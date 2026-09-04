package com.quzzar.kithkyn.entities.ai;

/** Shared health thresholds and timing for a person's recovery behaviors. */
public final class HealthRecoveryPolicy {

  /** Ten seconds of Regeneration I after using the village campfire. */
  public static final int CAMPFIRE_REGENERATION_TICKS = 20 * 10;

  /** One campfire recovery per person per minute. */
  public static final long CAMPFIRE_COOLDOWN_TICKS = 20L * 60L;

  /** Only residents already this close consider the campfire a recovery option. */
  public static final double CAMPFIRE_SEARCH_RANGE = 30.0D;

  private HealthRecoveryPolicy() {
  }

  /** Eating, retreating, and campfire recovery begin below one-third health. */
  public static boolean isBadlyHurt(float health, float maximumHealth) {
    return maximumHealth > 0.0F && health < maximumHealth / 3.0F;
  }

  /** Whether the persisted campfire cooldown has elapsed. */
  public static boolean isCampfireReady(long gameTime, long availableAt) {
    return gameTime >= availableAt;
  }

  /** The person needs help, carries no food, and is outside their cooldown. */
  public static boolean shouldSeekCampfire(float health, float maximumHealth, boolean hasMeal,
      long gameTime, long availableAt) {
    return isBadlyHurt(health, maximumHealth)
        && !hasMeal
        && isCampfireReady(gameTime, availableAt);
  }

  /** The first game tick at which another campfire recovery may begin. */
  public static long nextCampfireUse(long gameTime) {
    return gameTime + CAMPFIRE_COOLDOWN_TICKS;
  }
}
