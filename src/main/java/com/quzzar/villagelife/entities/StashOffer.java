package com.quzzar.villagelife.entities;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.llm.LlmSelection;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.village.PersonalChest;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.buildings.BuildingInfo;
import com.quzzar.villagelife.village.buildings.Buildings;
import com.quzzar.villagelife.village.buildings.Materials;
import com.quzzar.villagelife.village.buildings.StructureInProgress;
import com.quzzar.villagelife.village.buildings.VillageGoal;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.TieredItem;

/**
 * Puts one bedtime question to a villager who has a chest of their own: of
 * what they are carrying home tonight, what would they rather keep than hand
 * back to the village stores? The rules lay out the facts, what is in the
 * pack and what the chest already holds, and one option per kind of item
 * carried; the model keeps any number of them, or none, in character, over
 * the multi-pick sibling of decide() (docs/llm-brain.md).
 *
 * <p>The chest is for keepsakes, and the briefing says so: the village runs on
 * what its workers bring in, so the question is what is truly personal, not
 * what to store. Two things learned live (2026-09-02): a chest introduced as
 * "shared with X and Y" read as shared storage, and a whole flock's wool went
 * home night after night as "personal supplies"; and a 3B model asked what to
 * keep "for yourself" heard an invitation. The job's kit is never on the list
 * at all, since the miner once kept the bucket and the torches the restock had
 * just handed over, and the shaft flooded while they sat in a barrel at home.
 *
 * <p>Silence keeps nothing. The rules' own choice is what always happened,
 * the whole pack back to the stores, so a mute or absent model costs the
 * village no goods; only an explicit pick holds something back. What is kept
 * stays in the pack for the walk home and is set down in the chest by hand
 * ({@link com.quzzar.villagelife.entities.ai.goals.StashAtHomeGoal}), so
 * nothing teleports. The answer lands through {@link RealPerson#settleStash},
 * which stows the rest of the pack: the pack is held whole while the question
 * is out, or the answer would arrive to empty pockets.
 */
public final class StashOffer {

  private StashOffer() {
  }

  /**
   * Asks, and settles the villager's stash on the main thread when the answer
   * lands. A villager carrying nothing, or a village with no brain ready, is
   * settled at once with nothing kept.
   */
  public static void offer(RealPerson person, BlockPos chest) {
    Map<Item, Integer> carried = carried(person);
    LlmService llm = LlmService.get();
    MinecraftServer server = person.getServer();
    if (carried.isEmpty() || !llm.isReady() || server == null) {
      person.settleStash(Set.of());
      return;
    }
    List<Item> kinds = new ArrayList<>();
    List<String> options = new ArrayList<>();
    for (Item item : carried.keySet()) {
      if (!isKit(item)) {
        kinds.add(item);
      }
    }
    if (kinds.isEmpty()) {
      person.settleStash(Set.of());
      return;
    }
    for (Item item : kinds) {
      options.add("Hold back the " + carried.get(item) + " " + plain(item) + " from the village stores");
    }
    String purpose = person.getFullName() + "'s chest at home";
    llm.choose(purpose, situation(person, chest, carried), options).whenComplete((selection, error) -> {
      if (error != null) {
        Villagelife.LOGGER.error("'{}' could not weigh what to keep at home", person.getFullName(), error);
      }
      Optional<LlmSelection> settled = selection == null ? Optional.empty() : selection;
      server.execute(() -> finish(person, kinds, settled));
    });
  }

  /**
   * The job's kit: any tool, and what the bedtime restock hands out (torches,
   * the bucket, the sponge). It was the village's when the day began and is
   * not the villager's to keep, so it is never offered.
   */
  private static boolean isKit(Item item) {
    return item instanceof TieredItem || item instanceof BucketItem || item instanceof ShearsItem
        || item instanceof FishingRodItem || item instanceof ProjectileWeaponItem
        || item == Items.TORCH || item == Items.SPONGE;
  }

