package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What pays for what (docs/building-spec.md, "Every building is now priced").
 *
 * A recipe names one item per material, but the village pays in kind: a cost
 * that names a log is met by any log, whichever wood the guard or lumberjack
 * happened to fell. A camp in a mangrove swamp never sees an oak log, and
 * before this rule it would have sat on its first house for good with a store
 * full of wood that nothing counted. Every other material is exact.
 *
 * This is the one place the rule lives. The planner's tally, a goal's
 * shortfall, the builder's gathering and the commit that spends the recipe,
 * the wall's draw and the market's reserve all ask here rather than comparing
 * items themselves, so widening it (planks, one day) is one edit.
 */
public final class Materials {

  private Materials() {
  }

  /** Whether the item is a log of any wood. */
  public static boolean isLog(Item item) {
    return item.builtInRegistryHolder().is(ItemTags.LOGS);
  }

  /** Whether an item in hand pays toward a cost that names another. */
  public static boolean pays(Item held, Item wanted) {
    return held == wanted || (isLog(wanted) && isLog(held));
  }

  /** How much of a stock tally counts toward this cost item. */
  public static int counted(Map<Item, Integer> stock, Item wanted) {
    if (!isLog(wanted)) {
      return stock.getOrDefault(wanted, 0);
    }
    int total = 0;
    for (Map.Entry<Item, Integer> entry : stock.entrySet()) {
      if (pays(entry.getKey(), wanted)) {
        total += entry.getValue();
      }
    }
    return total;
  }

  /** How much a container holds that pays toward this cost item. */
  public static int held(Container container, Item wanted) {
    int total = 0;
    for (int slot = 0; slot < container.getContainerSize(); slot++) {
      ItemStack stack = container.getItem(slot);
      if (!stack.isEmpty() && pays(stack.getItem(), wanted)) {
        total += stack.getCount();
      }
    }
    return total;
  }

  /**
   * Takes up to {@code amount} of whatever pays toward the cost item out of
   * the container, last slots first as {@code Utils.removeItem} does, and
   * returns what came out: one stack per wood, so a builder paying a log cost
   * from a mixed store carries a mixed load.
   */
  public static List<ItemStack> take(Container container, Item wanted, int amount) {
    List<ItemStack> taken = new ArrayList<>();
    int left = amount;
    for (int slot = container.getContainerSize() - 1; slot >= 0 && left > 0; slot--) {
      ItemStack stack = container.getItem(slot);
      if (stack.isEmpty() || !pays(stack.getItem(), wanted)) {
        continue;
      }
      ItemStack piece = stack.split(left);
      left -= piece.getCount();
      addTo(taken, piece);
    }
    if (!taken.isEmpty()) {
      container.setChanged();
    }
    return taken;
  }

  /** Merges a piece into the list, growing an existing stack of the same item. */
  private static void addTo(List<ItemStack> stacks, ItemStack piece) {
    for (ItemStack stack : stacks) {
      if (ItemStack.isSameItemSameComponents(stack, piece)) {
        stack.grow(piece.getCount());
        return;
      }
    }
    stacks.add(piece);
  }

  /**
   * The cost item in words, for the brain's briefing and a villager's mouth.
   * A log cost is spoken as "logs", because that is what pays for it; anything
   * else is its plain name, "cobblestone" for "minecraft:cobblestone".
   */
  public static String describe(Item wanted) {
    if (isLog(wanted)) {
      return "logs";
    }
    return wanted.toString().replace("minecraft:", "").replace('_', ' ');
  }

}
