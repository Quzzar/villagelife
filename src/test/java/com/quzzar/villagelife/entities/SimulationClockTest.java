package com.quzzar.villagelife.entities;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SimulationClockTest {

  @Test
  void aFutureTimestampIsNeverRecentAfterAClockRewind() {
    assertFalse(SimulationClock.isRecent(1_000L, 50_000L, 48_000L));
  }

  @Test
  void anEarlierTimestampOnTheSameTimelineCanBeRecent() {
    assertTrue(SimulationClock.isRecent(50_000L, 49_000L, 48_000L));
  }

  @Test
  void anUnsetPersistedTimestampIsNeverRecent() {
    assertFalse(SimulationClock.isRecent(1_000L, -1L, 48_000L));
  }
}
