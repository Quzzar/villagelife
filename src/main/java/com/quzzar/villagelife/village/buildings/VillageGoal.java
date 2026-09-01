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
  private static final String KEY_SHORT_AT_SET = "goal_short_at_set";
  private static final String KEY_STALLED = "goal_stalled_building";
  private static final String KEY_STALLED_AT = "goal_stalled_at";

  /** Village seconds a goal stands before the village gives up on it. */
  public static final int GOAL_LIFETIME_SECONDS = 1200;

  /**
   * Village seconds a stalled building stays off the table. Long enough that the
   * village names something else and works on it, short enough that a lodge it
   * could not reach today is back in the running once circumstances change.
   */
  public static final int STALL_COOLDOWN_SECONDS = 2 * GOAL_LIFETIME_SECONDS;

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

  /**
   * Names the goal. {@code shortfall} is what it was still short of at that
   * moment, kept so expiry can tell a goal that made progress from one nothing
   * ever came in for (see {@link #markStalled}).
   */
  public static void set(Village village, String buildingName, String reason, String shortfall,
      int villageTime) {
    CompoundTag strategy = village.getBrain().getStrategy();
    strategy.putString(KEY_BUILDING, buildingName);
    strategy.putString(KEY_REASON, reason == null ? "" : reason);
    strategy.putString(KEY_SHORT_AT_SET, shortfall == null ? "" : shortfall);
    strategy.putInt(KEY_SET_AT, villageTime);
    Villagelife.LOGGER.info("Village '{}' is saving for {}: {}", village.getName(), buildingName, reason);
  }

  /** What the current goal was short of when it was named; empty when unknown. */
  public static String shortfallAtSet(Village village) {
    return village.getBrain().getStrategy().getString(KEY_SHORT_AT_SET);
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
    strategy.remove(KEY_SHORT_AT_SET);
  }

  /**
   * Records that a goal ran its whole lifetime with its shortfall unchanged:
   * nothing the village has can produce what it lacks, whatever the reachability
   * test thought. The building sits out {@link #STALL_COOLDOWN_SECONDS} so the
   * planner names something it can actually work toward, instead of the same
   * unreachable thing again and again (a fresh camp re-named its lumberjack
   * lodge ten times over four hours, short the same nine logs throughout).
   */
  public static void markStalled(Village village, String buildingName, int villageTime) {
    CompoundTag strategy = village.getBrain().getStrategy();
    strategy.putString(KEY_STALLED, buildingName);
    strategy.putInt(KEY_STALLED_AT, villageTime);
    Villagelife.LOGGER.info("Village '{}' gave up on {} for now: nothing came in for it the whole time",
        village.getName(), buildingName);
  }

  /** The building currently sitting out for having stalled, or null when none is. */
  @Nullable
  public static String stalled(Village village, int villageTime) {
    CompoundTag strategy = village.getBrain().getStrategy();
    String building = strategy.getString(KEY_STALLED);
    if (building.isEmpty()) {
      return null;
    }
    if (villageTime - strategy.getInt(KEY_STALLED_AT) > STALL_COOLDOWN_SECONDS) {
      strategy.remove(KEY_STALLED);
      strategy.remove(KEY_STALLED_AT);
      return null;
    }
    return building;
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
