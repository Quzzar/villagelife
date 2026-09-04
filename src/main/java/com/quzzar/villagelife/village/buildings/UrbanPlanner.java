package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.llm.LlmDecision;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.village.Occupation;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.VillageRequests;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Decides what a village builds next.
 *
 * The rules establish facts, the model decides. Nothing here scores a building
 * or ranks one need above another: that judgement is the model's. What the rules
 * do is answer the factual questions a decision needs, and only those. Which
 * buildings are legal and affordable right now. Which are worth working toward
 * because there is a real path to them. And, for anything the village cannot yet
 * afford, what it is short of and which building would produce it, so the model
 * can see the chain for itself: a farm needs oak logs, oak logs come from a
 * lumberjack, so the lumberjack is the way to the farm. All of that is data,
 * derived from each building's cost and grants. The model is handed the whole
 * field of legal options with those facts attached and chooses among them; a
 * failed, slow, or absent model costs nothing but the flavour of the choice, and
 * the first thing the village could build stands in.
 */
public class UrbanPlanner {

  /** Chosen when the village would rather wait than spend what it has. */
  private static final String WAIT_OPTION = "nothing yet: keep what we have and wait";

  /**
   * A village center is placed once, at founding, and never built fresh again.
   * The check is by CATEGORY, not by id: there is a center variant per biome, and
   * matching only the default id let a village decide to build itself a second
   * town hall in a different architectural style. An UPGRADE of the center is
   * not a second center: it replaces the one standing, on the same terms as any
   * other upgrade, so a level above 1 is left in.
   */
  private static boolean isFoundingOnly(BuildingInfo info) {
    if (info.getUpgradesFrom() != null) {
      return false;
    }
    return info.hasWellFormedId()
        ? "village_center".equals(info.getCategory())
        : info.getName().startsWith(Buildings.VILLAGE_CENTER_CATEGORY);
  }

  /**
   * A couple's cottage is never deliberated over: it is raised only as the
   * saved-for goal a marriage sets (docs/marriage.md), so it is filtered out of
   * both the affordable options and the reachable goals the brain is shown. A
   * village should not decide to build one for no one; it builds one because two
   * of its people wed. The goal short-circuit in {@link #chooseNextProject}
   * still builds it once named, which is the only way it is ever built.
   */
  private static boolean isMarriageOnly(BuildingInfo info) {
    return info.hasWellFormedId() && Buildings.COUPLE_COTTAGE_CATEGORY.equals(info.getCategory());
  }

  /**
   * A building that would add the village nothing it lacks, and so is not offered
   * (Ember Hill built six wells, 2026-09-02). A second farm, house, mine or
   * storehouse always earns its place, another field, more beds, another seam, more
   * shelves, so anything that brings a bed, a job, or a store is never redundant.
   * What is left is a building whose whole worth is the capabilities it grants, and
   * a well is the case in point: it grants WATER, and WATER is a yes-or-no the
   * village either has or has not. Once one well stands, a second grants nothing,
   * so a building whose every grant is already provided and which adds no bed, job,
   * or store is kept off the table. The first well, when the village has no water,
   * grants WATER and is offered as normal; only the redundant ones are dropped.
   */
  private static boolean isRedundant(Village village, BuildingInfo info) {
    if (!info.getBedLocations().isEmpty()
        || !info.getWorkLocations().isEmpty()
        || !info.getContainerLocations().isEmpty()
        || !info.getConditionalGrants().isEmpty()) {
      return false;
    }
    List<String> grants = info.getGrants();
    if (grants.isEmpty()) {
      return false;
    }
    for (String grant : grants) {
      if (!village.canDo(grant)) {
        return false;
      }
    }
    return true;
  }

  /** A candidate building and the plain-language facts that describe it. */
  public record Candidate(BuildingInfo info, String description) {}

  /** The same vetted option field the brain receives, exposed read-only to chat. */
  public record PlanningOptions(List<Candidate> buildable, List<Candidate> saveable) {
    public PlanningOptions {
      buildable = List.copyOf(buildable);
      saveable = List.copyOf(saveable);
    }
  }

  /**
   * What a village is short of to build the given thing right now, in the
   * model's own terms, or empty when it can already afford it. Public so the
   * marriage housing goal can be named with a real shortfall, which is what lets
   * the goal machinery tell a home it is slowly saving toward from one its
   * economy can never reach (docs/marriage.md).
   */
  public static String shortfallFor(Village village, BuildingInfo info) {
    return shortfall(village, info, village.stockTally());
  }

