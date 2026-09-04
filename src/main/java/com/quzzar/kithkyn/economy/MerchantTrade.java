package com.quzzar.kithkyn.economy;

import java.util.Optional;

import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.village.Village;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * One transaction between a player and a wandering merchant (docs/economy.md,
 * "Wandering merchant"). The mirror of {@link Trade}, but the merchant's side
 * is VIRTUAL: prices and stock come from the merchant's own ledger
 * ({@link EconomySnapshot}), which drifts as it trades and holds its own
 * emerald money box, seeded from the home village's balance. Nothing physical
 * ever rides on the merchant, so killing it drops no trade loot.
 *
 * <p>The player's side is real: goods and emeralds move in and out of the
 * player's inventory exactly as a village trade would. Only the merchant keeps
 * books instead of chests. Standing is read live from the home village, so a
 * village that dislikes you charges its travelling merchant's prices against
 * you too; a home village since deleted trades at par and refuses no one.
 */
public final class MerchantTrade {

  private MerchantTrade() {
  }

  /** The merchant sells {@code count} of an item to the player. */
  public static Trade.Result sellToPlayer(RealPerson merchant, ServerLevel level, ServerPlayer player,
      Item item, int count) {
    EconomySnapshot ledger = merchant.getWanderingStock();
    if (ledger == null) {
      return Trade.Result.fail("This merchant has nothing to trade.");
    }
    if (item == Treasury.CURRENCY) {
      return Trade.Result.fail("Emeralds are the money here, not goods.");
    }
    Village source = merchant.getSourceVillage();
    Optional<String> refused = standingRefusal(source, level, player);
    if (refused.isPresent()) {
      return Trade.Result.fail("The merchant will not trade with you: " + refused.get() + ".");
    }
    Optional<Double> unit = VillagePricing.sellsAt(item, level.getServer(),
        VillagePricing.demandFromHeld(ledger.count(item)))
        .map(value -> value * standingMarkup(source, level, player));
    if (unit.isEmpty()) {
      return Trade.Result.fail("The merchant will not trade in that.");
    }
    int held = ledger.count(item);
    if (held < count) {
      return Trade.Result.fail("The merchant has only " + held + " to sell.");
    }
    int price = (int) Math.ceil(unit.get() * count);
    if (countInInventory(player, Items.EMERALD) < price) {
      return Trade.Result.fail("That costs " + price + " emeralds and you do not have them.");
    }
    int taken = ledger.remove(item, count);
    if (taken <= 0) {
      return Trade.Result.fail("The goods were gone when the merchant looked.");
    }
    int owed = (int) Math.ceil(unit.get() * taken);
    removeFromInventory(player, Items.EMERALD, owed);
    ledger.add(Treasury.CURRENCY, owed); // the merchant's virtual money box gains the price
    giveOrDrop(player, new ItemStack(item, taken));
    return new Trade.Result(true, "Bought " + taken + " " + item.getDescriptionId()
        + " from the merchant for " + owed + " emeralds.");
  }

  /** The merchant buys {@code count} of an item from the player. */
  public static Trade.Result buyFromPlayer(RealPerson merchant, ServerLevel level, ServerPlayer player,
      Item item, int count) {
    EconomySnapshot ledger = merchant.getWanderingStock();
    if (ledger == null) {
      return Trade.Result.fail("This merchant has nothing to trade.");
    }
    if (item == Treasury.CURRENCY) {
      return Trade.Result.fail("Emeralds are the money here, not goods.");
    }
    Village source = merchant.getSourceVillage();
    Optional<String> refused = standingRefusal(source, level, player);
    if (refused.isPresent()) {
      return Trade.Result.fail("The merchant will not trade with you: " + refused.get() + ".");
    }
    Optional<Double> unit = VillagePricing.buysAt(item, level.getServer(),
        VillagePricing.demandFromHeld(ledger.count(item)))
        .map(value -> value / standingMarkup(source, level, player));
    if (unit.isEmpty()) {
      return Trade.Result.fail("The merchant will not trade in that.");
    }
    if (countInInventory(player, item) < count) {
      return Trade.Result.fail("You do not have " + count + " of those.");
    }
    int price = (int) Math.floor(unit.get() * count);
    if (price <= 0) {
      return Trade.Result.fail("That is worth less than an emerald here. Offer more of it.");
    }
    int budget = ledger.count(Treasury.CURRENCY);
    if (budget < price) {
      return Trade.Result.fail("The merchant wants it but has only " + budget
          + " emeralds, and the price is " + price + ".");
    }
    removeFromInventory(player, item, count);
    ledger.add(item, count); // into the merchant's virtual stock, not onto its body
    ledger.remove(Treasury.CURRENCY, price); // paid from its virtual money box
    giveOrDrop(player, new ItemStack(Items.EMERALD, price));
    return new Trade.Result(true, "Sold " + count + " " + item.getDescriptionId()
        + " to the merchant for " + price + " emeralds.");
  }

  private static Optional<String> standingRefusal(Village source, ServerLevel level, ServerPlayer player) {
    if (source == null) {
      return Optional.empty();
    }
    com.quzzar.kithkyn.wrongdoing.Standing.Tier tier =
        com.quzzar.kithkyn.wrongdoing.Standing.tierOf(source, level, player.getUUID());
    return tier.atLeastAsBadAs(com.quzzar.kithkyn.wrongdoing.Standing.Tier.UNWELCOME)
        ? Optional.of(com.quzzar.kithkyn.wrongdoing.Standing.refusalReason(tier))
        : Optional.empty();
  }

  private static double standingMarkup(Village source, ServerLevel level, ServerPlayer player) {
    if (source == null) {
      return 1.0D;
    }
    return com.quzzar.kithkyn.wrongdoing.Standing.priceMultiplier(
        com.quzzar.kithkyn.wrongdoing.Standing.of(source, level, player.getUUID()));
  }

  private static int countInInventory(ServerPlayer player, Item item) {
    int total = 0;
    for (ItemStack stack : player.getInventory().items) {
      if (stack.getItem() == item) {
        total += stack.getCount();
      }
    }
    return total;
  }

  private static void removeFromInventory(ServerPlayer player, Item item, int count) {
    int remaining = count;
    for (ItemStack stack : player.getInventory().items) {
      if (remaining <= 0) {
        break;
      }
      if (stack.getItem() == item) {
        int taken = Math.min(stack.getCount(), remaining);
        stack.shrink(taken);
        remaining -= taken;
      }
    }
    player.getInventory().setChanged();
  }

  private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
    if (!player.getInventory().add(stack)) {
      player.drop(stack, false);
    }
  }
}
