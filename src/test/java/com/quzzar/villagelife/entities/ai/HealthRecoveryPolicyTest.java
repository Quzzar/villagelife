package com.quzzar.villagelife.entities.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HealthRecoveryPolicyTest {

  @Test
  void badlyHurtMeansBelowOneThirdHealth() {
    assertTrue(HealthRecoveryPolicy.isBadlyHurt(5.0F, 18.0F));
    assertFalse(HealthRecoveryPolicy.isBadlyHurt(6.0F, 18.0F));
    assertFalse(HealthRecoveryPolicy.isBadlyHurt(0.0F, 0.0F));
  }

  @Test
  void campfireRecoveryReturnsAfterOneMinute() {
    long usedAt = 4_000L;
    long availableAt = HealthRecoveryPolicy.nextCampfireUse(usedAt);

    assertEquals(5_200L, availableAt);
    assertFalse(HealthRecoveryPolicy.isCampfireReady(5_199L, availableAt));
    assertTrue(HealthRecoveryPolicy.isCampfireReady(5_200L, availableAt));
    assertEquals(200, HealthRecoveryPolicy.CAMPFIRE_REGENERATION_TICKS);
    assertEquals(30.0D, HealthRecoveryPolicy.CAMPFIRE_SEARCH_RANGE);
  }

  @Test
  void foodAlwaysWinsOverCampfireRecovery() {
    assertFalse(HealthRecoveryPolicy.shouldSeekCampfire(5.0F, 18.0F, true, 1_200L, 1_200L));
    assertTrue(HealthRecoveryPolicy.shouldSeekCampfire(5.0F, 18.0F, false, 1_200L, 1_200L));
  }
}