  /**
   * Chooses the next project asynchronously: every affordable build and every
   * reachable save-for goal, each with its facts attached, is offered to the
   * model, which decides. Completes with null when the village can build nothing
   * right now, or when the model chooses to wait or to save for a goal.
   */
  public static CompletableFuture<BuildingInfo> chooseNextProject(Village village) {
    // A village that is saving does not deliberate: it spends on the thing it
    // named, the moment it can, and otherwise waits. That is what saving is.
    // One sweep of village storage answers every affordability question below.
    Map<Item, Integer> stock = village.stockTally();

    String goal = VillageGoal.current(village);
    if (goal != null) {
      if (VillageGoal.hasExpired(village, village.getVillageTime())) {
        // A goal that expires with its shortfall exactly as it was when named
        // proved something the reachability test could not: nobody here can get
        // what it needs. Sit it out, or the village re-names it forever.
        BuildingInfo expired = Buildings.getByName(goal);
        String before = VillageGoal.shortfallAtSet(village);
        String now = expired == null ? "" : shortfall(village, expired, stock);
        VillageGoal.clear(village, "waited too long");
        if (expired != null && !before.isEmpty() && before.equals(now)) {
          VillageGoal.markStalled(village, goal, village.getVillageTime());
        }
      } else {
        BuildingInfo wanted = Buildings.getByName(goal);
        if (wanted == null) {
          VillageGoal.clear(village, "the definition is gone");
        } else if (hasMaterialsToConstruct(village, stock, wanted)) {
          VillageGoal.clear(village, "affordable at last");
          Villagelife.LOGGER.info("Village '{}' saved up and is building {}",
              village.getName(), wanted.getName());
          return CompletableFuture.completedFuture(wanted);
        } else {
          return CompletableFuture.completedFuture(null);
        }
      }
    }

    // Everything the village could start this minute, and everything it could
    // work toward. Neither list is ranked: the model is shown the whole field
    // and decides for itself. Each option carries what it would give the village
    // (see describe), and a goal carries what it still needs and what would
    // produce it (see shortfall), so the model can weigh the chain.
    PlanningOptions planningOptions = optionsFor(village, stock);
    List<Candidate> buildable = planningOptions.buildable();
    List<Candidate> goals = planningOptions.saveable();

    if (buildable.isEmpty() && goals.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }

    // The rules' only stand-in for the model: the first thing the village could
    // build. Used when there is no brain to ask, never to overrule one.
    Candidate fallback = buildable.isEmpty() ? null : buildable.get(0);
    if (!LlmService.get().isReady()) {
      return CompletableFuture.completedFuture(fallback == null ? null : fallback.info());
    }

    // Waiting is a real move: a brain that can only build will build until it
    // physically cannot, and a village that is saving up should be able to say so.
    List<String> options = new ArrayList<>(buildable.stream().map(Candidate::description).toList());
    options.add(WAIT_OPTION);
    for (Candidate goalCandidate : goals) {
      options.add("save up for " + goalCandidate.description()
          + shortfall(village, goalCandidate.info(), stock));
    }
    // What the brain was actually asked. Without it a village that never saves
    // for anything is indistinguishable from one that was never offered the
    // chance, and the same ambiguity hid a broken site search for a day.
    String situation = situationOf(village, stock);
    Villagelife.LOGGER.debug("Village '{}' is choosing among {} it can build now, {} to work toward, plus waiting. Situation: {}",
        village.getName(), buildable.size(), goals.size(), situation);
    return LlmService.get().decide("what " + village.getName() + " builds next", situation, options)
        .thenApply(decision -> pick(village, buildable, goals, fallback, decision, stock));
  }

  /**
   * Materials a village cannot simply dig up: each needs some building standing
   * before any of it exists. Anything absent from this map comes straight out of
   * the ground, and a village that has founded can always get it. This is also
   * the source of the dependency chain the model is shown: an item's capability
   * names the building that produces it (see producerFor). Ask through
   * {@link #sourceOf}: any log waits on LOGS and any wool on WOOL, since any log
   * pays a log cost and any wool a wool cost ({@link Materials}), and oak and
   * white stand here for all of theirs. Iron waits on SMELTING: until 2026-09-02
   * it was absent, read as dug-up, and a village with no forge was offered a
   * forge that cost the very ingots only a forge can make.
   */
  private static final Map<Item, String> MATERIAL_SOURCE = Map.of(
      Items.OAK_LOG, "LOGS",
      Items.OAK_PLANKS, "PLANKS",
      Items.STONE, "CUT_STONE",
      Items.STONE_BRICKS, "CUT_STONE",
      Items.SANDSTONE, "CUT_STONE",
      Items.CUT_SANDSTONE, "CUT_STONE",
      Items.WHITE_WOOL, "WOOL",
      Items.IRON_INGOT, "SMELTING");

