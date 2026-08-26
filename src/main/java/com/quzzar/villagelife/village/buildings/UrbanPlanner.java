package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.llm.LlmDecision;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.village.Occupation;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.VillageAttractiveness;
import com.quzzar.villagelife.village.bookkeeping.NoResourceBookkeepingEvent;

import net.minecraft.world.item.ItemStack;

/**
 * Decides what a village builds next.
 *
 * Two stages, in the order the design calls for (docs/village-tiers.md): the
 * rules filter, then the model chooses. Scoring here answers "what does this
 * village lack" from real state — beds against population, work stations
 * against idle people, storage and food, safety after deaths — and only
 * buildings the village can actually afford and legally build survive. The
 * LLM then picks among those legal options and never sees an illegal one, so
 * a failed, slow, or absent model costs nothing but the flavour of the choice:
 * the rules' own top pick stands in.
 */
public class UrbanPlanner {

  /**
   * How many options the model is asked to choose between. Aaron chose breadth
   * over a short list, so the village feels less railroaded; small local models
   * choose worse from a long list than a large one does, which is why it is a
   * config key rather than a constant.
   */
  private static int optionsOffered() {
    return com.quzzar.villagelife.configuration.VillagelifeConfig.BuildOptionsOffered;
  }

  /** Chosen when the village would rather wait than spend what it has. */
  private static final String WAIT_OPTION = "nothing yet: keep what we have and wait";

  /** A candidate building, with why the village might want it. */
  public record Candidate(BuildingInfo info, double score, String description) {}

  /**
   * Chooses the next project asynchronously: affordable candidates are ranked
   * by need, then offered to the model. Completes with null when the village
   * can build nothing right now.
   */
  public static CompletableFuture<BuildingInfo> chooseNextProject(Village village) {
    List<Candidate> candidates = rankCandidates(village);
    if (candidates.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }

    List<Candidate> offered = candidates.subList(0, Math.min(optionsOffered(), candidates.size()));
    Candidate ruleChoice = offered.get(0);
    if (offered.size() == 1 || !LlmService.get().isReady()) {
      return CompletableFuture.completedFuture(ruleChoice.info());
    }

    // Waiting is a real move: a brain that can only build will build until it
    // physically cannot, and a village that is saving up should be able to say so.
    List<String> options = new ArrayList<>(offered.stream().map(Candidate::description).toList());
    options.add(WAIT_OPTION);
    return LlmService.get().decide(situationOf(village), options)
        .thenApply(decision -> pick(village, offered, ruleChoice, decision));
  }

  private static BuildingInfo pick(Village village, List<Candidate> offered, Candidate ruleChoice,
      Optional<LlmDecision> decision) {
    if (decision.isEmpty()) {
      Villagelife.LOGGER.debug("Village '{}' had no answer from the brain; building {} on the rules' advice",
          village.getName(), ruleChoice.info().getName());
      return ruleChoice.info();
    }
    LlmDecision chosen = decision.get();
    int index = chosen.choiceIndex();
    if (index == offered.size()) {
      Villagelife.LOGGER.info("Village '{}' decided to build nothing for now: {}",
          village.getName(), chosen.reason());
      return null;
    }
    if (index < 0 || index > offered.size()) {
      return ruleChoice.info();
    }
    Candidate candidate = offered.get(index);
    Villagelife.LOGGER.info("Village '{}' decided to build {}: {}",
        village.getName(), candidate.info().getName(), chosen.reason());
    return candidate.info();
  }

  /** What the village is short of right now, in the model's own terms. */
  private static String situationOf(Village village) {
    int population = village.getPopulation().size();
    int beds = village.getTotalBeds();
    int idle = village.idlePeople().size();
    int openJobs = village.getUnassignedJobs().size();
    VillageAttractiveness report = village.getAttractiveness();

    StringBuilder situation = new StringBuilder();
    situation.append("You are the collective judgement of ").append(village.getName())
        .append(", a settlement of ").append(population).append(" people with ")
        .append(beds).append(" beds");
    if (idle > 0) {
      situation.append(", ").append(idle).append(" of them with no work");
    }
    if (openJobs > 0) {
      situation.append(" and ").append(openJobs).append(" jobs nobody has taken");
    }
    situation.append(". ");
    if (report != null) {
      situation.append(switch (report.status()) {
        case GROWING -> "Newcomers keep arriving. ";
        case HOLDING -> "The population is steady. ";
        case DECLINING -> "People are talking of leaving. ";
      });
      if (report.foodPerCapita() < 4.0) {
        situation.append("Food is short. ");
      }
      if (report.deathImpact() > 0.5F) {
        situation.append("There have been deaths recently. ");
      }
      if (population > beds) {
        situation.append("Some people have nowhere to sleep. ");
      }
    }
    situation.append("Choose what to build next.");
    return situation.toString();
  }

