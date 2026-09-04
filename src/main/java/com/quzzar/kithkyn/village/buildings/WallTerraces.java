package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure terrain policy for turning noisy ground into long, walkable wall terraces. */
final class WallTerraces {

  /** A terrace holds this many route blocks before it is allowed to change height. */
  static final int RUN_LENGTH = 4;
  /** A steep neighbor may not pull a deck more than this far above its own ground. */
  static final int MAX_RISE_ABOVE_LOCAL_GROUND = 5;

  private WallTerraces() {
  }

  /**
   * Returns the y of the wall's top walking course at every route column.
   *
   * Each four-block run clears the highest ground under it. Neighboring runs
   * smooth toward one-block steps while the local height cap permits it. At a
   * cliff, keeping the wall close to the ground wins over lifting the entire
   * approach. The constraint is circular because a wall has no privileged
   * start or end.
   */
  static List<Integer> deckProfile(List<Integer> ground, int wallHeight) {
    if (ground.isEmpty()) {
      return List.of();
    }
    int runCount = Math.ceilDiv(ground.size(), RUN_LENGTH);
    List<Integer> runs = new ArrayList<>(runCount);
    List<Integer> runCaps = new ArrayList<>(runCount);
    for (int run = 0; run < runCount; run++) {
      int from = run * RUN_LENGTH;
      int to = Math.min(ground.size(), from + RUN_LENGTH);
      int highest = Collections.max(ground.subList(from, to));
      int lowest = Collections.min(ground.subList(from, to));
      int cap = lowest + MAX_RISE_ABOVE_LOCAL_GROUND;
      runCaps.add(cap);
      runs.add(Math.min(highest + wallHeight - 1, cap));
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
          int raised = Math.min(right - 1, runCaps.get(i));
          if (raised > left) {
            runs.set(i, raised);
            changed = true;
          }
        } else if (right + 1 < left) {
          int raised = Math.min(left - 1, runCaps.get(next));
          if (raised > right) {
            runs.set(next, raised);
            changed = true;
          }
        }
      }
    } while (changed);

    List<Integer> deck = new ArrayList<>(ground.size());
    for (int i = 0; i < ground.size(); i++) {
      int minimumVisibleDeck = ground.get(i) + wallHeight - 1;
      int cappedDeck = Math.min(runs.get(i / RUN_LENGTH),
          ground.get(i) + MAX_RISE_ABOVE_LOCAL_GROUND);
      deck.add(Math.max(minimumVisibleDeck, cappedDeck));
    }
    return List.copyOf(deck);
  }

  /**
   * Plans against the higher of natural ground and a defensive surface such as
   * open water, while callers retain the natural profile for foundations.
   */
  static List<Integer> deckProfile(List<Integer> ground,
      List<Integer> defensiveSurface, int wallHeight) {
    if (ground.size() != defensiveSurface.size()) {
      throw new IllegalArgumentException(
          "Ground and defensive surface profiles must have the same length");
    }
    List<Integer> effectiveSurface = new ArrayList<>(ground.size());
    for (int index = 0; index < ground.size(); index++) {
      effectiveSurface.add(Math.max(ground.get(index), defensiveSurface.get(index)));
    }
    return deckProfile(effectiveSurface, wallHeight);
  }
}
