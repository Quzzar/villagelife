package com.quzzar.villagelife.village.buildings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class WallTerracesTest {

  @Test
  void ignoresSmallTerrainNoiseWithinOneTerrace() {
    List<Integer> deck = WallTerraces.deckProfile(
        List.of(70, 71, 70, 71, 70, 71, 70, 71), 5);

    assertEquals(List.of(75, 75, 75, 75, 75, 75, 75, 75), deck);
  }

  @Test
  void raisesApproachRunsBeforeACliffAndNeverBuriesTheWall() {
    List<Integer> ground = List.of(
        60, 60, 60, 60,
        64, 64, 64, 64,
        68, 68, 68, 68,
        64, 64, 64, 64);
    List<Integer> deck = WallTerraces.deckProfile(ground, 5);

    for (int i = 0; i < ground.size(); i++) {
      assertTrue(deck.get(i) >= ground.get(i) + 4);
      assertEquals(deck.get((i / WallTerraces.RUN_LENGTH) * WallTerraces.RUN_LENGTH), deck.get(i));
    }
    for (int run = 0; run < ground.size() / WallTerraces.RUN_LENGTH; run++) {
      int next = (run + 1) % (ground.size() / WallTerraces.RUN_LENGTH);
      int left = deck.get(run * WallTerraces.RUN_LENGTH);
      int right = deck.get(next * WallTerraces.RUN_LENGTH);
      assertTrue(Math.abs(left - right) <= 1);
    }
  }
}