  /** The capability a material waits on, or null when it comes out of the ground. */
  @Nullable
  private static String sourceOf(Item item) {
    if (Materials.isLog(item) || Materials.isPlank(item)) {
      return "LOGS";
    }
    if (Materials.isWool(item)) {
      return "WOOL";
    }
    return MATERIAL_SOURCE.get(item);
  }

  /**
   * Whether a village could ever pay for this, which is a different question
   * from whether it can today. A goal it has no way to work toward is not a
   * goal, it is a wait until the timeout: a plains village that names a snowy
   * farm saves for stone brick it cannot make, expires, and names another.
   */
  private static boolean withinReach(Village village, Map<Item, Integer> stock, BuildingInfo info) {
    for (ItemStack cost : ConstructionQuote.requiredFor(village, info)) {
      String capability = sourceOf(cost.getItem());
      if (capability == null || village.canDo(capability)) {
        continue;
      }
      // Some already in store counts: whoever put it there can get more, and a
      // village part-way to a goal should be allowed to finish it.
      if (Materials.countedToward(stock, cost.getItem()) > 0) {
        continue;
      }
      return false;
    }
    return true;
  }

  /**
   * The buildings the village wants but cannot pay for yet, and could genuinely
   * work toward. No ranking: the whole reachable set is returned in catalogue
   * order and the model chooses. A building with no path to its materials is left
   * out entirely, because a goal you can never reach is not a goal.
   */
  private static List<Candidate> outOfReach(Village village, Map<Item, Integer> stock) {
    List<Candidate> unaffordable = new ArrayList<>();
    String stalled = VillageGoal.stalled(village, village.getVillageTime());
    for (BuildingInfo info : Buildings.catalogue(village.getStyle())) {
      if (isFoundingOnly(info) || isMarriageOnly(info) || isRedundant(village, info)
          || hasMaterialsToConstruct(village, stock, info)) {
        continue;
      }
      // Sitting out after a goal lifetime in which nothing came in for it.
      if (info.getName().equals(stalled)) {
        continue;
      }
      // A goal the village has nowhere to put, or no path to pay for, is not a
      // goal, it is a wait until the timeout.
      if (hasNoRoomFor(village, info) || !withinReach(village, stock, info)) {
        continue;
      }
      // An upgrade it cannot pay for yet is exactly the kind of thing worth
      // saving toward, so it belongs here on the same terms as a new building.
      Building standing = BuildingUpgrade.standingSource(village, info);
      if (standing != null) {
        unaffordable.add(new Candidate(info, BuildingUpgrade.describe(standing, info)));
        continue;
      }
      if (info.getUpgradesFrom() != null || (info.hasWellFormedId() && info.getLevel() > 1)) {
        continue;
      }
      unaffordable.add(new Candidate(info, describe(info)));
    }
    return unaffordable;
  }

  /** Captures every legal start-now and save-for choice for this village. */
  public static PlanningOptions optionsFor(Village village) {
    return optionsFor(village, village.stockTally());
  }

  private static PlanningOptions optionsFor(Village village, Map<Item, Integer> stock) {
    return new PlanningOptions(affordableBuilds(village, stock), outOfReach(village, stock));
  }

