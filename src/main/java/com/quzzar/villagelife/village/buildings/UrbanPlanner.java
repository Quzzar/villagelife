package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.llm.LlmDecision;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.village.Occupation;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.VillageAttractiveness;
import com.quzzar.villagelife.village.bookkeeping.NoResourceBookkeepingEvent;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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

  /** How many out-of-reach buildings are offered as things to save toward. */
  private static final int GOALS_OFFERED = 2;

  /**
   * A village center is placed once, at founding, and never chosen again. The
   * check is by CATEGORY, not by id: there is a center variant per biome, and
   * matching only the default id let a village decide to build itself a second
   * town hall in a different architectural style.
   */
  private static boolean isFoundingOnly(BuildingInfo info) {
    return info.hasWellFormedId()
        ? "village_center".equals(info.getCategory())
        : info.getName().equals(Buildings.VILLAGE_CENTER_NAME);
  }

  /** A candidate building, with why the village might want it. */
  public record Candidate(BuildingInfo info, double score, String description) {}

  /** What a village is short of, sampled once and applied to every candidate. */
  private record Needs(double housing, double work, double food, double safety) {
    static Needs of(Village village) {
      int population = village.getPopulation().size();
      int beds = village.getTotalBeds();
      int idle = village.idlePeople().size();
      int openJobs = village.getUnassignedJobs().size();
      VillageAttractiveness report = village.getAttractiveness();
      return new Needs(
          Math.max(0, population + 2 - beds),
          Math.max(0, idle - openJobs),
          report != null && report.foodPerCapita() < 8.0 ? 8.0 - report.foodPerCapita() : 0.0,
          report != null ? Math.min(3.0, report.deathImpact() * 2.0) : 0.0);
    }
  }

  /** How badly this village wants this building, given what it lacks. */
  private static double scoreOf(Village village, BuildingInfo info, Needs needs) {
    double score = 0.0;
    score += info.getBedLocations().size() * (1.0 + needs.housing());
    score += info.getWorkLocations().size() * (1.0 + needs.work());
    score += info.getContainerLocations().size() * 0.25;
    for (Occupation occupation : info.getWorkLocations().values()) {
      if (occupation == Occupation.GUARD) {
        score += needs.safety();
      }
      if (occupation == Occupation.FARMER || occupation == Occupation.LUMBERJACK) {
        score += needs.food() * 0.5;
      }
    }
    // Never stack a third of the same thing while anything else is wanted.
    score -= countBuilt(village, info.getName()) * 2.0;
    return score;
  }

  /**
   * Chooses the next project asynchronously: affordable candidates are ranked
   * by need, then offered to the model. Completes with null when the village
   * can build nothing right now.
   */
  public static CompletableFuture<BuildingInfo> chooseNextProject(Village village) {
    // A village that is saving does not deliberate: it spends on the thing it
    // named, the moment it can, and otherwise waits. That is what saving is.
    // One sweep of village storage answers every affordability question below.
    Map<Item, Integer> stock = village.stockTally();

    String goal = VillageGoal.current(village);
    if (goal != null) {
      if (VillageGoal.hasExpired(village, village.getVillageTime())) {
        VillageGoal.clear(village, "waited too long");
      } else {
        BuildingInfo wanted = Buildings.getByName(goal);
        if (wanted == null) {
          VillageGoal.clear(village, "the definition is gone");
        } else if (hasMaterialsToConstruct(stock, wanted)) {
          VillageGoal.clear(village, "affordable at last");
          Villagelife.LOGGER.info("Village '{}' saved up and is building {}",
              village.getName(), wanted.getName());
          return CompletableFuture.completedFuture(wanted);
        } else {
          return CompletableFuture.completedFuture(null);
        }
      }
    }

    List<Candidate> candidates = rankCandidates(village, stock);
    List<Candidate> offered = candidates.subList(0, Math.min(optionsOffered(), candidates.size()));

    // Things worth wanting but out of reach today, offered as goals rather
    // than as builds. A village with nothing affordable is exactly the village
    // that most needs to name something and save for it, so these are gathered
    // even when there is nothing to build.
    List<Candidate> goals = outOfReach(village, stock);
    if (offered.isEmpty() && goals.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }

    Candidate ruleChoice = offered.isEmpty() ? null : offered.get(0);
    if (!LlmService.get().isReady()) {
      return CompletableFuture.completedFuture(ruleChoice == null ? null : ruleChoice.info());
    }

    // Waiting is a real move: a brain that can only build will build until it
    // physically cannot, and a village that is saving up should be able to say so.
    List<String> options = new ArrayList<>(offered.stream().map(Candidate::description).toList());
    options.add(WAIT_OPTION);
    for (Candidate goalCandidate : goals) {
      options.add("save up for " + goalCandidate.description());
    }
    // What the brain was actually asked. Without it a village that never saves
    // for anything is indistinguishable from one that was never offered the
    // chance, and the same ambiguity hid a broken site search for a day.
    Villagelife.LOGGER.debug("Village '{}' is choosing among {} affordable, {} to save for, plus waiting",
        village.getName(), offered.size(), goals.size());
    return LlmService.get().decide(situationOf(village), options)
        .thenApply(decision -> pick(village, offered, goals, ruleChoice, decision));
  }

  /**
   * Materials a village cannot simply dig up: each needs some building standing
   * before any of it exists. Anything absent from this map comes straight out of
   * the ground, and a village that has founded can always get it.
   */
  private static final Map<Item, String> MATERIAL_SOURCE = Map.of(
      Items.OAK_LOG, "LOGS",
      Items.OAK_PLANKS, "PLANKS",
      Items.STONE, "CUT_STONE",
      Items.STONE_BRICKS, "CUT_STONE",
      Items.SANDSTONE, "CUT_STONE",
      Items.CUT_SANDSTONE, "CUT_STONE");

  /**
   * Whether a village could ever pay for this, which is a different question
   * from whether it can today. A goal it has no way to work toward is not a
   * goal, it is a wait until the timeout: a plains village that names a snowy
   * farm saves for stone brick it cannot make, expires, and names another.
   */
  private static boolean withinReach(Village village, Map<Item, Integer> stock, BuildingInfo info) {
    for (ItemStack cost : info.getMaterialCost()) {
      String capability = MATERIAL_SOURCE.get(cost.getItem());
      if (capability == null || village.canDo(capability)) {
        continue;
      }
      // Some already in store counts: whoever put it there can get more, and a
      // village part-way to a goal should be allowed to finish it.
      if (stock.getOrDefault(cost.getItem(), 0) > 0) {
        continue;
      }
      return false;
    }
    return true;
  }

  /**
   * The best few buildings the village wants but cannot pay for. Ranked by
   * whether it can actually work toward them first, then by the same need
   * scoring, so what it saves for is something it both lacks and can reach.
   */
  private static List<Candidate> outOfReach(Village village, Map<Item, Integer> stock) {
    Needs needs = Needs.of(village);
    List<Candidate> unaffordable = new ArrayList<>();
    for (BuildingInfo info : Buildings.allBuildings().values()) {
      if (isFoundingOnly(info) || hasMaterialsToConstruct(stock, info)) {
        continue;
      }
      // An upgrade it cannot pay for yet is exactly the kind of thing worth
      // saving toward, so it belongs here on the same terms as a new building.
      Building standing = BuildingUpgrade.standingSource(village, info);
      if (standing != null) {
        unaffordable.add(new Candidate(info, upgradeScore(village, info, standing, needs),
            BuildingUpgrade.describe(standing, info)));
        continue;
      }
      if (info.getUpgradesFrom() != null || (info.hasWellFormedId() && info.getLevel() > 1)) {
        continue;
      }
      unaffordable.add(new Candidate(info, scoreOf(village, info, needs), describe(info)));
    }
    // Reach first, then need. Ranking by need alone had plains villages saving
    // for desert and snowy farms, whose sandstone and stone brick they had no
    // mason to make, cycling through unreachable goals a timeout at a time.
    // Decided once per candidate rather than inside the comparator: a sort
    // calls its key function O(n log n) times, and this one used to read the
    // whole of village storage on every call (#65).
    Map<BuildingInfo, Boolean> reachable = new HashMap<>();
    for (Candidate c : unaffordable) {
      reachable.computeIfAbsent(c.info(), info -> withinReach(village, stock, info));
    }
    Comparator<Candidate> byReach = Comparator.comparing((Candidate c) -> reachable.get(c.info()));
    unaffordable.sort(byReach.reversed()
        .thenComparing(Comparator.comparingDouble(Candidate::score).reversed()));
    return unaffordable.subList(0, Math.min(GOALS_OFFERED, unaffordable.size()));
  }

  private static BuildingInfo pick(Village village, List<Candidate> offered, List<Candidate> goals,
      Candidate ruleChoice, Optional<LlmDecision> decision) {
    if (decision.isEmpty()) {
      if (ruleChoice == null) {
        return null;
      }
      Villagelife.LOGGER.debug("Village '{}' had no answer from the brain; building {} on the rules' advice",
          village.getName(), ruleChoice.info().getName());
      return ruleChoice.info();
    }
    LlmDecision chosen = decision.get();
    int index = chosen.choiceIndex();
    if (index > offered.size()) {
      int goalIndex = index - offered.size() - 1;
      if (goalIndex >= 0 && goalIndex < goals.size()) {
        VillageGoal.set(village, goals.get(goalIndex).info().getName(),
            chosen.reason(), village.getVillageTime());
        return null;
      }
      return ruleChoice == null ? null : ruleChoice.info();
    }
    if (index == offered.size()) {
      Villagelife.LOGGER.info("Village '{}' decided to build nothing for now: {}",
          village.getName(), chosen.reason());
      return null;
    }
    if (index < 0 || index >= offered.size()) {
      return ruleChoice == null ? null : ruleChoice.info();
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
   * Higher levels are in the list only as upgrades of something already
   * standing: a level above 1 is never built fresh (docs/building-spec.md), so
   * one is offered when the village owns the level below, can pay the new
   * level's own cost, and the larger footprint has somewhere to grow into.
   */
  public static List<Candidate> rankCandidates(Village village) {
    return rankCandidates(village, village.stockTally());
  }

  private static List<Candidate> rankCandidates(Village village, Map<Item, Integer> stock) {
    Needs needs = Needs.of(village);

    List<Candidate> candidates = new ArrayList<>();
    for (BuildingInfo info : Buildings.allBuildings().values()) {
      if (isFoundingOnly(info)) {
        continue;
      }
      if (!hasMaterialsToConstruct(stock, info)) {
        continue;
      }
      Building standing = BuildingUpgrade.standingSource(village, info);
      if (standing != null) {
        candidates.add(new Candidate(info, upgradeScore(village, info, standing, needs),
            BuildingUpgrade.describe(standing, info)));
        continue;
      }
      if (info.getUpgradesFrom() != null || (info.hasWellFormedId() && info.getLevel() > 1)) {
        continue;
      }
      candidates.add(new Candidate(info, scoreOf(village, info, needs), describe(info)));
    }

    candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
    return candidates;
  }

  /**
   * How badly the village wants the improvement, which is the value of what the
   * upgrade ADDS rather than of the whole building. Scoring an upgrade as if it
   * were a new building would make a bigger farm look worth as much as a farm
   * to a village that already has one.
   */
  private static double upgradeScore(Village village, BuildingInfo info, Building standing, Needs needs) {
    BuildingInfo current = standing.getInfo();
    if (current == null) {
      return scoreOf(village, info, needs);
    }
    double score = 0.0;
    score += (info.getBedLocations().size() - current.getBedLocations().size()) * (1.0 + needs.housing());
    score += (info.getWorkLocations().size() - current.getWorkLocations().size()) * (1.0 + needs.work());
    score += (info.getContainerLocations().size() - current.getContainerLocations().size()) * 0.25;
    for (Occupation occupation : info.getWorkLocations().values().stream().distinct().toList()) {
      int added = stationsFor(info, occupation) - stationsFor(current, occupation);
      if (added <= 0) {
        continue;
      }
      if (occupation == Occupation.GUARD) {
        score += needs.safety() * added;
      }
      if (occupation == Occupation.FARMER || occupation == Occupation.LUMBERJACK) {
        score += needs.food() * 0.5 * added;
      }
    }
    // Improving something is a smaller act than founding something new, and it
    // costs the village the use of the building while it happens, so a tie goes
    // to the new thing.
    return score - 0.5;
  }

  private static int stationsFor(BuildingInfo info, Occupation occupation) {
    int count = 0;
    for (Occupation station : info.getWorkLocations().values()) {
      if (station == occupation) {
        count++;
      }
    }
    return count;
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

  private static int countBuilt(Village village, String name) {
    int count = 0;
    for (Building building : village.getBuildings()) {
      if (name.equals(building.getName())) {
        count++;
      }
    }
    return count;
  }

  /**
   * Whether the village can pay for this, answered from a tally taken once for
   * the whole decision rather than by re-reading storage per material (#65).
   * Each cost is checked independently, as it always was: a definition listing
   * the same item twice is a datapack quirk, not something to change here
   * under cover of a performance fix.
   */
  private static boolean hasMaterialsToConstruct(Map<Item, Integer> stock, BuildingInfo build){
    for(ItemStack itemCost : build.getMaterialCost()){
      if(stock.getOrDefault(itemCost.getItem(), 0) < itemCost.getCount()){
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
    Map<Item, Integer> stock = village.stockTally();
    for(BuildingInfo build : Buildings.allBuildings().values()){
      if (isFoundingOnly(build) || build.getUpgradesFrom() != null) {
        continue;
      }
      for(ItemStack itemCost : build.getMaterialCost()){
        if(stock.getOrDefault(itemCost.getItem(), 0) < itemCost.getCount()){
          return itemCost;
        }
      }
    }
    return null;
  }

}
