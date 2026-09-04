package com.quzzar.villagelife.entities;

import java.util.Locale;

/**
 * A person's physical and social stage of life.
 *
 * <p>The first two stages are children without work. A teenager is still
 * dependently housed with family, but may hold a real village job and therefore
 * presents their occupation rather than an age label. Adults need housing of
 * their own.
 */
public enum AgeStage {

  TODDLER(2.20F / 1.95F, false, true, "Toddler"),
  KID(2.80F / 1.95F, false, true, "Kid"),
  TEENAGER(3.40F / 1.95F, true, true, "Teenager"),
  ADULT(1.0F, true, false, "");

  private final float scale;
  private final boolean canWork;
  private final boolean dependentlyHoused;
  private final String ageLabel;

  private static final float PERSON_BASE_HEIGHT = 1.95F;
  private static final float PERSON_RENDER_SCALE = 0.9375F;
  private static final float MODEL_GROUND_OFFSET = 1.501F;
  private static final float HEAD_HALF_HEIGHT = 0.5F;
  private static final float YOUNG_MODEL_BASE_HEIGHT = 1.0F;
  private static final float PRE_ADULT_NAMEPLATE_LIFT = 0.10F;
  private static final float ADULT_RENDERED_TOP =
      PERSON_RENDER_SCALE * (MODEL_GROUND_OFFSET + HEAD_HALF_HEIGHT);
  private static final float ADULT_NAMEPLATE_CLEARANCE = PERSON_BASE_HEIGHT - ADULT_RENDERED_TOP;

  AgeStage(float scale, boolean canWork, boolean dependentlyHoused, String ageLabel) {
    this.scale = scale;
    this.canWork = canWork;
    this.dependentlyHoused = dependentlyHoused;
    this.ageLabel = ageLabel;
  }

  public float scale() {
    return scale;
  }

  /**
   * Scale used by collision, eye height, and entity attachments such as the nameplate.
   * The configured stage value expands Minecraft's nominal one-block young model; it is
   * not itself a world-space height. The attachment adds the same small model-to-bounds
   * clearance used by an adult instead of treating the stage multiplier as meters.
   */
  public float dimensionsScale() {
    if (this == ADULT) {
      return scale;
    }
    float relativeModelHeight = YOUNG_MODEL_BASE_HEIGHT * scale;
    return (relativeModelHeight + ADULT_NAMEPLATE_CLEARANCE) / PERSON_BASE_HEIGHT;
  }

  /** Presentation-only padding above pre-adult models for their full label stack. */
  public float nameplateLift() {
    return this == ADULT ? 0.0F : PRE_ADULT_NAMEPLATE_LIFT;
  }

  public boolean canWork() {
    return canWork;
  }

  public boolean isDependentlyHoused() {
    return dependentlyHoused;
  }

  /** Teenagers change into occupational clothing only once they actually work. */
  public boolean usesChildWardrobe(boolean employed) {
    return this == TODDLER || this == KID || (this == TEENAGER && !employed);
  }

  /** Toddler/kid labels always win; Teenager is the idle fallback label. */
  public String ageLabel() {
    return ageLabel;
  }

  public AgeStage next() {
    return switch (this) {
      case TODDLER -> KID;
      case KID -> TEENAGER;
      case TEENAGER, ADULT -> ADULT;
    };
  }

  /** NBT- and command-safe parse; unknown saved values become adults. */
  public static AgeStage byName(String name) {
    if (name == null || name.isBlank()) {
      return ADULT;
    }
    try {
      return valueOf(name.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      return ADULT;
    }
  }
}
