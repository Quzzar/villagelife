package com.quzzar.kithkyn.village;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.configuration.KithkynConfig;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.llm.LlmDecision;
import com.quzzar.kithkyn.llm.LlmService;
import com.quzzar.kithkyn.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * When a village is going hungry beside an empty field, the brain is asked to
 * put someone on it. The claiming pass ({@link JobClaiming}) fills an open post
 * from the IDLE only, and the swap pass only trades posts to raise aptitude;
 * neither ever takes a settled worker off a job the village can spare and moves
 * them to one it cannot. So a village that built a farm but grew no farmer
 * starves next to it forever, and starving is exactly what stops it growing the
 * newcomer who would farm (docs/population-and-labor.md): a deadlock a hungry
 * village cannot break on its own.
 *
 * <p>During the midnight rebalance window, then: if food is below target, a
 * food-producing post stands open with its building up, and there is no idle
 * hand to take it, the brain is shown the shortage and the crew and picks who
 * moves to the field, or that no one should. It decides WHO the same way it
 * decides what to build: the facts are laid out and the model chooses, no
 * ranking of its own (Aaron, 2026-09-02). Only loaded workers can be moved,
 * since a reassignment awakens them, brings them to the campfire, and rebuilds
 * their goals in the world. One decision is in flight per village, like the
 * build planner and the marriage verdict, and a village whose brain leaves the
 * crew as it is sits the question out a while rather than asking it every pass.
 */
public final class LaborPlanner {

  private LaborPlanner() {
  }

  /** Phase-staggered per village, like the build decision, since the verdict is a model call. */
  public static final int LABOR_INTERVAL_SECONDS = 60;

  /** The jobs that put food in the stores. */
  private static final Set<Occupation> FOOD_PRODUCERS =
      EnumSet.of(Occupation.FARMER, Occupation.FISHER, Occupation.HUNTER);

  /**
   * The trades a village always keeps at least one of: its miner (no miner, no
   * stone, and the build economy stalls) and its builder (no builder, and a
   * project in progress never finishes, so the village sits stuck on it and
   * never advances to houses). The last of either is never moved off to the
   * field, however hungry the village; a second, if there is one, may still go
   * (Aaron, 2026-09-03).
   */
  private static final Set<Occupation> ALWAYS_STAFFED =
      EnumSet.of(Occupation.MINER, Occupation.BUILDER);

  /** Village-seconds a village waits out after the brain leaves the crew as it is. */
  private static final int QUIET_SECONDS = 300;

  /** Village-time each village may be asked again, so a lasting shortage is not re-asked every pass. */
  private static final ConcurrentHashMap<String, Integer> quietUntil = new ConcurrentHashMap<>();

  private static final String LEAVE_AS_IS = "Leave the crew as it is for now";

  /**
   * One look, on the caller's cadence: ask the brain to move someone to the
   * field only when the village is hungry, the field is up and empty, and no
   * one is spare to take it. A no on every count is the common case and costs
   * nothing.
   */
  public static void tick(Village village, ServerLevel level) {
    if (!JobClaiming.isMidnightRebalanceTime(level.getDayTime())) {
      return;
    }
    if (village.isLaborDecisionPending() || level.getServer() == null || !LlmService.get().isReady()) {
      return;
    }
    Integer quiet = quietUntil.get(village.getID());
    if (quiet != null && village.getVillageTime() < quiet) {
      return;
    }
    // Nothing can be reassigned in a village nobody is near: the worker has to
    // be loaded to have its goals rebuilt, and asking the brain would spend a
    // model call on an answer no one could act on.
    Building centre = village.getTownCenter();
    if (centre == null || !level.hasChunkAt(BlockPos.of(centre.getCenterLocation()))) {
      return;
    }
    // An idle hand is claimed by the ordinary claiming pass; only step in when
    // the village has no one spare and would otherwise leave the field empty.
    if (!village.idlePeople().isEmpty() || !isHungry(village)) {
      return;
    }
    JobAssignment vacancy = openFoodPost(village);
    if (vacancy == null) {
      return;
    }
    List<RealPerson> crew = movableWorkers(village, level, vacancy);
    if (crew.isEmpty()) {
      return;
    }
    ask(village, level, vacancy, crew);
  }

  /** Food stored is below the per-capita target the newcomer math wants (VillageAttractiveness). */
  private static boolean isHungry(Village village) {
    VillageAttractiveness report = village.getAttractiveness();
    return report != null
        && report.foodCount() < report.population() * KithkynConfig.AttractivenessFoodTargetPerCapita;
  }

  /** The first open food post whose building actually stands, or null when none is going wanting. */
  private static JobAssignment openFoodPost(Village village) {
    for (JobAssignment job : village.claimableJobs()) {
      if (FOOD_PRODUCERS.contains(job.getOccupation()) && village.getBuilding(job.getBuildingUUID()) != null) {
        return job;
      }
    }
    return null;
  }

