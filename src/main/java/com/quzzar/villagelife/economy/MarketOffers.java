package com.quzzar.villagelife.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.quzzar.villagelife.village.Village;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What a village puts on its stall: the surplus it will part with, and the
 * shortages it will pay well for (docs/economy.md). The wanted list is the
 * point of the market - a village's needs are a paid job board - so it is
 * built from what the village lacks, not from a fixed catalogue.
 *
 * Anything valued can still be sold to a village that is not asking for it;
 * it simply will not be listed, and the price will sit near the bank's floor.
 */
public final class MarketOffers {

  /** How many rows each column shows before it stops being a market and starts being a spreadsheet. */
  public static final int MAX_ROWS = 6;

  /** One line on the stall: what it is, how many, and what an emerald buys. */
  public record Offer(Item item, int quantity, double unitPrice) {
  }

  /** Staples a village always cares about, so a new market is never empty. */
  private static final List<String> WANTED_STAPLES = List.of(
      "minecraft:bread", "minecraft:wheat", "minecraft:iron_ingot", "minecraft:oak_log",
      "minecraft:cobblestone", "minecraft:coal", "minecraft:leather", "minecraft:wool");

  private MarketOffers() {
  }

  /** The village's surplus, priciest first: what a player can buy. */
  public static List<Offer> selling(Village village, ServerLevel level) {
    List<Offer> offers = new ArrayList<>();
    Set<Item> seen = new HashSet<>();
    for (ItemStack stack : village.getVillageInventory()) {
      Item item = stack.getItem();
      if (item == Treasury.CURRENCY || !seen.add(item)) {
        continue; // the treasury is money, not stock
      }
      int held = VillagePricing.countHeld(village, item);
      if (held <= 0) {
        continue;
      }
      Optional<Double> price = VillagePricing.villageSells(village, item, level.getServer());
      if (price.isEmpty()) {
        continue; // nothing can value it, so it is not tradeable
      }
      offers.add(new Offer(item, held, price.get()));
    }
    offers.sort(Comparator.comparingDouble((Offer offer) -> offer.unitPrice() * offer.quantity()).reversed());
    return offers.size() > MAX_ROWS ? offers.subList(0, MAX_ROWS) : offers;
  }

  /**
   * What the village is short of and will pay near full value for: the job
   * board. Staples it holds little of, best price first.
   */
  public static List<Offer> wanted(Village village, ServerLevel level) {
    List<Offer> offers = new ArrayList<>();
    for (String id : WANTED_STAPLES) {
      Optional<Item> item = ItemValues.item(id);
      if (item.isEmpty()) {
        continue;
      }
      double demand = VillagePricing.demand(village, item.get());
      if (demand < 0.5D) {
        continue; // they have plenty; it belongs on the other column if anywhere
      }
      Optional<Double> price = VillagePricing.villageBuys(village, item.get(), level.getServer());
      if (price.isEmpty()) {
        continue;
      }
      offers.add(new Offer(item.get(), VillagePricing.countHeld(village, item.get()), price.get()));
    }
    offers.sort(Comparator.comparingDouble(Offer::unitPrice).reversed());
    return offers.size() > MAX_ROWS ? offers.subList(0, MAX_ROWS) : offers;
  }

  public static String idOf(Item item) {
    return BuiltInRegistries.ITEM.getKey(item).toString();
  }
}
