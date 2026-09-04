package com.quzzar.kithkyn.village.buildings;

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
  void raisesApproachRunsWithoutBuildingTowersOverLowGround() {
    List<Integer> ground = List.of(
        60, 60, 60, 60,
        64, 64, 64, 64,
        68, 68, 68, 68,
        64, 64, 64, 64);
    List<Integer> deck = WallTerraces.deckProfile(ground, 5);

    for (int i = 0; i < ground.size(); i++) {
      assertTrue(deck.get(i) >= ground.get(i) + 4);
      assertTrue(deck.get(i) <= ground.get(i) + WallTerraces.MAX_RISE_ABOVE_LOCAL_GROUND);
      assertEquals(deck.get((i / WallTerraces.RUN_LENGTH) * WallTerraces.RUN_LENGTH), deck.get(i));
    }
  }

  @Test
  void waterlineSetsADeckFloorWithoutDiscardingTheSeabedProfile() {
    List<Integer> seabed = List.of(50, 50, 50, 50, 50, 50, 50, 50);
    List<Integer> defensiveSurface = List.of(63, 63, 63, 63, 50, 50, 50, 50);

    List<Integer> deck = WallTerraces.deckProfile(seabed, defensiveSurface, 3);

    assertEquals(List.of(65, 65, 65, 65, 55, 55, 55, 55), deck);
    assertEquals(List.of(50, 50, 50, 50, 50, 50, 50, 50), seabed);
  }

  @Test
  void neighboringWaterProtectsARecentlyClearedWallColumn() {
    int defensiveSurface = WallTerrain.defensiveSurfaceY(
        50, 10, 20, (x, z) -> x == 11 && z == 20 ? 63 : 50);

    assertEquals(63, defensiveSurface);
  }
}