  private static BuildingInfo pick(Village village, List<Candidate> buildable, List<Candidate> goals,
      Candidate fallback, Optional<LlmDecision> decision, Map<Item, Integer> stock) {
    if (decision.isEmpty()) {
      if (fallback == null) {
        return null;
      }
      Villagelife.LOGGER.debug("Village '{}' had no answer from the brain; building {} on the rules' advice",
          village.getName(), fallback.info().getName());
      return fallback.info();
    }
    LlmDecision chosen = decision.get();
    int index = chosen.choiceIndex();
    if (index > buildable.size()) {
      int goalIndex = index - buildable.size() - 1;
      if (goalIndex >= 0 && goalIndex < goals.size()) {
        BuildingInfo goalInfo = goals.get(goalIndex).info();
        Villagelife.LOGGER.info("Village '{}' decided to save up for {}: {}",
            village.getName(), goalInfo.getName(), chosen.reason());
        VillageGoal.set(village, goalInfo.getName(), chosen.reason(), shortfall(village, goalInfo, stock),
            village.getVillageTime());
        return null;
      }
      return fallback == null ? null : fallback.info();
    }
    if (index == buildable.size()) {
      Villagelife.LOGGER.info("Village '{}' decided to build nothing for now: {}",
          village.getName(), chosen.reason());
      return null;
    }
    if (index < 0 || index >= buildable.size()) {
      return fallback == null ? null : fallback.info();
    }
    Candidate candidate = buildable.get(index);
    Villagelife.LOGGER.info("Village '{}' decided to build {}: {}",
        village.getName(), candidate.info().getName(), chosen.reason());
    return candidate.info();
  }

  /** What the village is short of right now, in the model's own terms, as facts. */
  private static String situationOf(Village village, Map<Item, Integer> stock) {
    StringBuilder situation = new StringBuilder(
        VillageContextSnapshot.capture(village, stock).plannerBriefing());
    if (!producesFood(village)) {
      situation.append("No building grows or gathers food yet. ");
    }
    appendWoodShortage(village, situation);
    appendRequests(village, situation);
    // A stalled goal is stated as a fact, so the brain knows why a building it
    // wanted is missing from its options rather than quietly choosing around a gap.
    String stalled = VillageGoal.stalled(village, village.getVillageTime());
    if (stalled != null) {
      BuildingInfo info = Buildings.getByName(stalled);
      String label = info != null ? info.displayLabel() : stalled;
      situation.append("You saved up for a ").append(label)
          .append(" before and nothing came in for it the whole time, so it is off the table for now. ");
    }
    // What the village has learned about its own room, stated for the same
    // reason the stalled goal is: buildings that found no ground are missing
    // from the options below, and the brain should know that is why rather than
    // choose around a gap it cannot see.
    String room = village.describeRoom();
    if (room != null) {
      situation.append(room).append(' ');
    }
    situation.append("Choose what to build next.");
    return situation.toString();
  }

  /** Whether any standing building already produces food. */
  private static boolean producesFood(Village village) {
    return village.getBuildings().stream().anyMatch(building -> building.getInfo() != null
        && (building.getInfo().getGrants().contains("GRAIN")
            || building.getInfo().getGrants().contains("MEAT")));
  }

  /**
   * The keystone a small model most often reasons past: a village with no way to
   * make logs of its own, in a catalogue where nearly everything it can build
   * needs logs, is stuck until it raises a lumberjack, which is paid for in stone
   * alone. The per-option shortfalls already note "a lumberjack would make logs";
   * this states the whole problem and its one answer as a fact in the situation,
   * so the decision does not rest on the model assembling it from a dozen separate
   * hints. Silent once a wood source stands, or where no lumberjack is defined.
   */
  private static void appendWoodShortage(Village village, StringBuilder situation) {
    if (village.canDo("LOGS") || Buildings.resolve("lumberjack", 1, village.getStyle()) == null) {
      return;
    }
    situation.append("You have no way to make logs of your own, and nearly everything you could "
        + "build needs logs. A lumberjack turns stone into logs, so raising one is the way out of "
        + "this shortage. ");
  }

  /**
   * The villagers' standing requests, folded into the brain's briefing as an
   * argument, not an instruction: it weighs them and still decides. Each request
   * is listed raw, exactly as its villager put it - many people asking the same
   * thing simply reads as many lines, so the volume itself is the emphasis.
   */
  private static void appendRequests(Village village, StringBuilder situation) {
    List<VillageRequests.Request> asks = VillageRequests.current(village);
    if (asks.isEmpty()) {
      return;
    }
    List<String> lines = new ArrayList<>();
    for (VillageRequests.Request request : asks) {
      String reason = request.reason().isBlank() ? "" : " (" + request.reason() + ")";
      lines.add(request.requesterName() + " asks for " + request.subject() + reason);
    }
    situation.append("Your people have asked: ").append(String.join("; ", lines))
        .append(". Weigh them, but decide for the village. ");
  }

