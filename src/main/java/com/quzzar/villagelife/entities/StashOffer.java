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

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Puts one bedtime question to a villager who has a chest of their own: of
 * what they are carrying home tonight, what would they rather keep than hand
 * back to the village stores? The rules lay out the facts, what is in the
 * pack, what the chest already holds and who shares it, and one option per
 * kind of item carried; the model keeps any number of them, or none, in
 * character, over the multi-pick sibling of decide() (docs/llm-brain.md).
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
    List<Item> kinds = new ArrayList<>(carried.keySet());
    List<String> options = new ArrayList<>();
    for (Item item : kinds) {
      options.add("Keep the " + carried.get(item) + " " + plain(item));
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

  /** The facts the model decides on, kept to a few lines so a small model reads all of them. */
  private static String situation(RealPerson person, BlockPos chest, Map<Item, Integer> carried) {
    List<String> pack = new ArrayList<>();
    carried.forEach((item, count) -> pack.add(count + " " + plain(item)));
    StringBuilder situation = new StringBuilder(CraftOffer.identityLead(person))
        .append("You are turning in for the night carrying ").append(String.join(", ", pack))
        .append(". Your home has a chest of your own");
    List<String> housemates = PersonalChest.housemateNames(person);
    if (!housemates.isEmpty()) {
      situation.append(", shared with ").append(String.join(" and ", housemates));
    }
    Container container = PersonalChest.container(person, chest);
    if (container == null) {
      situation.append("; you cannot recall exactly what is in it. ");
    } else {
      String holds = PersonalChest.summarize(container);
      situation.append(holds.isEmpty() ? "; it is empty. " : "; it holds " + holds + ". ");
    }
    situation.append("Whatever you put there is yours, not the village's: no worker takes from it and the")
        .append(" quartermaster leaves it alone. Decide which of these, if any, to keep for yourself;")
        .append(" everything else goes back to the village stores tonight. Give your reason in a few words.");
    return situation.toString();
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
