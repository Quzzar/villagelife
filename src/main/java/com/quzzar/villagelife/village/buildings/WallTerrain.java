package com.quzzar.villagelife.village.buildings;

import java.util.function.IntPredicate;

/** Pure terrain-height policy shared by wall sampling and its regression tests. */
final class WallTerrain {

  private WallTerrain() {
  }

  /** Walks down from a heightmap result until a genuine supporting surface is found. */
  static int surfaceY(int top, int minimum, IntPredicate sturdyTerrainAt) {
    int surface = top;
    while (surface > minimum && !sturdyTerrainAt.test(surface - 1)) {
      surface--;
    }
    return surface;
  }
}
