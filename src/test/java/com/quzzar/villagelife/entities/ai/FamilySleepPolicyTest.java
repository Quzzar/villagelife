package com.quzzar.villagelife.entities.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quzzar.villagelife.entities.AgeStage;

import org.junit.jupiter.api.Test;

class FamilySleepPolicyTest {

  @Test
  void youngerChildrenRetireEarlier() {
    assertTrue(FamilySleepPolicy.shouldRest(AgeStage.TODDLER, 11_000L, false));
    assertFalse(FamilySleepPolicy.shouldRest(AgeStage.KID, 11_000L, false));
    assertFalse(FamilySleepPolicy.shouldRest(AgeStage.TEENAGER, 12_000L, false));
    assertTrue(FamilySleepPolicy.shouldRest(AgeStage.TEENAGER, 13_000L, true));
  }

  @Test
  void everyDependentWakesAtDawn() {
    assertTrue(FamilySleepPolicy.shouldWake(AgeStage.TODDLER, 0L, true));
    assertTrue(FamilySleepPolicy.shouldWake(AgeStage.KID, 0L, true));
    assertTrue(FamilySleepPolicy.shouldWake(AgeStage.TEENAGER, 0L, true));
    assertFalse(FamilySleepPolicy.shouldWake(AgeStage.TODDLER, 23_000L, false));
  }
}
