package com.quzzar.villagelife.economy;

import java.util.List;
import java.util.Optional;

import com.quzzar.villagelife.village.Village;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * One transaction between a player and a village (docs/economy.md). Prices
 * come from {@link VillagePricing}, which keeps them inside the bank's band;
 * the money is real emeralds from the {@link Treasury}; the goods are real
 * items moving between the player's inventory and the village's chests.
 *
 * Every trade is checked in full before anything moves, so a half-completed
 * exchange cannot leave either side short.
 */
public final class Trade {

  /** What happened, in words the caller can show as-is. */
  public record Result(boolean ok, String message) {
    static Result fail(String why) {
      return new Result(false, why);
    }
  }

  private Trade() {
  }

  /** The village buys {@code count} of an item from the player. */
  public static Result villageBuys(Village village, ServerLevel level, ServerPlayer player, Item item, int count) {
    if (item == Treasury.CURRENCY) {
      return Result.fail("Emeralds are the money here, not goods.");
    }
    Optional<String> blocked = Treasury.tradeBlocker(village, level);
    if (blocked.isPresent()) {
      return Result.fail("'" + village.getName() + "' cannot trade: " + blocked.get() + ".");
    }
    Optional<String> refused = standingRefusal(village, level, player);
    if (refused.isPresent()) {
      return Result.fail("'" + village.getName() + "' will not trade with you: " + refused.get() + ".");
    }
    Optional<Double> unit = VillagePricing.villageBuys(village, item, level.getServer())
        // Standing moves the price against you before it closes the door (#64).
        .map(value -> value / standingMarkup(village, level, player));
    if (unit.isEmpty()) {
      return Result.fail("The village will not trade in that.");
    }
    if (countInInventory(player, item) < count) {
      return Result.fail("You do not have " + count + " of those.");
    }

    // Whole emeralds only: a village cannot pay a fraction of a gem.
    int price = (int) Math.floor(unit.get() * count);
    if (price <= 0) {
      return Result.fail("That is worth less than an emerald here. Offer more of it.");
    }
    int balance = Treasury.balance(village, level);
    if (balance < price) {
      return Result.fail("'" + village.getName() + "' wants it but has only " + balance
          + " emeralds, and the price is " + price + ".");
    }

    List<Container> chests = Treasury.chests(village, level);
    if (chests.isEmpty()) {
      return Result.fail("The market has nowhere to put it.");
    }
    // Stock goes in first: if it does not fit, nothing has moved yet.
    int leftover = count;
    for (Container container : chests) {
      if (leftover <= 0) {
        break;
      }
      leftover = Treasury.insert(container, new ItemStack(item, leftover));
    }
    int accepted = count - leftover;
    if (accepted <= 0) {
      return Result.fail("The market's chests are full.");
    }

    int paid = (int) Math.floor(unit.get() * accepted);
    Treasury.withdraw(village, level, paid);
    removeFromInventory(player, item, accepted);
    giveOrDrop(player, new ItemStack(Items.EMERALD, paid));

    return new Result(true, "Sold " + accepted + " " + item.getDescriptionId()
        + " to '" + village.getName() + "' for " + paid + " emeralds.");
  }

  /** The village sells {@code count} of an item to the player. */
  public static Result villageSells(Village village, ServerLevel level, ServerPlayer player, Item item, int count) {
    if (item == Treasury.CURRENCY) {
      return Result.fail("Emeralds are the money here, not goods.");
    }
    Optional<String> blocked = Treasury.tradeBlocker(village, level);
    if (blocked.isPresent()) {
      return Result.fail("'" + village.getName() + "' cannot trade: " + blocked.get() + ".");
    }
    Optional<String> refused = standingRefusal(village, level, player);
    if (refused.isPresent()) {
      return Result.fail("'" + village.getName() + "' will not trade with you: " + refused.get() + ".");
    }
    Optional<Double> unit = VillagePricing.villageSells(village, item, level.getServer())
        .map(value -> value * standingMarkup(village, level, player));
    if (unit.isEmpty()) {
      return Result.fail("The village will not trade in that.");
    }
    int held = VillagePricing.countHeld(village, item);
    if (held < count) {
      return Result.fail("'" + village.getName() + "' has only " + held + " to sell.");
    }
    int price = (int) Math.ceil(unit.get() * count);
    if (countInInventory(player, Items.EMERALD) < price) {
      return Result.fail("That costs " + price + " emeralds and you do not have them.");
    }

    // Take the goods out of the village first; only pay for what came out.
    int taken = village.gatherItemStackFromVillage(new ItemStack(item, count)).getCount();
    if (taken <= 0) {
      return Result.fail("The goods were gone when the merchant looked.");
    }

    int owed = (int) Math.ceil(unit.get() * taken);
    removeFromInventory(player, Items.EMERALD, owed);
    Treasury.deposit(village, level, owed);
    giveOrDrop(player, new ItemStack(item, taken));

    return new Result(true, "Bought " + taken + " " + item.getDescriptionId()
        + " from '" + village.getName() + "' for " + owed + " emeralds.");
  }

  /**
   * Why this village will not deal with this person, if it will not. Standing
   * closes the market before it sets guards on anyone, so being unwelcome is
   * the first consequence a player actually feels (docs/economy.md).
   */
  private static Optional<String> standingRefusal(Village village, ServerLevel level, ServerPlayer player) {
    com.quzzar.villagelife.wrongdoing.Standing.Tier tier =
        com.quzzar.villagelife.wrongdoing.Standing.tierOf(village, level, player.getUUID());
    return tier.atLeastAsBadAs(com.quzzar.villagelife.wrongdoing.Standing.Tier.UNWELCOME)
        ? Optional.of(com.quzzar.villagelife.wrongdoing.Standing.refusalReason(tier))
        : Optional.empty();
  }

  /** How much worse than ordinary this village's prices are for this person. */
  private static double standingMarkup(Village village, ServerLevel level, ServerPlayer player) {
    return com.quzzar.villagelife.wrongdoing.Standing.priceMultiplier(
        com.quzzar.villagelife.wrongdoing.Standing.of(village, level, player.getUUID()));
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
