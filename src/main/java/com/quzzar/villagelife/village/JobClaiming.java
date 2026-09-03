package com.quzzar.villagelife.village;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.VillagelifeAttachments;
import com.quzzar.villagelife.llm.LlmDecision;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.persona.PersonaData;
import com.quzzar.villagelife.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * Job placement and reorganization for the campfire model (campfire map #18,
 * upgraded by #38): each open job is filled from the idle campfire pool by
 * aptitude ({@link JobAptitudes}, FIFO as the tiebreaker), and a slow-tick swap
 * pass reorganizes workers only when the improvement clears the configured
 * threshold, with a per-person cooldown so villages don't churn.
 *
 * <p>The rules gate to competence and the model picks within it: when two or
 * more idle people are near-equally suited to an open post ({@link #PICK_DELTA}
 * apart) and the brain is ready, it chooses among them on character and says
 * why (the {@code decide()} pattern of {@code llm-brain.md}); an uncontested
 * post, or an absent or slow brain, falls straight to the aptitude best. One
 * decision is in flight per village, mirroring the project planner. The swap
 * pass stays purely rule-based. Runs from {@link Village#update}.
 *
 * <p>Employment requires housing (Aaron, 2026-08-31): a post can only be held
 * by someone with a bed. Claiming and the swap pass skip a bedless candidate
 * unless the village can house them for THAT post, either from a free general
 * bed or by moving them into the post's own live-in bed
 * ({@code Village.hasFreeGeneralBed}/{@code hasFreeBedIn}); a free bed in some
 * other workplace is reserved for that building and does not count.
 * {@link #releaseUnhousedWorkers} stands down a worker the village can no longer
 * house. Workstations that carry live-in beds (the lumberjack hut, the
 * watchtower, the upper blacksmith, the church) reserve that bed for their own
 * worker: general housing never takes it ({@code Village.isReservedWorkplaceBed}),
 * so the post the bed exists to staff can always claim it, and
 * {@code Village.assignJob} moves the worker in.
 */
public final class JobClaiming {

  private JobClaiming() {
  }

  /**
   * How close in aptitude two people must be to count as near-equally suited, on
   * the 3-18 stat scale. Within this the brain picks among them on character;
   * beyond it the better-suited person simply takes the post, no call spent. A
   * tunable worth moving to config if it stays (as the swap threshold already is).
   */
  private static final double PICK_DELTA = 2.0;

  /** The most applicants offered to the brain at once, to keep the prompt small. */
  private static final int MAX_APPLICANTS = 4;

  /** A loaded idle person and their aptitude score for the post under consideration. */
  private record Applicant(RealPerson person, double score) {
  }

  /** One reconciliation-and-claiming pass every second; swaps on the slow tick. */
  public static void tick(Village village, ServerLevel level) {
    releaseInvalidAssignments(village, level);
    if (isSlowTick(village, level)) {
      registerMissingStations(village);
      registerMissingBeds(village);
    }
    releaseOrphanedWorkers(village, level);
    releaseUnhousedWorkers(village, level);
    claimOpenJobs(village, level);
    maybeRunSwapPass(village, level);
  }

  /**
   * A building definition redefined by a datapack reload is not a building
   * removal, so nothing else fires when a station list shrinks or reorders
   * under live assignments. Station indexes rely on the definition's insertion
   * order, so a stale assignment either dangles or silently re-points at a
   * DIFFERENT station (a guard quietly becomes a miner). Any held assignment
   * whose station no longer exists, or whose occupation no longer matches the
   * station it points at, is released: the person returns to the campfire pool
   * and gets claimed by whatever is genuinely open.
   */
  private static void releaseInvalidAssignments(Village village, ServerLevel level) {
    for (Map.Entry<UUID, JobAssignment> entry : village.getJobAssignmentsView().entrySet()) {
      JobAssignment job = entry.getValue();
      if (isStationValid(village, job)) {
        continue;
      }
      village.releaseJob(entry.getKey()); // dropped, never re-queued: the station is gone
      RealPerson person = village.getPerson(level, entry.getKey());
      Villagelife.LOGGER.warn(
          "Released {}'s {} assignment in '{}': its station no longer matches the building definition",
          person != null ? person.getFullName() : entry.getKey(), job.getOccupation(), village.getName());
      // The orphan pass this same tick returns them to idle.
    }
  }

  /**
   * The growing half of the same reconciliation: a definition that GAINS a
   * station under a standing building registers nothing, because stations are
   * only ever registered when a building is first added. A datapack author
   * editing a definition on a live world, or a building upgraded in place,
   * would leave real work nobody could claim.
   *
   * Every station in the current definition must be represented exactly once,
   * either by a booked assignment or by an open one; whatever is missing is
   * registered as open. Additive only: shrink and reorder belong to
   * {@link #releaseInvalidAssignments}, so between them a definition change
   * self-heals in any direction.
   */
  public static void registerMissingStations(Village village) {
    List<JobAssignment> open = village.getUnassignedJobs();
    for (Building building : village.getBuildings()) {
      UUID buildingId = building.getUUID();
      int index = 0;
      for (Occupation occupation : building.getInfo().getWorkLocations().values()) {
        if (!isStationRepresented(village, open, buildingId, index)) {
          open.add(new JobAssignment(null, occupation, buildingId, index));
          Villagelife.LOGGER.info("Registered a new {} station in '{}': the building definition grew",
              occupation, village.getName());
        }
        index++;
      }
    }
  }

  /**
   * The same additive reconciliation for beds. An upgraded house or village
   * center gains beds, and beds are registered only when a building is first
   * added, so without this the new ones exist in the world and in the
   * definition while nobody can ever be assigned to them.
   */
  public static void registerMissingBeds(Village village) {
    List<BedAssignment> open = village.getUnassignedBeds();
    for (Building building : village.getBuildings()) {
      UUID buildingId = building.getUUID();
      int count = building.getInfo() == null ? 0 : building.getInfo().getBedLocations().size();
      for (int index = 0; index < count; index++) {
        if (!isBedRepresented(village, open, buildingId, index)) {
          open.add(new BedAssignment(null, buildingId, index));
          Villagelife.LOGGER.info("Registered a new bed in '{}': the building definition grew",
              village.getName());
        }
      }
    }
  }

  /** True when this bed is already someone's or already on the open list. */
  private static boolean isBedRepresented(Village village, List<BedAssignment> open, UUID buildingId,
      int bedIndex) {
    for (BedAssignment bed : village.getBedAssignmentsView().values()) {
      if (bed.getBedIndex() == bedIndex && buildingId.equals(bed.getBuildingUUID())) {
        return true;
      }
    }
    for (BedAssignment bed : open) {
      if (bed.getBedIndex() == bedIndex && buildingId.equals(bed.getBuildingUUID())) {
        return true;
      }
    }
    return false;
  }

  /** True when this station is already booked by someone or already on the open list. */
  private static boolean isStationRepresented(Village village, List<JobAssignment> open, UUID buildingId,
      int stationIndex) {
    for (JobAssignment job : village.getJobAssignmentsView().values()) {
      if (job.getStationIndex() == stationIndex && buildingId.equals(job.getBuildingUUID())) {
        return true;
      }
    }
    for (JobAssignment job : open) {
      if (job.getStationIndex() == stationIndex && buildingId.equals(job.getBuildingUUID())) {
        return true;
      }
    }
    return false;
  }

  /** True when the assignment's station index still exists and still carries its occupation. */
  private static boolean isStationValid(Village village, JobAssignment job) {
    Building building = village.getBuilding(job.getBuildingUUID());
    if (building == null) {
      return false;
    }
    int index = 0;
    for (Occupation occupation : building.getInfo().getWorkLocations().values()) {
      if (index == job.getStationIndex()) {
        return occupation == job.getOccupation();
      }
      index++;
    }
    return false; // index past the end: the station list shrank
  }

  /**
   * Anyone holding an occupation without a booked job assignment goes back to
   * idle: this is how removed buildings and vacated jobs return people to the
   * campfire pool, with no hook needed at the removal sites.
   */
  private static void releaseOrphanedWorkers(Village village, ServerLevel level) {
    for (UUID personId : new ArrayList<>(village.getPopulation())) {
      if (village.getJobAssignment(personId) != null) {
        continue;
      }
      RealPerson person = village.getPerson(level, personId);
      if (person == null || person.getOccupation().isIdle()) {
        continue;
      }
      Villagelife.LOGGER.debug("{} lost their job; returning to the campfire pool", person.getFullName());
      person.setOccupation(Occupation.WANDERER);
      person.setTravelTarget(null);
      person.reloadState();
    }
  }

  /**
   * A worker with no bed and no prospect of one stands down: their post
   * reopens for someone housed, and they return to the campfire pool. Runs
   * after {@code reconcileBeds} in the same village tick, so a bedless worker
   * the village can still house (a free general bed reconcile will take, or a
   * free bed in their own workplace) is left alone and housed within the tick;
   * only one it genuinely cannot house is released. A free bed in some OTHER
   * workplace is reserved for that building and does not save them.
   *
   * <p>Unlike {@link #releaseInvalidAssignments}, the released post is
   * re-queued: the station is fine, the worker just cannot keep it. An
   * unloaded worker is released all the same; the orphan pass sets them idle
   * when they next load.
   */
  private static void releaseUnhousedWorkers(Village village, ServerLevel level) {
    Map<UUID, BedAssignment> beds = village.getBedAssignmentsView();
    for (Map.Entry<UUID, JobAssignment> entry : village.getJobAssignmentsView().entrySet()) {
      UUID workerId = entry.getKey();
      if (beds.containsKey(workerId)) {
        continue;
      }
      if (village.hasFreeGeneralBed() || village.hasFreeBedIn(entry.getValue().getBuildingUUID())) {
        continue; // housable this tick; reconcile or assignment will seat them
      }
      JobAssignment released = village.releaseJob(workerId);
      if (released == null) {
        continue;
      }
      village.getUnassignedJobs().add(released);
      RealPerson person = village.getPerson(level, workerId);
      if (person != null) {
        person.setOccupation(Occupation.WANDERER);
        person.setTravelTarget(null);
        person.reloadState();
        person.logIssue("I gave up the " + released.getOccupation().name().toLowerCase(Locale.ROOT)
            + " work; there is no bed for me in this village.", Optional.empty());
      }
      Villagelife.LOGGER.info("{} stood down from the {} job in '{}': no bed for them",
          person != null ? person.getFullName() : workerId, released.getOccupation(), village.getName());
    }
  }

  /**
   * Oldest open job first. The single best-suited idle person takes an
   * uncontested post on the spot (FIFO breaks ties); a post two or more people
   * are near-equally suited to is offered to the brain, which picks among them
   * and pauses claiming until the answer lands.
   */
  private static void claimOpenJobs(Village village, ServerLevel level) {
    if (village.isJobDecisionPending()) {
      return; // a pick is in flight; the post it is deciding stays open until it lands
    }
    while (!village.getUnassignedJobs().isEmpty()) {
      JobAssignment job = nextOpening(village);
      if (job == null) {
        return; // only builder posts the village has not grown into remain
      }
      if (!isStationValid(village, job)) {
        // Stale opening from a redefined building: drop it, never fill it.
        village.getUnassignedJobs().remove(job);
        Villagelife.LOGGER.warn("Dropped a stale open {} job in '{}': station no longer in the building definition",
            job.getOccupation(), village.getName());
        continue;
      }
      List<Applicant> shortlist = shortlistFor(village, level, job);
      if (shortlist.isEmpty()) {
        return; // nobody claimable is loaded; try again next pass
      }
      if (shortlist.size() >= 2 && LlmService.get().isReady()) {
        askBrainToPick(village, level, job, shortlist);
        return; // one decision in flight; the rest of the queue waits for a later pass
      }
      Applicant best = shortlist.get(0);
      assignJob(village, level, best.person(), job, best.score());
    }
  }

  /**
   * The next post to fill, or null when only posts the village has not grown
   * into remain: the first open post for a trade nobody in the village holds
   * yet, else simply the first. Posts are otherwise filled in registration
   * order, and the town centre registers three BUILDER posts at founding
   * (docs/worker-loops.md). The second and third open with population
   * ({@link Village#isPostUnlocked}), and every trade is staffed once before
   * any is doubled, so a camp's first hires are its miner and quartermaster
   * and never three builders: a camp with no miner never gets its stone.
   */
  @Nullable
  private static JobAssignment nextOpening(Village village) {
    List<JobAssignment> open = village.getUnassignedJobs();
    JobAssignment first = null;
    for (JobAssignment job : open) {
      if (!village.isPostUnlocked(job)) {
        continue;
      }
      if (!village.hasWorkerOf(job.getOccupation())) {
        return job;
      }
      if (first == null) {
        first = job;
      }
    }
    return first;
  }

  /**
   * The loaded idle people best suited to a post: scored by aptitude, sorted
   * best first (a stable sort keeps arrival order among equals, so FIFO still
   * breaks ties), and cut to those within {@link #PICK_DELTA} of the top score.
   * A clear winner yields a shortlist of one and no deliberation; a genuine
   * near-tie yields the handful the brain then chooses among.
   */
  private static List<Applicant> shortlistFor(Village village, ServerLevel level, JobAssignment job) {
    // Housing gate: a bedless candidate can hold this post only if the village
    // can house them, either from a free general bed or by moving them into this
    // workplace's own live-in bed (Village.assignJob seats them). A free bed in
    // some OTHER workplace does not count: it is reserved for that building.
    boolean canHouseHere = village.hasFreeGeneralBed() || village.hasFreeBedIn(job.getBuildingUUID());
    Map<UUID, BedAssignment> beds = village.getBedAssignmentsView();
    List<Applicant> applicants = new ArrayList<>();
    for (UUID idleId : village.idlePeople()) {
      if (!canHouseHere && !beds.containsKey(idleId)) {
        continue; // employment requires housing, and there is none for them
      }
      RealPerson person = village.getPerson(level, idleId);
      if (person == null) {
        continue; // not loaded right now; the next pass will see them
      }
      applicants.add(new Applicant(person, JobAptitudes.score(person.getStatBlock(), job.getOccupation())));
    }
    if (applicants.isEmpty()) {
      return List.of();
    }
    applicants.sort(Comparator.comparingDouble(Applicant::score).reversed());
    double best = applicants.get(0).score();
    List<Applicant> shortlist = new ArrayList<>();
    for (Applicant applicant : applicants) {
      if (best - applicant.score() > PICK_DELTA || shortlist.size() >= MAX_APPLICANTS) {
        break;
      }
      shortlist.add(applicant);
    }
    return shortlist;
  }

  /**
   * Offers the shortlist to the brain and applies its pick when it lands. The
   * aptitude best is the fallback for every unusable answer (no reply, a
   * timeout, an out-of-range index). The world can move in the up-to-60s the
   * call takes, so the pick is re-validated against live state before it is
   * applied; if it and the fallback have both left the pool, the next claiming
   * pass simply starts over.
   */
  private static void askBrainToPick(Village village, ServerLevel level, JobAssignment job,
      List<Applicant> shortlist) {
    village.setJobDecisionPending(true);
    Applicant ruleChoice = shortlist.get(0);
    List<String> options = shortlist.stream().map(applicant -> describeApplicant(applicant.person())).toList();
    String purpose = "who takes the " + job.getOccupation().name().toLowerCase(java.util.Locale.ROOT)
        + " post at " + village.getName();
    LlmService.get().decide(purpose, situationOf(village, job), options)
        .whenComplete((decision, error) -> {
          if (level == null) {
            village.setJobDecisionPending(false);
            return;
          }
          level.getServer().execute(() -> {
            village.setJobDecisionPending(false);
            applyPick(village, level, job, shortlist, ruleChoice, decision, error);
          });
        });
  }

  private static void applyPick(Village village, ServerLevel level, JobAssignment job,
      List<Applicant> shortlist, Applicant ruleChoice, Optional<LlmDecision> decision, Throwable error) {
    if (!village.getUnassignedJobs().contains(job) || !isStationValid(village, job)) {
      return; // the post was filled, dropped, or redefined while we waited
    }
    Applicant chosen = resolveChoice(village, job, shortlist, ruleChoice, decision, error);
    RealPerson person = liveClaimant(village, level, job, chosen);
    if (person == null) {
      chosen = ruleChoice; // the pick left the pool; the aptitude best is the safety net
      person = liveClaimant(village, level, job, chosen);
    }
    if (person == null) {
      return; // even the fallback is gone; let the next pass start fresh
    }
    assignJob(village, level, person, job, chosen.score());
  }

  /**
   * Maps the brain's answer back to an applicant, logging its reason on a real
   * pick. Empty, errored, or out-of-range answers fall back to the aptitude best
   * the shortlist was built around.
   */
  private static Applicant resolveChoice(Village village, JobAssignment job, List<Applicant> shortlist,
      Applicant ruleChoice, Optional<LlmDecision> decision, Throwable error) {
    if (error != null) {
      Villagelife.LOGGER.error("'{}' failed to choose who takes the {} post",
          village.getName(), job.getOccupation(), error);
      return ruleChoice;
    }
    if (decision == null || decision.isEmpty()) {
      return ruleChoice; // no answer; the best suited by the rules takes it
    }
    int index = decision.get().choiceIndex();
    if (index < 0 || index >= shortlist.size()) {
      return ruleChoice;
    }
    Applicant chosen = shortlist.get(index);
    Villagelife.LOGGER.info("'{}' chose {} for the {} post: {}", village.getName(),
        chosen.person().getFullName(), job.getOccupation(), decision.get().reason());
    return chosen;
  }

  /**
   * The live entity for an applicant if it is still a loaded, idle, unassigned
   * claimant, otherwise null. Re-fetched rather than trusting the reference the
   * shortlist captured up to a minute earlier, which may be a stale instance if
   * the person unloaded and came back in the meantime.
   */
  private static RealPerson liveClaimant(Village village, ServerLevel level, JobAssignment job,
      Applicant applicant) {
    UUID id = applicant.person().getUUID();
    RealPerson person = village.getPerson(level, id);
    if (person == null || village.getJobAssignment(id) != null || !person.getOccupation().isIdle()) {
      return null;
    }
    boolean housed = village.getBedAssignmentsView().containsKey(id);
    if (!housed && !village.hasFreeGeneralBed() && !village.hasFreeBedIn(job.getBuildingUUID())) {
      return null; // housing for them went away while the brain deliberated; not seatable now
    }
    return person;
  }

  /** The post to fill, in the model's own terms. The numbered options are the applicants. */
  private static String situationOf(Village village, JobAssignment job) {
    String occupation = job.getOccupation().name().toLowerCase(Locale.ROOT);
    return "You are the collective judgement of " + village.getName()
        + ", deciding who from the campfire takes up a trade. The " + occupation
        + "'s post stands open, and these idle folk are all well suited to it. Choose who should"
        + " take it, in keeping with who they are, and give your reason in a few words.";
  }

  /**
   * An applicant as the model sees them: their name, and their persona blurb
   * when they have one, so the choice can turn on character. Competence is
   * already guaranteed (the shortlist is only ever the near-equally suited), so
   * what is left to weigh is who each person is.
   */
  private static String describeApplicant(RealPerson person) {
    PersonaData persona = person.getData(VillagelifeAttachments.PERSONA.get());
    if (persona != null && !persona.isEmpty() && !persona.blurb().isBlank()) {
      return person.getFullName() + ": " + persona.blurb();
    }
    return person.getFullName();
  }

  /** Books the assignment, sends the worker off to the post, marks the cooldown. */
  private static void assignJob(Village village, ServerLevel level, RealPerson person, JobAssignment job,
      double score) {
    village.assignJob(person.getUUID(), job);
    startJob(village, person, job);
    markCooldown(village, person.getUUID(), level.getGameTime());
    Villagelife.LOGGER.debug("{} claimed the open {} job (aptitude {})", person.getFullName(),
        job.getOccupation(), String.format("%.1f", score));
  }

  /**
   * Occupation, the bare starting kit, visible commute to the workplace, goal
   * refresh. The kit is issued here and nowhere else: taking a job is the one
   * moment a tool appears from nothing, and only into a bare hand
   * ({@link RealPerson#issueStartingKit}).
   */
  static void startJob(Village village, RealPerson person, JobAssignment job) {
    person.setOccupation(job.getOccupation());
    // Taking a job is where a companion pet is granted: a hunter's dog, sometimes
    // a guard's or a quartermaster's (CompanionPets). The pet, once granted, stays
    // with the person through later job changes, so this only ever adds one.
    if (person.level() instanceof ServerLevel level) {
      CompanionPets.onJobAssigned(level, village, person, job.getOccupation());
    }
    person.issueStartingKit();
    Building workplace = village.getBuilding(job.getBuildingUUID());
    if (workplace != null) {
      person.setTravelTarget(BlockPos.of(workplace.getCenterLocation()));
    }
    person.reloadState();
  }

  // --- The swap pass (#38): threshold-gated reorganization on a slow tick ---

  /** Phase-staggered per village, so many villages don't all scan on one tick. */
  private static boolean isSlowTick(Village village, ServerLevel level) {
    int interval = Math.max(10, VillagelifeConfig.JobSwapIntervalSeconds);
    long gameSeconds = level.getGameTime() / 20;
    return (gameSeconds + Math.floorMod(village.getID().hashCode(), interval)) % interval == 0;
  }

  private static void maybeRunSwapPass(Village village, ServerLevel level) {
    if (!isSlowTick(village, level)) {
      return;
    }
    double threshold = VillagelifeConfig.JobSwapThreshold;
    long now = level.getGameTime();

    Map<UUID, JobAssignment> assignments = village.getJobAssignmentsView();
    Map<UUID, BedAssignment> beds = village.getBedAssignmentsView();

    // A) A markedly better-suited idle person takes over a worker's job.
    for (Map.Entry<UUID, JobAssignment> entry : assignments.entrySet()) {
      UUID workerId = entry.getKey();
      JobAssignment job = entry.getValue();
      if (isOnCooldown(village, workerId, now)) {
        continue;
      }
      RealPerson worker = village.getPerson(level, workerId);
      if (worker == null) {
        continue;
      }
      double workerScore = JobAptitudes.score(worker.getStatBlock(), job.getOccupation());
      // The housing gate, same as claiming: a bedless challenger can take over
      // this post only if the village can house them (the displaced worker keeps
      // their own bed), either from general housing or this workplace's own bed.
      boolean canHouseChallenger = village.hasFreeGeneralBed() || village.hasFreeBedIn(job.getBuildingUUID());

      RealPerson challenger = null;
      double challengerScore = workerScore;
      for (UUID idleId : village.idlePeople()) {
        if (isOnCooldown(village, idleId, now)) {
          continue;
        }
        if (!canHouseChallenger && !beds.containsKey(idleId)) {
          continue;
        }
        RealPerson candidate = village.getPerson(level, idleId);
        if (candidate == null) {
          continue;
        }
        double score = JobAptitudes.score(candidate.getStatBlock(), job.getOccupation());
        if (score > challengerScore) {
          challenger = candidate;
          challengerScore = score;
        }
      }
      if (challenger == null || challengerScore - workerScore < threshold) {
        continue;
      }

      JobAssignment released = village.releaseJob(workerId);
      if (released == null) {
        continue;
      }
      village.assignJob(challenger.getUUID(), released);
      startJob(village, challenger, released);
      worker.setOccupation(Occupation.WANDERER);
      worker.setTravelTarget(null);
      worker.reloadState();
      worker.logIssue("Lost the " + released.getOccupation().name().toLowerCase() + " job to "
          + challenger.getFullName() + "; the village decided they were better suited.",
          java.util.Optional.of(challenger.getUUID()));
      markCooldown(village, workerId, now);
      markCooldown(village, challenger.getUUID(), now);
      Villagelife.LOGGER.info("'{}' took over the {} job from '{}' in '{}' (aptitude {} vs {})",
          challenger.getFullName(), released.getOccupation(), worker.getFullName(), village.getName(),
          String.format("%.1f", challengerScore), String.format("%.1f", workerScore));
    }

    // B) One beneficial two-worker exchange per pass, when the NET gain clears
    // the threshold. One per pass keeps reorganization legible.
    List<Map.Entry<UUID, JobAssignment>> workers = new ArrayList<>(village.getJobAssignmentsView().entrySet());
    for (int i = 0; i < workers.size(); i++) {
      for (int j = i + 1; j < workers.size(); j++) {
        UUID firstId = workers.get(i).getKey();
        UUID secondId = workers.get(j).getKey();
        if (isOnCooldown(village, firstId, now) || isOnCooldown(village, secondId, now)) {
          continue;
        }
        JobAssignment firstJob = workers.get(i).getValue();
        JobAssignment secondJob = workers.get(j).getValue();
        if (firstJob.getOccupation() == secondJob.getOccupation()) {
          continue;
        }
        RealPerson first = village.getPerson(level, firstId);
        RealPerson second = village.getPerson(level, secondId);
        if (first == null || second == null) {
          continue;
        }
        double current = JobAptitudes.score(first.getStatBlock(), firstJob.getOccupation())
            + JobAptitudes.score(second.getStatBlock(), secondJob.getOccupation());
        double exchanged = JobAptitudes.score(second.getStatBlock(), firstJob.getOccupation())
            + JobAptitudes.score(first.getStatBlock(), secondJob.getOccupation());
        if (exchanged - current < threshold) {
          continue;
        }
        JobAssignment releasedFirst = village.releaseJob(firstId);
        JobAssignment releasedSecond = village.releaseJob(secondId);
        if (releasedFirst == null || releasedSecond == null) {
          continue;
        }
        village.assignJob(secondId, releasedFirst);
        village.assignJob(firstId, releasedSecond);
        startJob(village, second, releasedFirst);
        startJob(village, first, releasedSecond);
        markCooldown(village, firstId, now);
        markCooldown(village, secondId, now);
        Villagelife.LOGGER.info("'{}' and '{}' exchanged the {} and {} jobs in '{}' (net aptitude +{})",
            first.getFullName(), second.getFullName(), releasedFirst.getOccupation(),
            releasedSecond.getOccupation(), village.getName(), String.format("%.1f", exchanged - current));
        return; // one exchange per pass
      }
    }
  }

  static boolean isOnCooldown(Village village, UUID personId, long now) {
    CompoundTag cooldowns = village.getSwapCooldowns();
    String key = personId.toString();
    if (!cooldowns.contains(key)) {
      return false;
    }
    long cooldownTicks = (long) (VillagelifeConfig.JobSwapCooldownDays * 24000L);
    if (now - cooldowns.getLong(key) >= cooldownTicks) {
      cooldowns.remove(key); // expired; prune as we go
      return false;
    }
    return true;
  }

  static void markCooldown(Village village, UUID personId, long now) {
    village.getSwapCooldowns().putLong(personId.toString(), now);
  }
}
