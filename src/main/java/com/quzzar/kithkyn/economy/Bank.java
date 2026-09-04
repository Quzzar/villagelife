package com.quzzar.kithkyn.economy;

import java.util.Optional;

import com.quzzar.kithkyn.configuration.KithkynConfig;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;

/**
 * The abstract exchange that floors every price (docs/economy.md): always
 * available, no counterparty, deliberately punishing. One multiplier governs
 * both directions, so a single config value tunes how harsh the economy feels.
 *
 * Everything else in the market hangs off this spread. A village drowning in
 * wood can always convert to iron alone, badly, which is how biome poverty
 * resolves without a player; and because the bank pays so little, any player
 * offering a fairer rate is worth talking to. Village prices live inside this
 * band, never outside it.
 */
public final class Bank {

  private Bank() {
  }

  private static double spread() {
    return Math.max(1.0D, KithkynConfig.BankSpread);
  }

  /** What the bank pays the village for one of these: value / spread. */
  public static Optional<Double> buyPrice(Item item, MinecraftServer server) {
    return ItemValues.valueOf(item, server).map(value -> value / spread());
  }

  /** What the bank charges the village for one of these: value * spread. */
  public static Optional<Double> sellPrice(Item item, MinecraftServer server) {
    return ItemValues.valueOf(item, server).map(value -> value * spread());
  }

  /**
   * How many of {@code give} the bank demands for one {@code want}: the
   * "128 wood for one iron" number, and the reason a village prefers a player.
   */
  public static Optional<Integer> barterRate(Item give, Item want, MinecraftServer server) {
    Optional<Double> paid = buyPrice(give, server);
    Optional<Double> charged = sellPrice(want, server);
    if (paid.isEmpty() || charged.isEmpty() || paid.get() <= 0) {
      return Optional.empty();
    }
    return Optional.of((int) Math.ceil(charged.get() / paid.get()));
  }
}
