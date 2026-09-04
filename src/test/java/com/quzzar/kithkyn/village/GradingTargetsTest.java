package com.quzzar.kithkyn.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GradingTargetsTest {

  private static final int WIDTH = 10;

  @Test
  void smoothingDoesNotCreateANewInteriorPitByCutting() {
    int[] original = pitSurface();
    boolean[] fixed = standardFixed();

    int[] target = GradingTargets.plan(WIDTH, WIDTH, original, original,
        fixed, emptyFlags(), emptyFlags());

    int pit = index(1, 5);
    boolean wasPit = lowerThanAllNeighbours(original, pit);
    boolean becomesPit = target[pit] < original[pit] && lowerThanAllNeighbours(target, pit);
    assertFalse(becomesPit && !wasPit,
        () -> "grading cut interior column (1,5) from " + original[pit] + " to " + target[pit]
            + " and created a new local pit");
  }

  @Test
  void midpointTiesPreferFillOverCutOnTheReproducedSurface() {
    int[] original = pitSurface();
    int[] target = GradingTargets.plan(WIDTH, WIDTH, original, original,
        standardFixed(), emptyFlags(), emptyFlags());

    int cuts = 0;
    int fills = 0;
    for (int i = 0; i < original.length; i++) {
      cuts += Math.max(0, original[i] - target[i]);
      fills += Math.max(0, target[i] - original[i]);
    }

    assertTrue(fills > cuts, "expected fill-biased work but planned " + cuts
        + " cut blocks and " + fills + " fill blocks");
  }

  @Test
  void partialExecutionCannotRatchetATargetDownwardOnReplan() {
    int[] original = ratchetSurface();
    int[] current = original.clone();
    boolean[] fixed = standardFixed();
    int column = index(7, 5);

    int firstTarget = GradingTargets.plan(WIDTH, WIDTH, current, original,
        fixed, emptyFlags(), emptyFlags())[column];
    current[column]--;
    int replannedTarget = GradingTargets.plan(WIDTH, WIDTH, current, original,
        fixed, emptyFlags(), emptyFlags())[column];

    assertEquals(firstTarget, replannedTarget);
  }

  @Test
  void pathRepairFillsADeepStepBeforeCuttingItsBanks() {
    int[] original = {65, 65, 62, 65, 65};
    boolean[] fixed = {true, false, false, false, true};
    boolean[] path = {false, true, true, true, false};

    int[] target = GradingTargets.plan(5, 1, original, original,
        fixed, new boolean[5], path);

    assertEquals(65, target[0]);
    assertEquals(65, target[1]);
    assertEquals(65, target[2]);
    assertEquals(65, target[3]);
    assertEquals(65, target[4]);
  }

  private static int[] pitSurface() {
    return new int[] {
        64, 63, 63, 65, 65, 65, 65, 66, 66, 66,
        64, 64, 64, 63, 65, 66, 65, 64, 65, 65,
        64, 64, 66, 66, 66, 65, 64, 65, 65, 67,
        64, 64, 66, 66, 66, 66, 65, 66, 66, 66,
        65, 63, 66, 66, 66, 66, 65, 65, 66, 66,
        66, 65, 66, 65, 67, 65, 65, 65, 67, 66,
        66, 65, 66, 65, 67, 66, 65, 65, 65, 68,
        65, 65, 66, 65, 66, 66, 65, 65, 65, 67,
        65, 65, 66, 65, 67, 65, 65, 65, 65, 66,
        64, 65, 65, 64, 66, 66, 65, 66, 66, 68
    };
  }

  private static int[] ratchetSurface() {
    return new int[] {
        65, 64, 64, 65, 64, 65, 65, 64, 66, 65,
        64, 64, 64, 64, 66, 64, 66, 65, 65, 66,
        64, 64, 66, 66, 66, 64, 65, 66, 67, 66,
        64, 64, 66, 66, 66, 65, 65, 65, 66, 66,
        64, 65, 66, 66, 66, 65, 65, 64, 66, 66,
        65, 65, 65, 64, 66, 66, 67, 67, 66, 68,
        65, 64, 65, 65, 66, 66, 65, 65, 65, 67,
        65, 64, 65, 64, 66, 66, 65, 65, 65, 67,
        65, 66, 64, 65, 65, 66, 65, 65, 65, 67,
        65, 65, 66, 65, 67, 66, 66, 65, 68, 67
    };
  }

  private static boolean[] standardFixed() {
    boolean[] fixed = emptyFlags();
    for (int z = 2; z <= 4; z++) {
      for (int x = 2; x <= 4; x++) {
        fixed[index(x, z)] = true;
      }
    }
    for (int z = 6; z <= 8; z++) {
      for (int x = 6; x <= 8; x++) {
        fixed[index(x, z)] = true;
      }
    }
    return fixed;
  }

  private static boolean[] emptyFlags() {
    return new boolean[WIDTH * WIDTH];
  }

  private static boolean lowerThanAllNeighbours(int[] surface, int at) {
    return surface[at] < surface[at - 1]
        && surface[at] < surface[at + 1]
        && surface[at] < surface[at - WIDTH]
        && surface[at] < surface[at + WIDTH];
  }

  private static int index(int x, int z) {
    return z * WIDTH + x;
  }
}
