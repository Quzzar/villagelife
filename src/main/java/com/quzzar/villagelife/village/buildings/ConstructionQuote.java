package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.quzzar.villagelife.village.Village;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * One authoritative quote for a building in one village. A fresh building
 * requires its whole recipe; an upgrade requires only what it adds.
 */
public record ConstructionQuote(List<ItemStack> required, List<ItemStack> missing) {

  public ConstructionQuote {
    required = copy(required);
    missing = copy(missing);
  }

  /** Captures the effective recipe and shortfall against one existing stock tally. */
  public static ConstructionQuote capture(Village village, BuildingInfo info, Map<Item, Integer> stock) {
    List<ItemStack> required = requiredFor(village, info);
    return new ConstructionQuote(required, Materials.shortfall(stock, required));
  }

  /** Includes materials already in assigned builders' packs for an active project. */
  public static ConstructionQuote captureProject(Village village, BuildingInfo info,
      Map<Item, Integer> stock) {
    Map<Item, Integer> gathered = new HashMap<>(stock);
    village.builderPackTally().forEach((item, count) -> gathered.merge(item, count, Integer::sum));
    return capture(village, info, gathered);
  }

  /** The effective recipe without reading storage, suitable for gather and spend paths. */
  public static List<ItemStack> requiredFor(Village village, BuildingInfo info) {
    return copy(BuildingUpgrade.effectiveCost(village, info));
  }

  public boolean affordable() {
    return missing.isEmpty();
  }

  /** The whole effective recipe in compact villager-facing words. */
  public String describeRequired() {
    return describe(required);
  }

  /** Only what the current stock still lacks, in compact villager-facing words. */
  public String describeMissing() {
    return describe(missing);
  }

  /** Amount of one payment item committed to this effective recipe. */
  public int requiredFrom(Item item) {
    return countPaidBy(required, item);
  }

  /** Amount of one payment item still absent from this quote. */
  public int missingFrom(Item item) {
    return countPaidBy(missing, item);
  }

  private static int countPaidBy(List<ItemStack> stacks, Item item) {
    int count = 0;
    for (ItemStack stack : stacks) {
      if (Materials.pays(item, stack.getItem())) {
        count += stack.getCount();
      }
    }
    return count;
  }

  private static String describe(List<ItemStack> stacks) {
    List<String> parts = new ArrayList<>();
    for (ItemStack stack : stacks) {
      parts.add(stack.getCount() + " " + Materials.describe(stack.getItem()));
    }
    return String.join(", ", parts);
  }

  private static List<ItemStack> copy(List<ItemStack> stacks) {
    return List.copyOf(stacks.stream().map(ItemStack::copy).toList());
  }
}
