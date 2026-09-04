package com.quzzar.kithkyn.entities.genetics;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Rare heritable conditions. At most one per person, rolled once at stat
 * generation. Mechanical conditions project through {@link StatProjection};
 * visual conditions can instead affect the derived appearance recipe. A giant
 * remains a giant regardless of their rolled Size score (though the two stack:
 * a high-Size giant is the tallest thing in the village).
 */
public enum GeneticCondition {

  /** No condition. The overwhelmingly common case. */
  NONE(0.0F),

  /** Towering build: much larger, tougher, a little slower. */
  GIGANTISM(0.01F),

  /** Diminutive build: much smaller and frailer, quick on their feet. */
  DWARFISM(0.01F),

  /** Two differently colored irises, with no mechanical stat effect. */
  HETEROCHROMIA(0.01F);

  private static final float DOMINANT_ONE_PARENT_CHANCE = 0.50F;
  private static final float DOMINANT_TWO_PARENT_CHANCE = 0.75F;
  private static final float ACHONDROPLASIA_TWO_PARENT_LIVE_BIRTH_CHANCE = 2.0F / 3.0F;
  private static final float AIP_ONE_PARENT_EXPRESSION_CHANCE = 0.125F;
  private static final float AIP_TWO_PARENT_EXPRESSION_CHANCE = 0.1875F;

  private final float chance;

  private GeneticCondition(float chance) {
    this.chance = chance;
  }

  public float getChance() {
    return chance;
  }

  public boolean changesBodySize() {
    return this == GIGANTISM || this == DWARFISM;
  }

  /**
   * Rolls a condition: each non-NONE condition gets an independent slice of
   * the probability space, checked in declaration order; the remainder is
   * NONE.
   */
  public static GeneticCondition roll(RandomGenerator random) {
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

  /**
   * Rolls the conditions expressed by a child from phenotype-level parental
   * data. The retained save format supports one condition, so independently
   * expressed collisions resolve to one uniformly selected condition.
   */
  public static GeneticCondition rollChild(
      GeneticCondition firstParent, GeneticCondition secondParent, RandomGenerator random) {
    List<GeneticCondition> expressed = new ArrayList<>(3);
    for (GeneticCondition condition : values()) {
      if (condition != NONE
          && random.nextFloat() < condition.childChance(firstParent, secondParent)) {
        expressed.add(condition);
      }
    }
    return expressed.isEmpty() ? NONE : expressed.get(random.nextInt(expressed.size()));
  }

  /** The documented clinical model's phenotype chance for a spawned child. */
  float childChance(GeneticCondition firstParent, GeneticCondition secondParent) {
    if (this == NONE) {
      return 0.0F;
    }
    int affectedParents = (firstParent == this ? 1 : 0) + (secondParent == this ? 1 : 0);
    if (affectedParents == 0) {
      return chance;
    }
    return switch (this) {
      case DWARFISM -> affectedParents == 1
          ? DOMINANT_ONE_PARENT_CHANCE
          : ACHONDROPLASIA_TWO_PARENT_LIVE_BIRTH_CHANCE;
      case GIGANTISM -> affectedParents == 1
          ? AIP_ONE_PARENT_EXPRESSION_CHANCE
          : AIP_TWO_PARENT_EXPRESSION_CHANCE;
      case HETEROCHROMIA -> affectedParents == 1
          ? DOMINANT_ONE_PARENT_CHANCE
          : DOMINANT_TWO_PARENT_CHANCE;
      case NONE -> 0.0F;
    };
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