  /**
   * Loaded workers a reassignment could actually move onto the field. Three are
   * held back: whoever already does the wanted job; the last miner and the last
   * builder, the trades a village must always keep ({@link #ALWAYS_STAFFED});
   * and anyone still inside their job-swap cooldown, so a person just placed or
   * moved is left to settle rather than yanked straight onto the field
   * (the same per-person cooldown the aptitude swap pass respects,
   * {@link JobClaiming#isOnCooldown}).
   */
  private static List<RealPerson> movableWorkers(Village village, ServerLevel level, JobAssignment vacancy) {
    long now = level.getGameTime();
    List<RealPerson> crew = new ArrayList<>();
    for (var entry : village.getJobAssignmentsView().entrySet()) {
      Occupation occupation = entry.getValue().getOccupation();
      if (occupation == vacancy.getOccupation()) {
        continue;
      }
      if (ALWAYS_STAFFED.contains(occupation) && countOf(village, occupation) <= 1) {
        continue; // a village always keeps its miner and its builder
      }
      if (JobClaiming.isOnCooldown(village, entry.getKey(), now)) {
        continue; // recently placed, swapped, or displaced: let them settle
      }
      if (!village.canHouseForJob(entry.getKey(), vacancy.getBuildingUUID())) {
        continue; // their current workplace bed cannot follow them to this post
      }
      RealPerson worker = village.getPerson(level, entry.getKey());
      if (worker != null) {
        crew.add(worker);
      }
    }
    return crew;
  }

  /** How many villagers hold this occupation right now. */
  private static int countOf(Village village, Occupation occupation) {
    int count = 0;
    for (JobAssignment job : village.getJobAssignmentsView().values()) {
      if (job.getOccupation() == occupation) {
        count++;
      }
    }
    return count;
  }

  private static void ask(Village village, ServerLevel level, JobAssignment vacancy, List<RealPerson> crew) {
    String field = vacancy.getOccupation().name().toLowerCase();
    List<String> options = new ArrayList<>();
    for (RealPerson worker : crew) {
      options.add("Move " + worker.getFullName() + " off " + worker.getOccupation().name().toLowerCase()
          + " to work as the " + field);
    }
    options.add(LEAVE_AS_IS);

    List<UUID> crewIds = crew.stream().map(RealPerson::getUUID).toList();
    String situation = situationOf(village, field, crew);
    village.setLaborDecisionPending(true);
    LlmService.get().decide("who " + village.getName() + " puts on the field", situation, options)
        .whenComplete((result, error) -> {
          if (level.getServer() == null) {
            return;
          }
          level.getServer().execute(() -> apply(village, level, crewIds, field, result, error));
        });
  }

  private static String situationOf(Village village, String field, List<RealPerson> crew) {
    VillageAttractiveness report = village.getAttractiveness();
    StringBuilder situation = new StringBuilder(village.getName()).append(" is going hungry. ");
    if (report != null) {
      situation.append("It has ").append(report.foodCount()).append(" food stored for ")
          .append(report.population()).append(" people, below what keeps them fed, ")
          .append("and a hungry village stops drawing newcomers, so it will not simply grow a ")
          .append(field).append(" of its own. ");
    }
    situation.append("The ").append(field).append("'s post stands open with its building up: food could be ")
        .append("produced, but no one is producing it, and no one is idle to take it. Filling it means moving ")
        .append("someone off their work, which leaves that work open in turn. The crew: ");
    for (RealPerson worker : crew) {
      situation.append(worker.getFullName()).append(" works as the ")
          .append(worker.getOccupation().name().toLowerCase()).append("; ");
    }
    return situation.toString();
  }

  /**
   * Applies the brain's pick on the server thread: move the chosen worker onto
   * the food post, or wait. The post is looked up fresh, since a claiming pass
   * may have filled it while the model thought; a worker who unloaded in the
   * meantime is simply skipped.
   */
  private static void apply(Village village, ServerLevel level, List<UUID> crewIds, String field,
      Optional<LlmDecision> result, Throwable error) {
    village.setLaborDecisionPending(false);
    if (!JobClaiming.isMidnightRebalanceTime(level.getDayTime())) {
      return;
    }
    if (error != null) {
      Kithkyn.LOGGER.error("[labor] '{}' could not decide who to put on the field", village.getName(), error);
      return;
    }
    if (result == null || result.isEmpty()) {
      return;
    }
    LlmDecision decision = result.get();
    if (decision.choiceIndex() < 0 || decision.choiceIndex() >= crewIds.size()) {
      // "Leave the crew as it is", or a garbled index: sit the question out so a
      // lasting shortage the brain will not act on is not re-asked every pass.
      quietUntil.put(village.getID(), village.getVillageTime() + QUIET_SECONDS);
      Kithkyn.LOGGER.info("[labor] '{}' leaves its crew as it is: {}", village.getName(), decision.reason());
      return;
    }
    RealPerson worker = village.getPerson(level, crewIds.get(decision.choiceIndex()));
    if (worker == null) {
      return;
    }
    JobAssignment post = openFoodPost(village);
    if (post == null || !village.canHouseForJob(worker.getUUID(), post.getBuildingUUID())) {
      return;
    }
    Occupation from = worker.getOccupation();
    JobAssignment vacated = village.releaseJob(worker.getUUID());
    if (vacated == null) {
      return;
    }
    JobClaiming.prepareForJobChange(village, worker);
    village.assignJob(worker.getUUID(), post);
    JobClaiming.startJob(village, worker, post);
    JobClaiming.markCooldown(village, worker.getUUID(), level.getGameTime());
    worker.logMemory("Moved off " + from.name().toLowerCase() + " to work as the " + field
        + "; the village needed the food more.", Optional.empty());
    Kithkyn.LOGGER.info("'{}' moved '{}' from {} to {} to feed the village: {}",
        village.getName(), worker.getFullName(), from, post.getOccupation(), decision.reason());
  }
}