  /**
   * Every building the village could legally and affordably start, in catalogue
   * order and unranked. Higher levels are here only as upgrades of something
   * already standing: a level above 1 is never built fresh (docs/building-spec.md),
   * so one is offered when the village owns the level below, can pay the new
   * level's own cost, and the larger footprint has somewhere to grow into.
   */
  private static List<Candidate> affordableBuilds(Village village, Map<Item, Integer> stock) {
    List<Candidate> candidates = new ArrayList<>();
    for (BuildingInfo info : Buildings.catalogue(village.getStyle())) {
      if (isFoundingOnly(info) || isMarriageOnly(info) || isRedundant(village, info)) {
        continue;
      }
      if (!hasMaterialsToConstruct(village, stock, info)) {
        continue;
      }
      if (hasNoRoomFor(village, info)) {
        continue;
      }
      Building standing = BuildingUpgrade.standingSource(village, info);
      if (standing != null) {
        candidates.add(new Candidate(info, BuildingUpgrade.describe(standing, info)));
        continue;
      }
      if (info.getUpgradesFrom() != null || (info.hasWellFormedId() && info.getLevel() > 1)) {
        continue;
      }
      candidates.add(new Candidate(info, describe(info)));
    }
    return candidates;
  }

  /**
   * The datapack effects a building grants, in plain words: what it lets the
   * village do or make, beyond the beds, jobs, and stores describe already reads
   * off its structure. STORAGE is left out, as the container count already says it.
   */
  private static final Map<String, String> GRANT_EFFECT = Map.ofEntries(
      Map.entry("WATER", "fresh water"),
      Map.entry("ORES", "digs ore"),
      Map.entry("FUEL", "makes fuel"),
      Map.entry("PROTECTION", "defends the village"),
      Map.entry("GRAIN", "grows grain"),
      Map.entry("TRADE", "a place to trade"),
      Map.entry("TRADE_INITIATIVE", "drums up trade"),
      Map.entry("REPAIR", "mends gear"),
      Map.entry("SMELTING", "smelts metal"),
      Map.entry("CUT_STONE", "cuts stone"),
      Map.entry("LOGS", "fells logs"),
      Map.entry("PLANKS", "makes planks"),
      Map.entry("MEAT", "provides meat"),
      Map.entry("BREAD", "bakes bread"),
      Map.entry("LEATHER", "tans leather"),
      Map.entry("WOOL", "gives wool"),
      Map.entry("HEALING", "heals the sick"),
      Map.entry("WANDERERS", "draws newcomers"));

  /** A plain-language option line: what it is, and what it would give the village. */
  private static String describe(BuildingInfo info) {
    String name = info.displayLabel();
    List<String> gives = new ArrayList<>();
    int bedCount = info.getBedLocations().size();
    if (bedCount > 0) {
      gives.add(bedCount + (bedCount == 1 ? " bed" : " beds"));
    }
    info.getWorkLocations().values().stream().distinct().forEach(occupation ->
        gives.add("work for a " + occupation.name().toLowerCase()));
    if (bedCount == 0 && !info.getWorkLocations().isEmpty()) {
      gives.add("no beds");
    }
    int containers = info.getContainerLocations().size();
    if (containers > 0) {
      gives.add(containers == 1 ? "a store" : containers + " stores");
    }
    // What it lets the village do or make, on top of its beds/jobs/stores.
    for (String grant : info.getGrants()) {
      String effect = GRANT_EFFECT.get(grant);
      if (effect != null) {
        gives.add(effect);
      }
    }
    String base = gives.isEmpty() ? "a " + name : "a " + name + " (" + String.join(", ", gives) + ")";
    return base + unlockNote(info);
  }

  /**
   * A fact appended to a producer's line: that it makes a material other
   * buildings are built from, so the model can see why raising a lumberjack (for
   * oak logs) or a mason (for stone) comes before the buildings that need them.
   * Derived from the cost data, never hand-authored per building. Empty for a
   * building whose grants produce no building material.
   */
  private static String unlockNote(BuildingInfo info) {
    for (String grant : info.getGrants()) {
      for (Map.Entry<Item, String> source : MATERIAL_SOURCE.entrySet()) {
        if (source.getValue().equals(grant)) {
          return ", which provides the " + Materials.describe(source.getKey())
              + " that other buildings are built from";
        }
      }
    }
    return "";
  }

