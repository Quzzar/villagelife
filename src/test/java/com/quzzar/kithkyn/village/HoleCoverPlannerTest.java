package com.quzzar.kithkyn.village;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class HoleCoverPlannerTest {

  @Test
  void capsASmallDeepOpeningAtTheSurroundingSurface() {
    int width = 5;
    int depth = 5;
    int[] surface = filled(width * depth, 64);
    boolean[] coverable = filled(width * depth, true);
    surface[index(width, 2, 2)] = 40;

    int[] cover = HoleCoverPlanner.plan(width, depth, surface, coverable);

    assertEquals(64, cover[index(width, 2, 2)]);
    assertEquals(HoleCoverPlanner.NO_COVER, cover[index(width, 1, 2)]);
  }

  @Test
  void capsTheShallowEdgesOfADeepOpeningWithTheRestOfItsMouth() {
    int width = 6;
    int depth = 6;
    int[] surface = filled(width * depth, 64);
    boolean[] coverable = filled(width * depth, true);
    surface[index(width, 2, 2)] = 61;
    surface[index(width, 3, 2)] = 58;
    surface[index(width, 2, 3)] = 60;
    surface[index(width, 3, 3)] = 59;

    int[] cover = HoleCoverPlanner.plan(width, depth, surface, coverable);

    assertEquals(64, cover[index(width, 2, 2)]);
    assertEquals(64, cover[index(width, 3, 2)]);
    assertEquals(64, cover[index(width, 2, 3)]);
    assertEquals(64, cover[index(width, 3, 3)]);
  }

  @Test
  void leavesAShallowDepressionForOrdinaryGrading() {
    int width = 5;
    int depth = 5;
    int[] surface = filled(width * depth, 64);
    boolean[] coverable = filled(width * depth, true);
    surface[index(width, 2, 2)] = 61;

    int[] cover = HoleCoverPlanner.plan(width, depth, surface, coverable);

    assertEquals(HoleCoverPlanner.NO_COVER, cover[index(width, 2, 2)]);
  }

  @Test
  void leavesADeepValleyThatOpensOutOfTheSurvey() {
    int width = 7;
    int depth = 7;
    int[] surface = filled(width * depth, 64);
    boolean[] coverable = filled(width * depth, true);
    for (int z = 0; z <= 3; z++) {
      surface[index(width, 3, z)] = 40;
    }

    int[] cover = HoleCoverPlanner.plan(width, depth, surface, coverable);

    assertEquals(HoleCoverPlanner.NO_COVER, cover[index(width, 3, 3)]);
  }

  @Test
  void capsANarrowCaveEntranceThatDrainsDownhill() {
    int width = 9;
    int depth = 9;
    int[] surface = new int[width * depth];
    boolean[] coverable = filled(width * depth, true);
    for (int z = 0; z < depth; z++) {
      for (int x = 0; x < width; x++) {
        surface[index(width, x, z)] = 70 - z;
      }
    }
    surface[index(width, 4, 4)] = 50;
    surface[index(width, 4, 5)] = 55;
    surface[index(width, 4, 6)] = 61;
    surface[index(width, 4, 7)] = 62;

    int[] cover = HoleCoverPlanner.plan(width, depth, surface, coverable);

    assertEquals(66, cover[index(width, 4, 4)]);
    assertEquals(65, cover[index(width, 4, 5)]);
    assertEquals(HoleCoverPlanner.NO_COVER, cover[index(width, 4, 8)]);
  }

  @Test
  void leavesAnOpeningThatTouchesUnknownTerrain() {
    int width = 5;
    int depth = 5;
    int[] surface = filled(width * depth, 64);
    boolean[] coverable = filled(width * depth, true);
    surface[index(width, 2, 2)] = 40;
    surface[index(width, 2, 1)] = HoleCoverPlanner.NO_SURFACE;
    coverable[index(width, 2, 1)] = false;

    int[] cover = HoleCoverPlanner.plan(width, depth, surface, coverable);

    assertEquals(HoleCoverPlanner.NO_COVER, cover[index(width, 2, 2)]);
  }

  @Test
  void leavesALargeRavineAlone() {
    int width = 9;
    int depth = 9;
    int[] surface = filled(width * depth, 64);
    boolean[] coverable = filled(width * depth, true);
    for (int z = 2; z <= 6; z++) {
      for (int x = 2; x <= 6; x++) {
        surface[index(width, x, z)] = 40;
      }
    }

    int[] cover = HoleCoverPlanner.plan(width, depth, surface, coverable);

    assertEquals(HoleCoverPlanner.NO_COVER, cover[index(width, 4, 4)]);
  }

  @Test
  void leavesALongNarrowRavineAlone() {
    int width = 9;
    int depth = 9;
    int[] surface = filled(width * depth, 64);
    boolean[] coverable = filled(width * depth, true);
    for (int x = 1; x <= 7; x++) {
      surface[index(width, x, 4)] = 40;
    }

    int[] cover = HoleCoverPlanner.plan(width, depth, surface, coverable);

    assertEquals(HoleCoverPlanner.NO_COVER, cover[index(width, 4, 4)]);
  }

  private static int index(int width, int x, int z) {
    return z * width + x;
  }

  private static int[] filled(int length, int value) {
    int[] values = new int[length];
    Arrays.fill(values, value);
    return values;
  }

  private static boolean[] filled(int length, boolean value) {
    boolean[] values = new boolean[length];
    Arrays.fill(values, value);
    return values;
  }
}
