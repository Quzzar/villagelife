package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/** Pure policy for deciding which heights in a planned wall column still need filling. */
final class WallOccupancy {

  private WallOccupancy() {
  }

  /** Whether the current block closes this cell without an explicit tier replacement. */
  static boolean isSatisfied(boolean hasCollision, boolean replacesPreviousTier) {
    return hasCollision && !replacesPreviousTier;
  }

  /** Returns only the planned heights that are not already satisfied by a barrier. */
  static List<Integer> missingHeights(int bottom, int top, IntPredicate isSatisfied) {
    List<Integer> missing = new ArrayList<>();
    for (int y = bottom; y <= top; y++) {
      if (!isSatisfied.test(y)) {
        missing.add(y);
      }
    }
    return missing;
  }
}
