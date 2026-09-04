package com.quzzar.kithkyn.village;

import java.util.function.Predicate;

import javax.annotation.Nullable;

/** The exact world-space box in which a workplace may alter its own blocks. */
public record WorkArea(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

  /** A world coordinate without a dependency on Minecraft's position classes. */
  public record Position(int x, int y, int z) {
  }

  public boolean contains(int x, int y, int z) {
    return x >= minX && x <= maxX
        && y >= minY && y <= maxY
        && z >= minZ && z <= maxZ;
  }

  /**
   * The first matching cell in a station-centred square, clipped to this work
   * area. Tilling uses this directly so the edge gate is covered independently
   * of a Minecraft level.
   */
  @Nullable
  public Position firstInSquare(int centerX, int y, int centerZ, int radius,
      Predicate<Position> matches) {
    for (int x = -radius; x <= radius; x++) {
      for (int z = -radius; z <= radius; z++) {
        Position candidate = new Position(centerX + x, y, centerZ + z);
        if (contains(candidate.x(), candidate.y(), candidate.z()) && matches.test(candidate)) {
          return candidate;
        }
      }
    }
    return null;
  }
}
