package com.quzzar.kithkyn.entities.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quzzar.kithkyn.entities.AgeStage;

import org.junit.jupiter.api.Test;

class SelfDefensePolicyTest {

  @Test
  void toddlersAlwaysFlee() {
    assertFalse(SelfDefensePolicy.fightsBack(AgeStage.TODDLER, 0.5F, 0.5F));
  }

  @Test
  void onlyExceptionallyResoluteKidsFightBack() {
    assertFalse(SelfDefensePolicy.fightsBack(AgeStage.KID, 0.2F, 0.2F));
    assertTrue(SelfDefensePolicy.fightsBack(AgeStage.KID, 0.5F, 0.5F));
  }

  @Test
  void teenagersAndAdultsFollowTemperament() {
    assertTrue(SelfDefensePolicy.fightsBack(AgeStage.TEENAGER, 0.2F, 0.2F));
    assertFalse(SelfDefensePolicy.fightsBack(AgeStage.ADULT, -0.2F, -0.2F));
  }
}
