package com.quzzar.kithkyn.village.buildings;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import java.util.function.IntPredicate;

import net.minecraft.core.BlockPos;

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

  /**
   * Uses the highest waterline touching a wall column. The neighborhood covers
   * water that flows back into a column immediately after an older wall is removed.
   */
  static int defensiveSurfaceY(int naturalGround, int x, int z,
      IntBinaryOperator waterSurfaceAt) {
    int defensiveSurface = naturalGround;
    for (int offsetX = -1; offsetX <= 1; offsetX++) {
      for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
        defensiveSurface = Math.max(defensiveSurface,
            waterSurfaceAt.applyAsInt(x + offsetX, z + offsetZ));
      }
    }
    return defensiveSurface;
  }

  /** Exposed natural soil is replaced once so the wall reads as embedded in the bank. */
  static boolean shouldEmbedSurface(boolean isNaturalSoil, boolean isOwned,
      boolean hasExposedSide) {
    return isNaturalSoil && !isOwned && hasExposedSide;
  }

  /** One horizontal block around a footprint, excluding the footprint itself. */
  static Set<Long> horizontalBuffer(Set<Long> occupiedColumns) {
    Set<Long> buffer = new HashSet<>(horizontalReach(occupiedColumns, 1));
    buffer.removeAll(occupiedColumns);
    return Set.copyOf(buffer);
  }

  /** Every column within the given horizontal radius, including the footprint. */
  static Set<Long> horizontalReach(Set<Long> occupiedColumns, int radius) {
    if (radius < 0) {
      throw new IllegalArgumentException("Horizontal radius cannot be negative");
    }
    Set<Long> reach = new HashSet<>();
    for (long column : occupiedColumns) {
      int x = BlockPos.getX(column);
      int z = BlockPos.getZ(column);
      for (int offsetX = -radius; offsetX <= radius; offsetX++) {
        for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
          reach.add(BlockPos.asLong(x + offsetX, 0, z + offsetZ));
        }
      }
    }
    return Set.copyOf(reach);
  }
}
