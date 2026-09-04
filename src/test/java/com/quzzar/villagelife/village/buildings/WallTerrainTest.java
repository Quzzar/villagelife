package com.quzzar.villagelife.village.buildings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

class WallTerrainTest {

  @Test
  void readsTheTerrainBelowAStandingTree() {
    Set<Integer> sturdyTerrain = Set.of(70);

    assertEquals(71, WallTerrain.surfaceY(78, -64, sturdyTerrain::contains));
  }
}
