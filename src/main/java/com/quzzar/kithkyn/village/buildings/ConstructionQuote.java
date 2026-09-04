package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.quzzar.kithkyn.village.Village;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * One authoritative quote for a building in one village. An upgrade pays its
 * positive recipe increase; a fresh higher level pays the base plus each increase.
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

  /** Captures a quote whose fresh-or-upgrade choice has already been made. */
  public static ConstructionQuote capture(ConstructionChoice choice, Map<Item, Integer> stock) {
    List<ItemStack> required = choice.redevelopment() == null ? requiredFor(choice.info(), choice.mode())
        : MaterialAmount.stacks(choice.redevelopment().netRequired());
    return new ConstructionQuote(required, Materials.shortfall(stock, required));
  }

  /** Uses the mode preserved with a saved goal, with legacy inference for old saves. */
  public static ConstructionQuote captureGoal(Village village, BuildingInfo info, Map<Item, Integer> stock) {
    RedevelopmentPlan redevelopment = VillageGoal.redevelopment(village);
    if (redevelopment != null) {
      return capture(new ConstructionChoice(info, redevelopment.mode(), redevelopment), stock);
    }
    ConstructionMode mode = VillageGoal.mode(village);
    return mode == null
        ? capture(village, info, stock)
        : capture(new ConstructionChoice(info, mode), stock);
  }

  /** Includes materials already in assigned builders' packs for an active project. */
  public static ConstructionQuote captureProject(Village village, StructureInProgress project,
      Map<Item, Integer> stock) {
    Map<Item, Integer> gathered = new HashMap<>(stock);
    village.builderPackTally().forEach((item, count) -> gathered.merge(item, count, Integer::sum));
    List<ItemStack> required = project.requiredMaterials();
    return new ConstructionQuote(required, Materials.shortfall(gathered, required));
  }

  /** The effective recipe without reading storage, suitable for gather and spend paths. */
  public static List<ItemStack> requiredFor(Village village, BuildingInfo info) {
    return copy(BuildingUpgrade.effectiveCost(village, info));
  }

  /** The recipe for an already-chosen mode, stable throughout an active project. */
  public static List<ItemStack> requiredFor(BuildingInfo info, ConstructionMode mode) {
    return copy(BuildingUpgrade.effectiveCost(info, mode));
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
