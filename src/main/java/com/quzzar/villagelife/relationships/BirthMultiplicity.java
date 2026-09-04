package com.quzzar.villagelife.relationships;

import java.util.random.RandomGenerator;

/** Number of siblings produced by one successful family-planning decision. */
public enum BirthMultiplicity {
  SINGLETON(1),
  TWINS(2),
  TRIPLETS(3);

  private final int children;

  BirthMultiplicity(int children) {
    this.children = children;
  }

  public int children() {
    return children;
  }

  /** Rolls triplets first because they occupy the rarer leading probability slice. */
  public static BirthMultiplicity roll(
      RandomGenerator random, double twinChance, double tripletChance) {
    return fromRoll(random.nextDouble(), twinChance, tripletChance);
  }

  static BirthMultiplicity fromRoll(double roll, double twinChance, double tripletChance) {
    double triplets = clampChance(tripletChance);
    double twins = Math.min(clampChance(twinChance), 1.0D - triplets);
    if (roll < triplets) {
      return TRIPLETS;
    }
    if (roll < triplets + twins) {
      return TWINS;
    }
    return SINGLETON;
  }

  private static double clampChance(double chance) {
    return Math.max(0.0D, Math.min(1.0D, chance));
  }
}
