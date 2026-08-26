package com.quzzar.villagelife.entities.genetics;

import net.minecraft.util.RandomSource;

/**
 * Rare heritable conditions. At most one per person, rolled once at stat
 * generation. Each condition's physical consequences are a fixed set of
 * attribute adjustments applied by {@link StatProjection} on top of the normal
 * stat-driven modifiers, so a giant is a giant regardless of their rolled Size
 * score (though the two stack: a high-Size giant is the tallest thing in the
 * village).
 */
public enum GeneticCondition {

  /** No condition. The overwhelmingly common case. */
  NONE(0.0F),

  /** Towering build: much larger, tougher, a little slower. */
  GIGANTISM(0.005F),

  /** Diminutive build: much smaller and frailer, quick on their feet. */
  DWARFISM(0.005F);

  private final float chance;

  private GeneticCondition(float chance) {
    this.chance = chance;
  }

  public float getChance() {
    return chance;
  }

  /**
   * Rolls a condition: each non-NONE condition gets an independent slice of
   * the probability space, checked in declaration order; the remainder is
   * NONE.
   */
  public static GeneticCondition roll(RandomSource random) {
    float roll = random.nextFloat();
    for (GeneticCondition condition : values()) {
      if (condition == NONE) {
        continue;
      }
      if (roll < condition.chance) {
        return condition;
      }
      roll -= condition.chance;
    }
    return NONE;
  }

  /** NBT-safe parse; unknown names (removed conditions) degrade to NONE. */
  public static GeneticCondition byName(String name) {
    for (GeneticCondition condition : values()) {
      if (condition.name().equals(name)) {
        return condition;
      }
    }
    return NONE;
  }
}
