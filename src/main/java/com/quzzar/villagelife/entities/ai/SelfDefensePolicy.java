package com.quzzar.villagelife.entities.ai;

import com.quzzar.villagelife.entities.AgeStage;

/** Personality and age decide whether a person retaliates against an attacker. */
public final class SelfDefensePolicy {

  private SelfDefensePolicy() {
  }

  public static boolean fightsBack(AgeStage stage, float aggression, float protectSelf) {
    double resolve = aggression * 0.4D + protectSelf * 0.6D;
    return switch (stage) {
      case TODDLER -> false;
      case KID -> resolve > 0.30D;
      case TEENAGER, ADULT -> resolve > 0.0D;
    };
  }
}
