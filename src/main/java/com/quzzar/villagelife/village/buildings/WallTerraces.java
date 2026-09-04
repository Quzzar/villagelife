package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure terrain policy for turning noisy ground into long, walkable wall terraces. */
final class WallTerraces {

  /** A terrace holds this many route blocks before it is allowed to change height. */
  static final int RUN_LENGTH = 4;

  private WallTerraces() {
  }

  /**
   * Returns the y of the wall's top walking course at every route column.
   *
   * Each four-block run clears the highest ground under it. Neighbouring runs
   * differ by at most one block; steep hills therefore lift the runs before the
   * hill instead of producing an unclimbable cliff or copying every one-block
   * wrinkle in the terrain. The constraint is circular because a wall has no
   * privileged start or end.
   */
  static List<Integer> deckProfile(List<Integer> ground, int wallHeight) {
    if (ground.isEmpty()) {
      return List.of();
    }
    int runCount = Math.ceilDiv(ground.size(), RUN_LENGTH);
    List<Integer> runs = new ArrayList<>(runCount);
    for (int run = 0; run < runCount; run++) {
      int from = run * RUN_LENGTH;
      int to = Math.min(ground.size(), from + RUN_LENGTH);
      int highest = Collections.max(ground.subList(from, to));
      runs.add(highest + wallHeight - 1);
    }

    // Raising the lower side is intentional. Lowering the higher side would
    // bury the wall in a hillside and turn the outside terrain into a stair.
    boolean changed;
    do {
      changed = false;
      for (int i = 0; i < runs.size(); i++) {
        int next = (i + 1) % runs.size();
        int left = runs.get(i);
        int right = runs.get(next);
        if (left + 1 < right) {
          runs.set(i, right - 1);
          changed = true;
        } else if (right + 1 < left) {
          runs.set(next, left - 1);
          changed = true;
        }
      }
    } while (changed);

    List<Integer> deck = new ArrayList<>(ground.size());
    for (int i = 0; i < ground.size(); i++) {
      deck.add(runs.get(i / RUN_LENGTH));
    }
    return List.copyOf(deck);
  }
}
