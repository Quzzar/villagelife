package com.quzzar.villagelife.entities.ai;

import com.quzzar.villagelife.entities.AgeStage;

/** Age-specific bedtime with one shared dawn wake-up. */
public final class FamilySleepPolicy {

  private static final long DAY_LENGTH = 24_000L;
  private static final long TODDLER_BEDTIME = 11_000L;
  private static final long KID_BEDTIME = 12_000L;
  private static final long TEENAGER_BEDTIME = 13_000L;

  private FamilySleepPolicy() {
  }

  /** Dependents retire progressively later; adults keep Minecraft's ordinary night check. */
  public static boolean shouldRest(AgeStage stage, long dayTime, boolean vanillaNight) {
    if (stage == AgeStage.ADULT) {
      return vanillaNight;
    }
    long clock = Math.floorMod(dayTime, DAY_LENGTH);
    return clock >= bedtime(stage);
  }

  /** All household children rise at dawn with the ordinary sleeping household. */
  public static boolean shouldWake(AgeStage stage, long dayTime, boolean vanillaDay) {
    if (stage == AgeStage.ADULT) {
      return vanillaDay;
    }
    return Math.floorMod(dayTime, DAY_LENGTH) < bedtime(stage);
  }

  static long bedtime(AgeStage stage) {
    return switch (stage) {
      case TODDLER -> TODDLER_BEDTIME;
      case KID -> KID_BEDTIME;
      case TEENAGER -> TEENAGER_BEDTIME;
      case ADULT -> TEENAGER_BEDTIME;
    };
  }
}
