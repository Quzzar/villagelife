package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.quzzar.kithkyn.entities.PersonalLogData;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.entities.SimulationClock;
import com.quzzar.kithkyn.entities.KithkynAttachments;
import com.quzzar.kithkyn.village.JobAssignment;
import com.quzzar.kithkyn.village.PopulationOutlook;
import com.quzzar.kithkyn.village.Village;
import com.quzzar.kithkyn.village.VillageAttractiveness;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

/**
 * Immutable facts about one village at one instant. Prompt consumers render
 * this snapshot; they do not independently recalculate population, housing,
 * construction, or worker state.
 */
public record VillageContextSnapshot(
    String name,
    String tier,
    int population,
    int employed,
    int idle,
    int preAdults,
    int dependentlyHoused,
    int dependentsWithoutResidentParent,
    int pendingArrivals,
    int totalBeds,
    int freeGeneralBeds,
    int freeReservedBeds,
    int unhousedAdults,
    Map<String, Integer> buildings,
    Map<String, Integer> openPosts,
    PopulationOutlook populationOutlook,
    double foodPerCapita,
    boolean recentDeaths,
    Optional<ConstructionPlan> currentProject,
    Optional<ConstructionPlan> savedGoal,
    List<String> recentBuilds,
    List<WorkerBlocker> workerBlockers,
    Optional<String> roomReport) {

  public enum ConstructionStage {
    GATHERING,
    BUILDING,
    SAVING
  }

  public record ConstructionPlan(String label, ConstructionStage stage, String reason,
      ConstructionQuote quote) {
  }

  public record WorkerBlocker(String occupation, String workerName, String text,
      long standingTicks) {
  }

  public VillageContextSnapshot {
    buildings = Collections.unmodifiableMap(new TreeMap<>(buildings));
    openPosts = Collections.unmodifiableMap(new TreeMap<>(openPosts));
    currentProject = currentProject == null ? Optional.empty() : currentProject;
    savedGoal = savedGoal == null ? Optional.empty() : savedGoal;
    recentBuilds = List.copyOf(recentBuilds);
    workerBlockers = List.copyOf(workerBlockers);
    roomReport = roomReport == null ? Optional.empty() : roomReport;
  }

  /** Captures all prompt-facing village facts from one storage tally. */
  public static VillageContextSnapshot capture(Village village) {
    return capture(village, village.stockTally());
  }

  /** Captures all prompt-facing facts while reusing a caller's storage tally. */
  public static VillageContextSnapshot capture(Village village, Map<Item, Integer> stock) {
    VillageAttractiveness attractiveness = village.getAttractiveness();

    Map<String, Integer> standing = new TreeMap<>();
    for (Building building : village.getBuildings()) {
      if (building.getInfo() != null) {
        standing.merge(building.getInfo().displayLabel(), 1, Integer::sum);
      }
    }

    Map<String, Integer> openings = new TreeMap<>();
    for (JobAssignment job : village.claimableJobs()) {
      openings.merge(job.getOccupation().name().toLowerCase(), 1, Integer::sum);
    }

    Optional<ConstructionPlan> project = Optional.empty();
    StructureInProgress current = village.getCurrentProject();
    if (current != null && current.getBuilding() != null && current.getBuilding().getInfo() != null) {
      BuildingInfo info = current.getBuilding().getInfo();
      project = Optional.of(new ConstructionPlan(info.displayLabel(),
          current.isGathering() ? ConstructionStage.GATHERING : ConstructionStage.BUILDING,
          "", ConstructionQuote.captureProject(village, current, stock)));
    }

    Optional<ConstructionPlan> goalPlan = Optional.empty();
    String goal = VillageGoal.current(village);
    BuildingInfo wanted = goal == null ? null : Buildings.getByName(goal);
    if (wanted != null) {
      String reason = VillageGoal.reason(village);
      goalPlan = Optional.of(new ConstructionPlan(wanted.displayLabel(), ConstructionStage.SAVING,
          reason == null ? "" : reason, ConstructionQuote.captureGoal(village, wanted, stock)));
    }

    List<String> completed = new ArrayList<>();
    for (Village.CompletedBuild build : village.getRecentBuilds()) {
      completed.add("a " + build.label() + " (" + PersonalLogData.formatDay(build.dayTime()) + ")");
    }

    List<WorkerBlocker> blockers = activeWorkerBlockers(village);
    int freeReserved = Math.max(0, village.getFreeBedCount() - village.getFreeGeneralBedCount());
    return new VillageContextSnapshot(
        village.getName(), tierName(village), village.getPopulation().size(),
        village.getJobAssignmentsView().size(), village.idlePeople().size(),
        village.getPreAdultResidentCount(), village.getDependentlyHousedResidentCount(),
        village.getDependentWithoutResidentParentCount(), village.getPendingArrivalCount(),
        village.getTotalBeds(), village.getFreeGeneralBedCount(), freeReserved,
        village.getUnhousedAdultResidentCount(), standing, openings,
        village.getPopulationOutlook(), attractiveness.foodPerCapita(),
        attractiveness.deathImpact() > 0.5F, project, goalPlan, completed, blockers,
        Optional.ofNullable(village.describeRoom()));
  }

  /** Compact shared facts for the collective build decision. */
  public String plannerBriefing() {
    StringBuilder text = new StringBuilder();
    text.append("You are the collective judgement of ").append(name)
        .append(", a ").append(tier).append(". ")
        .append(populationFacts()).append(' ')
        .append(housingFacts()).append(' ')
        .append(PlannerFacts.housingConstraint(unhousedAdults, freeGeneralBeds,
            openPosts.values().stream().mapToInt(Integer::intValue).sum()))
        .append(PlannerFacts.existingBuildings(buildings))
        .append(PlannerFacts.openPosts(openPosts))
        .append(outlookFacts()).append(' ')
        .append(String.format("Food stores stand at %.1f items per person. ", foodPerCapita));
    if (recentDeaths) {
      text.append("There have been deaths recently. ");
    }
    appendWorkerBlockers(text);
    return text.toString();
  }

  /** Multi-line shared facts for an individual resident's conversation. */
  public String chatBriefing() {
    StringBuilder text = new StringBuilder();
    text.append("Your village: ").append(name).append(", a ").append(tier).append(".\n")
        .append("Village population: ").append(populationFacts()).append('\n')
        .append("Village housing: ").append(housingFacts()).append('\n');
    String existing = PlannerFacts.existingBuildings(buildings);
    text.append(existing.isEmpty() ? "Standing buildings: none.\n"
        : existing.replace("Already standing", "Standing buildings") + "\n");
    String work = PlannerFacts.openPosts(openPosts);
    text.append(work.isEmpty() ? "Open work: none.\n" : work + "\n");
    text.append("Population outlook: ").append(outlookFacts()).append('\n');
    text.append(String.format("Village food stores: %.1f items per person.\n", foodPerCapita));
    appendConstruction(text);
    if (!recentBuilds.isEmpty()) {
      text.append("Lately the village finished building ").append(joinNatural(recentBuilds)).append(".\n");
    }
    roomReport.ifPresent(room -> text.append(room).append('\n'));
    appendWorkerBlockers(text);
    if (!workerBlockers.isEmpty()) {
      text.append('\n');
    }
    return text.toString();
  }

  private String populationFacts() {
    StringBuilder text = new StringBuilder();
    text.append(population).append(" residents; ").append(employed).append(" hold jobs; ")
        .append(idle).append(" work-eligible ")
        .append(idle == 1 ? "resident has" : "residents have").append(" no post");
    if (preAdults > 0) {
      text.append(". ").append(preAdults).append(preAdults == 1 ? " is a pre-adult" : " are pre-adults")
          .append("; working teenagers also appear in the job counts");
    }
    if (pendingArrivals > 0) {
      text.append(". ").append(pendingArrivals).append(pendingArrivals == 1 ? " person is" : " people are")
          .append(" still arriving");
    }
    return text.append('.').toString();
  }

  private String housingFacts() {
    StringBuilder text = new StringBuilder();
    text.append(totalBeds).append(" independent beds total; ")
        .append(freeGeneralBeds).append(" general ").append(freeGeneralBeds == 1 ? "bed" : "beds")
        .append(" free; ").append(freeReservedBeds).append(" free live-in ")
        .append(freeReservedBeds == 1 ? "bed is" : "beds are").append(" reserved to their workplaces; ")
        .append(unhousedAdults == 0 ? "no adult residents need" : unhousedAdults + " adult "
            + (unhousedAdults == 1 ? "resident needs" : "residents need"))
        .append(" independent housing. ")
        .append(dependentHousingFacts());
    return text.toString();
  }

  private String dependentHousingFacts() {
    if (preAdults == 0) {
      return "There are no pre-adults awaiting family housing.";
    }
    int withoutHome = Math.max(0, preAdults - dependentlyHoused);
    if (withoutHome == 0) {
      return preAdults == 1
          ? "The pre-adult is housed with a resident parent."
          : "All " + preAdults + " pre-adults are housed with resident parents.";
    }

    StringBuilder text = new StringBuilder();
    if (dependentlyHoused == 0) {
      text.append("None of the ").append(preAdults)
          .append(" pre-adults has a usable resident parent's home");
    } else {
      text.append(dependentlyHoused).append(" of the ").append(preAdults)
          .append(" pre-adults ")
          .append(dependentlyHoused == 1 ? "is" : "are")
          .append(" housed with a resident parent; ").append(withoutHome)
          .append(withoutHome == 1 ? " lacks" : " lack").append(" a usable family home");
    }
    if (dependentsWithoutResidentParent > 0) {
      text.append("; ").append(dependentsWithoutResidentParent)
          .append(dependentsWithoutResidentParent == 1 ? " has" : " have")
          .append(" no resident parent recorded");
    }
    return text.append(". ")
        .append(withoutHome == 1 ? "That child stays" : "Those children stay")
        .append(" by the campfire at night.").toString();
  }

  private String outlookFacts() {
    return populationOutlook.describe();
  }

  private void appendConstruction(StringBuilder text) {
    if (savedGoal.isPresent()) {
      ConstructionPlan goal = savedGoal.get();
      text.append("Your village is saving up to build a ").append(goal.label());
      if (!goal.reason().isBlank()) {
        text.append(" (").append(goal.reason()).append(')');
      }
      text.append(". It needs ").append(goal.quote().describeRequired()).append(". ")
          .append(goal.quote().affordable() ? "Everything needed is gathered."
              : "Still short " + goal.quote().describeMissing() + ".")
          .append('\n');
    }
    if (currentProject.isPresent()) {
      ConstructionPlan project = currentProject.get();
      text.append(project.stage() == ConstructionStage.GATHERING
          ? "Your village is gathering materials to build a "
          : "Your village is now building a ").append(project.label()).append(".\n");
      if (project.stage() == ConstructionStage.GATHERING) {
        text.append("That project needs ").append(project.quote().describeRequired()).append(". ")
            .append(project.quote().affordable()
                ? "Everything needed is either stored or already carried by its builders."
                : "Still short " + project.quote().describeMissing() + ".")
            .append('\n');
      }
    } else if (savedGoal.isEmpty()) {
      text.append("Your village is not saving up for or building anything at the moment.\n");
    }
  }

  private void appendWorkerBlockers(StringBuilder text) {
    if (workerBlockers.isEmpty()) {
      return;
    }
    text.append("Trouble your workers currently report: ");
    List<String> lines = new ArrayList<>();
    for (WorkerBlocker blocker : workerBlockers) {
      long days = blocker.standingTicks() / 24000L;
      String standing = days == 0 ? "since today" : "for " + days + (days == 1 ? " day" : " days");
      lines.add("your " + blocker.occupation() + " " + blocker.workerName() + ", " + standing
          + ": \"" + blocker.text() + "\"");
    }
    text.append(String.join("; ", lines)).append(". ");
  }

  private static List<WorkerBlocker> activeWorkerBlockers(Village village) {
    ServerLevel level = village.getLevel();
    if (level == null) {
      return List.of();
    }
    long now = level.getGameTime();
    List<WorkerBlocker> blockers = new ArrayList<>();
    for (Map.Entry<java.util.UUID, JobAssignment> assignment : village.getJobAssignmentsView().entrySet()) {
      RealPerson worker = village.getPerson(level, assignment.getKey());
      if (worker == null) {
        continue;
      }
      Optional<PersonalLogData.ActiveBlocker> blocker =
          worker.getData(KithkynAttachments.PERSONAL_LOG.get()).standingBlocker(now);
      if (blocker.isEmpty()) {
        continue;
      }
      long age = SimulationClock.elapsed(now, blocker.get().since()).orElse(0L);
      blockers.add(new WorkerBlocker(
          assignment.getValue().getOccupation().name().toLowerCase(), worker.getFullName(),
          blocker.get().text(), age));
    }
    return blockers;
  }

  private static String tierName(Village village) {
    String id = village.getTierId();
    int colon = id.indexOf(':');
    return colon >= 0 ? id.substring(colon + 1) : id;
  }

  private static String joinNatural(List<String> items) {
    if (items.size() == 1) {
      return items.get(0);
    }
    if (items.size() == 2) {
      return items.get(0) + " and " + items.get(1);
    }
    return String.join(", ", items.subList(0, items.size() - 1)) + ", and "
        + items.get(items.size() - 1);
  }
}
