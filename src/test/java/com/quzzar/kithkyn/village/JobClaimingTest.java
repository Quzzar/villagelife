package com.quzzar.kithkyn.village;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JobClaimingTest {

  @Test
  void aptitudeRebalancingOnlyRunsAroundMidnight() {
    assertFalse(JobClaiming.isMidnightRebalanceTime(12_000L));
    assertFalse(JobClaiming.isMidnightRebalanceTime(16_999L));
    assertTrue(JobClaiming.isMidnightRebalanceTime(17_000L));
    assertTrue(JobClaiming.isMidnightRebalanceTime(18_000L));
    assertTrue(JobClaiming.isMidnightRebalanceTime(18_999L));
    assertFalse(JobClaiming.isMidnightRebalanceTime(19_000L));
    assertTrue(JobClaiming.isMidnightRebalanceTime(41_000L));
  }
}