  /**
   * A one-line catalogue of the projects this village can start or viably save
   * toward, using the exact vetted option field offered to its brain.
   */
  public static String buildableCatalogue(Village village) {
    Map<Item, Integer> stock = village.stockTally();
    PlanningOptions options = optionsFor(village, stock);
    List<String> parts = new ArrayList<>();
    if (!options.buildable().isEmpty()) {
      parts.add("can start now: " + options.buildable().stream()
          .map(Candidate::description).collect(java.util.stream.Collectors.joining(", ")));
    }
    if (!options.saveable().isEmpty()) {
      parts.add("could save toward: " + options.saveable().stream()
          .map(candidate -> candidate.description() + shortfall(village, candidate.info(), stock))
          .collect(java.util.stream.Collectors.joining(", ")));
    }
    return parts.isEmpty() ? "no legal project currently has a viable site and material path"
        : String.join("; ", parts);
  }

  /**
   * What a save-for goal still lacks against current stock, as ", still needs 40
   * cobblestone", or empty when nothing is short. Where a missing material can
   * only be produced by a building the village does not have, the building is
   * named, so the goal reads as a chain the model can follow: the farm still
   * needs oak log, which a lumberjack would make.
   */
  private static String shortfall(Village village, BuildingInfo info, Map<Item, Integer> stock) {
    List<String> parts = new ArrayList<>();
    String chain = "";
    for (ItemStack missing : ConstructionQuote.capture(village, info, stock).missing()) {
      parts.add(missing.getCount() + " " + Materials.describe(missing.getItem()));
      if (chain.isEmpty()) {
        chain = producerHint(village, missing.getItem());
      }
    }
    return parts.isEmpty() ? "" : ", still needs " + String.join(", ", parts) + chain;
  }

  /**
   * If a material can only come from a building the village lacks, the clause
   * that names it, otherwise empty. This is the backward step of the chain: the
   * material the goal is short of, resolved to the building that produces it.
   */
  private static String producerHint(Village village, Item item) {
    String capability = sourceOf(item);
    if (capability == null || village.canDo(capability)) {
      return "";
    }
    BuildingInfo producer = producerFor(capability);
    if (producer == null) {
      return "";
    }
    String name = producer.displayLabel();
    return " (a " + name + " would make " + Materials.describe(item) + ")";
  }

  /** The first building in the catalogue that grants a capability, or null. */
  private static BuildingInfo producerFor(String capability) {
    for (BuildingInfo info : Buildings.allBuildings().values()) {
      if (info.getGrants().contains(capability)) {
        return info;
      }
    }
    return null;
  }

  /**
   * Whether the village already knows nothing this size will fit.
   *
   * An upgrade is exempt: it is rebuilt on the ground it already occupies, and
   * its fit was checked by {@link BuildingUpgrade#fits} when the option was
   * offered. Only a NEW building needs somewhere to go.
   */
  private static boolean hasNoRoomFor(Village village, BuildingInfo info) {
    if (info.getUpgradesFrom() != null || village.getLevel() == null) {
      return false;
    }
    return village.getSiteMemory().ruledOut(
        SiteMemory.footprintOf(village.getLevel(), info.getName()), village.getVillageTime());
  }

  /**
   * Whether the village can pay for this, answered from a tally taken once for
   * the whole decision rather than by re-reading storage per material (#65).
   * The recipe is settled as a whole by {@link Materials#covers}, so logs the
   * log lines leave over may stand in for planks.
   */
  private static boolean hasMaterialsToConstruct(Village village, Map<Item, Integer> stock, BuildingInfo build){
    // Effective cost: the delta for an upgrade, the whole recipe for a fresh
    // build, so an upgrade becomes affordable as soon as its ADDITIONS are in
    // store rather than a second building's worth.
    return ConstructionQuote.capture(village, build, stock).affordable();
  }

  /**
   * The first cost item the village cannot cover for any building it might
   * otherwise want, or null when everything in the catalogue is affordable.
   * Used to log a shortage when planning stalls.
   */
  public static ItemStack firstUnaffordableMaterial(Village village){
    Map<Item, Integer> stock = village.stockTally();
    for (BuildingInfo build : Buildings.catalogue(village.getStyle())) {
      if (isFoundingOnly(build) || build.getUpgradesFrom() != null) {
        continue;
      }
      List<ItemStack> missing = ConstructionQuote.capture(village, build, stock).missing();
      if (!missing.isEmpty()) {
        return missing.get(0);
      }
    }
    return null;
  }

}
