package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

/** Chooses a nearby work position on the village side of a wall segment. */
final class WallWorkPlanner {

  /** Horizontal reach around one wall column. */
  private static final int MAXIMUM_OFFSET = 2;

  /** One horizontal offset from the wall column. */
  record Offset(int x, int z) {

    private int distanceSqr() {
      return x * x + z * z;
    }

    private int inwardProgress(int inwardX, int inwardZ) {
      return x * inwardX + z * inwardZ;
    }
  }

  private WallWorkPlanner() {
  }

  /**
   * Returns the nearest usable offset that moves toward the village, or null
   * when none of the positions within arm's length can be used.
   */
  @Nullable
  static Offset choose(int inwardX, int inwardZ, Predicate<Offset> usable) {
    List<Offset> candidates = new ArrayList<>();
    for (int x = -MAXIMUM_OFFSET; x <= MAXIMUM_OFFSET; x++) {
      for (int z = -MAXIMUM_OFFSET; z <= MAXIMUM_OFFSET; z++) {
        Offset offset = new Offset(x, z);
        int distanceSqr = offset.distanceSqr();
        if (distanceSqr == 0 || distanceSqr > MAXIMUM_OFFSET * MAXIMUM_OFFSET
            || offset.inwardProgress(inwardX, inwardZ) <= 0) {
          continue;
        }
        candidates.add(offset);
      }
    }
    candidates.sort(Comparator.comparingInt(Offset::distanceSqr)
        .thenComparing(Comparator.comparingInt(
            (Offset offset) -> offset.inwardProgress(inwardX, inwardZ)).reversed())
        .thenComparingInt(Offset::x)
        .thenComparingInt(Offset::z));
    for (Offset candidate : candidates) {
      if (usable.test(candidate)) {
        return candidate;
      }
    }
    return null;
  }
}
