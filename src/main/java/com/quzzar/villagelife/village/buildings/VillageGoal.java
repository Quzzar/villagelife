package com.quzzar.villagelife.village.buildings;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.village.Village;

import net.minecraft.nbt.CompoundTag;

/**
 * Something a village has decided to save up for (decided on #58, the brain's
 * second verb).
 *
 * A goal is an intent that outlives the decision that made it. Without one a
 * village can only ever choose what it can afford this minute, which means it
 * can never deliberately reach for something expensive: it will always spend
 * its logs on the cheap thing in front of it. With one, it declines to spend,
 * and the declining is visible — that is what saving looks like from outside.
 *
 * Stored in the brain's {@code strategy} tag, which has existed since the port
 * and been documented as reserved for exactly this without ever having a
 * tenant. Goals expire so a village cannot starve itself forever chasing
 * something its economy will never reach.
 */
public final class VillageGoal {

  private static final String KEY_BUILDING = "goal_building";
  private static final String KEY_SET_AT = "goal_set_at";
  private static final String KEY_REASON = "goal_reason";

  /** Village seconds a goal stands before the village gives up on it. */
  public static final int GOAL_LIFETIME_SECONDS = 1200;

  private VillageGoal() {
  }

  /** The building this village is saving for, or null when it is not saving. */
  @Nullable
  public static String current(Village village) {
    CompoundTag strategy = village.getBrain().getStrategy();
    String building = strategy.getString(KEY_BUILDING);
    return building.isEmpty() ? null : building;
  }

  /** Why it said it wanted that, in its own words. */
  public static String reason(Village village) {
    return village.getBrain().getStrategy().getString(KEY_REASON);
  }

  public static void set(Village village, String buildingName, String reason, int villageTime) {
    CompoundTag strategy = village.getBrain().getStrategy();
    strategy.putString(KEY_BUILDING, buildingName);
    strategy.putString(KEY_REASON, reason == null ? "" : reason);
    strategy.putInt(KEY_SET_AT, villageTime);
    Villagelife.LOGGER.info("Village '{}' is saving for {}: {}", village.getName(), buildingName, reason);
  }

  public static void clear(Village village, String why) {
    CompoundTag strategy = village.getBrain().getStrategy();
    if (!strategy.getString(KEY_BUILDING).isEmpty()) {
      Villagelife.LOGGER.info("Village '{}' is no longer saving for {} ({})",
          village.getName(), strategy.getString(KEY_BUILDING), why);
    }
    strategy.remove(KEY_BUILDING);
    strategy.remove(KEY_SET_AT);
    strategy.remove(KEY_REASON);
  }

  /** True once the goal has stood too long to still be worth waiting for. */
  public static boolean hasExpired(Village village, int villageTime) {
    CompoundTag strategy = village.getBrain().getStrategy();
    if (strategy.getString(KEY_BUILDING).isEmpty()) {
      return false;
    }
    return villageTime - strategy.getInt(KEY_SET_AT) > GOAL_LIFETIME_SECONDS;
  }

}
