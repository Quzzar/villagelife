package com.quzzar.kithkyn.entities;

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

  TODDLER(2.05F / 1.95F, false, true, true, "Toddler"),
  // Young-model heads render at 75% scale, so 2.60 / 1.95 gives a Kid
  // the same absolute head size as an Adult while keeping child proportions.
  KID(2.60F / 1.95F, false, true, true, "Kid"),
  TEENAGER(0.9275F, true, true, false, "Teenager"),
  ADULT(1.0F, true, false, false, "");

  private final float scale;
  private final boolean canWork;
  private final boolean dependentlyHoused;
  private final boolean usesYoungModel;
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

  AgeStage(
      float scale,
      boolean canWork,
      boolean dependentlyHoused,
      boolean usesYoungModel,
      String ageLabel) {
    this.scale = scale;
    this.canWork = canWork;
    this.dependentlyHoused = dependentlyHoused;
    this.usesYoungModel = usesYoungModel;
    this.ageLabel = ageLabel;
  }

  public float scale() {
    return scale;
  }

  /**
   * Scale used by collision, eye height, and entity attachments such as the nameplate.
   * Toddler and Kid expand Minecraft's nominal one-block young model. Teenager and Adult
   * use the normal adult model, so their model height scales with the adult render height.
   * Every stage keeps the same small model-to-bounds clearance.
   */
  public float dimensionsScale() {
    if (!usesYoungModel) {
      float renderedModelHeight = ADULT_RENDERED_TOP * scale;
      return (renderedModelHeight + ADULT_NAMEPLATE_CLEARANCE) / PERSON_BASE_HEIGHT;
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

  /** Whether Minecraft should apply its young-player body proportions. */
  public boolean usesYoungModel() {
    return usesYoungModel;
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
