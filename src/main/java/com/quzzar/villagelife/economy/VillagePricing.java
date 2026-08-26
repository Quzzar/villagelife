package com.quzzar.villagelife.economy;

import java.util.Optional;

import com.quzzar.villagelife.village.Village;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What a village offers, as opposed to what a thing is worth (docs/economy.md).
 *
 * The rate table gives an item's value; a village's own supply and demand
 * moves its offer inside the bank's band. Granaries full of wheat buy more of
 * it grudgingly, near the bank's floor; a smithy idle for want of iron pays
 * near the top. The band is never left, so trading with a village is always
 * at least as good as the bank and never free money.
 */
public final class VillagePricing {

  /** Stock at or above this many of an item counts as a glut. */
  private static final int GLUT = 128;
  /** Stock at or below this counts as a shortage. */
  private static final int SCARCE = 8;

  private VillagePricing() {
  }

  /**
   * What the village pays the player for one of these. Bounded below by the
   * bank's buy price (never worse than dumping it) and above by the item's
   * value (a village never pays more than a thing is worth).
   */
  public static Optional<Double> villageBuys(Village village, Item item, MinecraftServer server) {
    Optional<Double> value = ItemValues.valueOf(item, server);
    Optional<Double> floor = Bank.buyPrice(item, server);
    if (value.isEmpty() || floor.isEmpty()) {
      return Optional.empty();
    }
    // 0 when the village is glutted, 1 when it is short.
    double need = demand(village, item);
    return Optional.of(floor.get() + (value.get() - floor.get()) * need);
  }

  /**
   * What the village charges the player for one of these: bounded below by the
   * item's value and above by the bank's sell price, moving the opposite way.
   * A village sells its glut cheaply and guards what it is short of.
   */
  public static Optional<Double> villageSells(Village village, Item item, MinecraftServer server) {
    Optional<Double> value = ItemValues.valueOf(item, server);
    Optional<Double> ceiling = Bank.sellPrice(item, server);
    if (value.isEmpty() || ceiling.isEmpty()) {
      return Optional.empty();
    }
    double need = demand(village, item);
    return Optional.of(value.get() + (ceiling.get() - value.get()) * need);
  }

  /**
   * 0 when the village is drowning in the item, 1 when it has none, sliding
   * linearly between the scarce and glut thresholds.
   */
  public static double demand(Village village, Item item) {
    int held = countHeld(village, item);
    if (held >= GLUT) {
      return 0.0D;
    }
    if (held <= SCARCE) {
      return 1.0D;
    }
    return 1.0D - ((double) (held - SCARCE) / (GLUT - SCARCE));
  }

  public static int countHeld(Village village, Item item) {
    int total = 0;
    for (ItemStack stack : village.getVillageInventory()) {
      if (stack.getItem() == item) {
        total += stack.getCount();
      }
    }
    return total;
  }
}