  /** The facts the model decides on, kept to a few lines so a small model reads all of them. */
  private static String situation(RealPerson person, BlockPos chest, Map<Item, Integer> carried) {
    List<String> pack = new ArrayList<>();
    carried.forEach((item, count) -> pack.add(count + " " + plain(item)));
    StringBuilder situation = new StringBuilder(CraftOffer.identityLead(person))
        .append("You are turning in for the night carrying ").append(String.join(", ", pack))
        .append(". Your home has a small chest for keepsakes");
    Container container = PersonalChest.container(person, chest);
    if (container == null) {
      situation.append("; you cannot recall exactly what is in it. ");
    } else {
      String holds = PersonalChest.summarize(container);
      situation.append(holds.isEmpty() ? "; it is empty. " : "; it holds " + holds + ". ");
    }
    situation.append("It is not a store. The village lives on what its workers bring in each day: the")
        .append(" stores are where everyone draws wood, food, wool and tools, and anything held back at")
        .append(" home is lost to the village's work. ").append(villageNeed(person.getVillage()))
        .append(" Most nights a worker keeps nothing. Hold something back only")
        .append(" if it is truly personal, a gift, a memento or a bite of something you fancy, never")
        .append(" supplies or your day's produce. Give your reason in a few words.");
    return situation.toString();
  }

  /**
   * What the village is saving for or building and what it is still short of,
   * as the chat briefing states it (PersonChatContext), so the villager weighs
   * the wool in their pack against the beds it is waiting to become. Stated
   * even when there is nothing, since an unmentioned need is a gap the model
   * fills. The first night on the keepsakes wording (2026-09-02), four of nine
   * still held back white wool as a "keepsake" while the village was short of
   * it; the cost was in the pack and never in the question.
   */
  private static String villageNeed(@Nullable Village village) {
    if (village == null) {
      return "";
    }
    Map<Item, Integer> stock = village.stockTally();
    List<String> needs = new ArrayList<>();
    StructureInProgress project = village.getCurrentProject();
    if (project != null && project.isGathering()) {
      BuildingInfo built = project.getBuilding().getInfo();
      needs.add(need("building", built, Materials.describeShortfall(stock, built.getMaterialCost())));
    }
    String goal = VillageGoal.current(village);
    BuildingInfo wanted = goal == null ? null : Buildings.getByName(goal);
    if (wanted != null) {
      needs.add(need("saving to build", wanted, Materials.describeShortfall(stock, wanted.getMaterialCost())));
    }
    if (needs.isEmpty()) {
      return "The village is not short of anything for a build tonight.";
    }
    return String.join(" ", needs);
  }

  private static String need(String doing, BuildingInfo building, String shortfall) {
    String label = building.hasWellFormedId() ? building.getCategory().replace('_', ' ') : building.getName();
    return "The village is " + doing + " a " + label
        + (shortfall.isEmpty() ? " and has everything it needs for it." : " and is still short " + shortfall + ".");
  }

  private static void finish(RealPerson person, List<Item> kinds, Optional<LlmSelection> selection) {
    if (!person.isAlive()) {
      return;
    }
    Set<Item> keep = new LinkedHashSet<>();
    if (selection.isEmpty()) {
      Villagelife.LOGGER.debug("'{}' had no answer on what to keep at home, so the pack goes to the stores",
          person.getFullName());
    } else {
      for (int index : selection.get().choiceIndexes()) {
        keep.add(kinds.get(index));
      }
      if (keep.isEmpty()) {
        Villagelife.LOGGER.info("'{}' keeps nothing back tonight: {}", person.getFullName(),
            selection.get().reason());
      } else {
        Villagelife.LOGGER.info("'{}' keeps the {} for their chest at home: {}", person.getFullName(),
            names(keep), selection.get().reason());
      }
    }
    person.settleStash(keep);
  }

  /** Each kind of item in the pack with how many, in pack order. */
  private static Map<Item, Integer> carried(RealPerson person) {
    Map<Item, Integer> counts = new LinkedHashMap<>();
    for (int slot = 0; slot < person.personMainInv.getContainerSize(); slot++) {
      ItemStack stack = person.personMainInv.getItem(slot);
      if (!stack.isEmpty()) {
        counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
      }
    }
    return counts;
  }

  /** An item as the model reads it: "wheat", "iron pickaxe". */
  public static String plain(@Nullable Item item) {
    return item == null ? "nothing" : item.getDescription().getString().toLowerCase(Locale.ROOT);
  }

  /** Several kinds as a log reads them: "wheat and apple and bread". */
  public static String names(Collection<Item> items) {
    List<String> out = new ArrayList<>();
    for (Item item : items) {
      out.add(plain(item));
    }
    return out.isEmpty() ? "nothing" : String.join(" and ", out);
  }
}
