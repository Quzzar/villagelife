package com.quzzar.villagelife.village.buildings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class WallProgressTest {

  @Test
  void defersAnUnreachableSegmentAndRevisitsItAfterTheRestOfTheRing() {
    int ringSize = 3;
    WallProgress.State progress = new WallProgress.State(0, List.of());

    progress = WallProgress.defer(progress, ringSize);
    assertEquals(1, WallProgress.currentIndex(progress, ringSize));
    progress = WallProgress.advance(progress, ringSize);
    assertEquals(2, WallProgress.currentIndex(progress, ringSize));
    progress = WallProgress.advance(progress, ringSize);
    assertEquals(0, WallProgress.currentIndex(progress, ringSize));
    assertFalse(WallProgress.isComplete(progress, ringSize));

    progress = WallProgress.advance(progress, ringSize);
    assertTrue(WallProgress.isComplete(progress, ringSize));
  }
}