  /**
   * Every building the village could legally and affordably start, best first.
   * A level above 1 is excluded: those are reached by upgrading in place, which
   * nothing implements yet, so offering one would promise a building that can
   * never be built.
   */
  public static List<Candidate> rankCandidates(Village village) {
    int population = village.getPopulation().size();
    int beds = village.getTotalBeds();
    int idle = village.idlePeople().size();
    int openJobs = village.getUnassignedJobs().size();
    VillageAttractiveness report = village.getAttractiveness();

    // How badly each kind of building is wanted, from real state.
    double housingNeed = Math.max(0, population + 2 - beds);
    double workNeed = Math.max(0, idle - openJobs);
    double foodNeed = report != null && report.foodPerCapita() < 8.0 ? 8.0 - report.foodPerCapita() : 0.0;
    double safetyNeed = report != null ? Math.min(3.0, report.deathImpact() * 2.0) : 0.0;
    String variant = villageVariant(village);

    List<Candidate> candidates = new ArrayList<>();
    for (BuildingInfo info : Buildings.allBuildings().values()) {
      if (info.getName().equals(Buildings.VILLAGE_CENTER_NAME) || info.getUpgradesFrom() != null) {
        continue;
      }
      if (info.hasWellFormedId() && info.getLevel() > 1) {
        continue;
      }
      if (!hasMaterialsToConstruct(village, info)) {
        continue;
      }

      double score = 0.0;
      score += info.getBedLocations().size() * (1.0 + housingNeed);
      score += info.getWorkLocations().size() * (1.0 + workNeed);
      score += info.getContainerLocations().size() * 0.25;
      for (Occupation occupation : info.getWorkLocations().values()) {
        if (occupation == Occupation.GUARD) {
          score += safetyNeed;
        }
        if (occupation == Occupation.FARMER || occupation == Occupation.LUMBERJACK) {
          score += foodNeed * 0.5;
        }
      }
      // A village builds in its own style unless it has no choice.
      if (variant != null && info.hasWellFormedId() && variant.equals(info.getVariant())) {
        score += 1.5;
      }
      // Never stack a third of the same thing while anything else is wanted.
      score -= countBuilt(village, info.getName()) * 2.0;

      candidates.add(new Candidate(info, score, describe(info)));
    }

    candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
    return candidates;
  }

  /** A plain-language option line: what it is, and what it would give the village. */
  private static String describe(BuildingInfo info) {
    String name = info.hasWellFormedId() ? info.getCategory().replace('_', ' ') : info.getName();
    List<String> gives = new ArrayList<>();
    int bedCount = info.getBedLocations().size();
    if (bedCount > 0) {
      gives.add(bedCount + (bedCount == 1 ? " bed" : " beds"));
    }
    info.getWorkLocations().values().stream().distinct().forEach(occupation ->
        gives.add("work for a " + occupation.name().toLowerCase()));
    int containers = info.getContainerLocations().size();
    if (containers > 0) {
      gives.add(containers == 1 ? "a store" : containers + " stores");
    }
    return gives.isEmpty() ? "a " + name : "a " + name + " (" + String.join(", ", gives) + ")";
  }

  private static String villageVariant(Village village) {
    Building center = village.getTownCenter();
    if (center == null || center.getInfo() == null || !center.getInfo().hasWellFormedId()) {
      return null;
    }
    return center.getInfo().getVariant();
  }

  private static int countBuilt(Village village, String name) {
    int count = 0;
    for (Building building : village.getBuildings()) {
      if (name.equals(building.getName())) {
        count++;
      }
    }
    return count;
  }

  private static boolean hasMaterialsToConstruct(Village village, BuildingInfo build){
    for(ItemStack itemCost : build.getMaterialCost()){
      if(!village.hasItemStackInVillage(itemCost)){
        return false;
      }
    }
    return true;
  }

  public static boolean payForBuilding(Village village, BuildingInfo build){
    boolean paidFullCost = true;
    for(ItemStack itemCost : build.getMaterialCost()){

      ItemStack gatheredStack = village.gatherItemStackFromVillage(itemCost);
      if(gatheredStack.getCount() < itemCost.getCount()){
        paidFullCost = false;
        village.logEvent(new NoResourceBookkeepingEvent(itemCost.getItem(),
            itemCost.getCount() - gatheredStack.getCount()));
      }

    }
    return paidFullCost;
  }

  /**
   * The first cost item the village cannot cover for any building it might
   * otherwise want, or null when everything in the catalogue is affordable.
   * Used to log a shortage when planning stalls.
   */
  public static ItemStack firstUnaffordableMaterial(Village village){
    for(BuildingInfo build : Buildings.allBuildings().values()){
      if (build.getName().equals(Buildings.VILLAGE_CENTER_NAME) || build.getUpgradesFrom() != null) {
        continue;
      }
      for(ItemStack itemCost : build.getMaterialCost()){
        if(!village.hasItemStackInVillage(itemCost)){
          return itemCost;
        }
      }
    }
    return null;
  }

}
