package com.quzzar.kithkyn.village;

import java.util.Arrays;

import com.quzzar.kithkyn.village.buildings.SitePreparation;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Pure target-height solver for one village grading survey.
 *
 * <p>The current surface says which columns exist and which work remains. The
 * reference surface is the terrain before grading touched it. Targets are
 * derived from that stable reference, so pausing after one block and planning
 * again cannot walk the target farther uphill or downhill.
 */
final class GradingTargets {

  /** Diffusion passes used to relax a worn path toward an even ramp. */
  private static final int PATH_SMOOTH_PASSES = 32;

  /** The four cardinal neighbours used by every surface pass. */
  private static final int[][] NEIGHBOURS = { {-1, 0}, {1, 0}, {0, -1}, {0, 1} };

  /** An envelope value no source reaches. */
  private static final int FAR = Integer.MAX_VALUE / 2;

  private final int width;
  private final int depth;
  private final int[] current;
  private final int[] reference;
  private final int[] shape;
  private final boolean[] fixed;
  private final boolean[] apron;
  private final boolean[] path;

  private GradingTargets(int width, int depth, int[] current, int[] reference,
      boolean[] fixed, boolean[] apron, boolean[] path) {
    int columns = Math.multiplyExact(width, depth);
    if (width <= 0 || depth <= 0 || current.length != columns || reference.length != columns
        || fixed.length != columns || apron.length != columns || path.length != columns) {
      throw new IllegalArgumentException("grading arrays must match a positive width and depth");
    }
    this.width = width;
    this.depth = depth;
    this.current = current;
    this.reference = reference;
    this.fixed = fixed;
    this.apron = apron;
    this.path = path;
    this.shape = new int[columns];
    for (int i = 0; i < columns; i++) {
      this.shape[i] = fixed[i] ? current[i] : reference[i];
    }
  }

  /** Returns the stable, fill-biased target for every supplied column. */
  static int[] plan(int width, int depth, int[] current, int[] reference,
      boolean[] fixed, boolean[] apron, boolean[] path) {
    return new GradingTargets(width, depth, current, reference, fixed, apron, path).plan();
  }

  private int[] plan() {
    int columns = current.length;
    boolean[] ground = new boolean[columns];
    boolean[] anchors = new boolean[columns];
    int[] negated = new int[columns];
    for (int i = 0; i < columns; i++) {
      ground[i] = current[i] != HoleCoverPlanner.NO_SURFACE;
      anchors[i] = ground[i] && fixed[i];
      negated[i] = ground[i] ? -shape[i] : 0;
    }

    int[] under = lowerEnvelope(shape, ground);
    int[] overNegated = lowerEnvelope(negated, ground);
    int[] ceiling = lowerEnvelope(shape, anchors);
    int[] floorNegated = lowerEnvelope(negated, anchors);
    int[] target = new int[columns];

    for (int i = 0; i < columns; i++) {
      if (!ground[i] || fixed[i]) {
        target[i] = current[i];
        continue;
      }
      int middle = upperHalf(under[i] - overNegated[i]);
      int floor = floorNegated[i] == FAR ? -FAR : -floorNegated[i];
      target[i] = floor <= ceiling[i]
          ? Math.max(floor, Math.min(ceiling[i], middle))
          : middle;
    }

    smoothPaths(target);
    preventNewCutBasins(target, ceiling);
    return target;
  }

  /** Half of an integer, resolving an exact half upward rather than downward. */
  private static int upperHalf(int value) {
    return Math.floorDiv(value, 2) + Math.floorMod(value, 2);
  }

  /**
   * Relaxes path columns, then makes the final walkability correction by
   * raising the low side first. Cutting the high side remains a bounded
   * fallback when the low column has reached its three-block allowance.
   */
  private void smoothPaths(int[] target) {
    if (!hasPath()) {
      return;
    }

    int delta = SitePreparation.MAX_COLUMN_DELTA;
    int[] smooth = shape.clone();
    for (int pass = 0; pass < PATH_SMOOTH_PASSES; pass++) {
      int[] next = smooth.clone();
      for (int i = 0; i < smooth.length; i++) {
        if (!path[i]) {
          continue;
        }
        int x = i % width;
        int z = i / width;
        int sum = 0;
        int count = 0;
        for (int[] step : NEIGHBOURS) {
          int neighbour = neighbour(x, z, step);
          if (neighbour < 0 || current[neighbour] == HoleCoverPlanner.NO_SURFACE
              || !(path[neighbour] || fixed[neighbour]) || !connected(i, neighbour)) {
            continue;
          }
          sum += smooth[neighbour];
          count++;
        }
        if (count > 0) {
          int aimed = roundedAverageUp(sum, count);
          next[i] = Math.max(reference[i] - delta, Math.min(reference[i] + delta, aimed));
        }
      }
      smooth = next;
    }

    smooth = raiseLowPathSteps(smooth, delta);
    lowerUnavoidablePathSteps(smooth, delta);
    for (int i = 0; i < smooth.length; i++) {
      if (path[i] && current[i] != HoleCoverPlanner.NO_SURFACE) {
        target[i] = smooth[i];
      }
    }
  }

  private boolean hasPath() {
    for (boolean isPath : path) {
      if (isPath) {
        return true;
      }
    }
    return false;
  }

  private static int roundedAverageUp(int sum, int count) {
    int floor = Math.floorDiv(sum, count);
    return Math.floorMod(sum, count) * 2 >= count ? floor + 1 : floor;
  }

  /** Raises low path cells until every step it can repair with fill is walkable. */
  private int[] raiseLowPathSteps(int[] smooth, int delta) {
    for (int guard = 0; guard <= width + depth; guard++) {
      boolean changed = false;
      for (int i = 0; i < smooth.length; i++) {
        if (!path[i]) {
          continue;
        }
        int x = i % width;
        int z = i / width;
        for (int[] step : NEIGHBOURS) {
          int neighbour = neighbour(x, z, step);
          if (neighbour < 0 || !path[neighbour] || !connected(i, neighbour)
              || smooth[i] >= smooth[neighbour] - 1) {
            continue;
          }
          int raised = Math.min(smooth[neighbour] - 1, reference[i] + delta);
          if (raised > smooth[i]) {
            smooth[i] = raised;
            changed = true;
          }
        }
      }
      if (!changed) {
        break;
      }
    }
    return smooth;
  }

  /** Lowers only a high side whose neighbour could not be raised far enough. */
  private void lowerUnavoidablePathSteps(int[] smooth, int delta) {
    for (int guard = 0; guard <= width + depth; guard++) {
      boolean changed = false;
      for (int i = 0; i < smooth.length; i++) {
        if (!path[i]) {
          continue;
        }
        int x = i % width;
        int z = i / width;
        for (int[] step : NEIGHBOURS) {
          int neighbour = neighbour(x, z, step);
          if (neighbour < 0 || !path[neighbour] || !connected(i, neighbour)
              || smooth[i] <= smooth[neighbour] + 1) {
            continue;
          }
          int lowered = Math.max(smooth[neighbour] + 1, reference[i] - delta);
          if (lowered < smooth[i]) {
            smooth[i] = lowered;
            changed = true;
          }
        }
      }
      if (!changed) {
        return;
      }
    }
  }

  /**
   * A cut may remove a bump, but may not turn ordinary ground into a strict
   * local minimum. Raising such a target reduces every neighbouring step, so
   * it preserves the walkability constraint while removing the new pit.
   */
  private void preventNewCutBasins(int[] target, int[] ceiling) {
    for (int z = 1; z < depth - 1; z++) {
      for (int x = 1; x < width - 1; x++) {
        int i = z * width + x;
        if (fixed[i] || target[i] >= reference[i]) {
          continue;
        }
        int lowestNeighbour = Integer.MAX_VALUE;
        boolean targetIsPit = true;
        boolean referenceWasPit = true;
        for (int[] step : NEIGHBOURS) {
          int neighbour = neighbour(x, z, step);
          if (current[neighbour] == HoleCoverPlanner.NO_SURFACE || !connected(i, neighbour)) {
            targetIsPit = false;
            break;
          }
          lowestNeighbour = Math.min(lowestNeighbour, target[neighbour]);
          targetIsPit &= target[i] < target[neighbour];
          referenceWasPit &= reference[i] < reference[neighbour];
        }
        if (targetIsPit && !referenceWasPit) {
          int upperBound = apron[i]
              ? ceiling[i]
              : Math.min(ceiling[i], reference[i] + SitePreparation.MAX_COLUMN_DELTA);
          target[i] = Math.max(target[i], Math.min(lowestNeighbour, upperBound));
        }
      }
    }
  }

  /** The lowest one-step surface at or below the supplied sources. */
  private int[] lowerEnvelope(int[] value, boolean[] isSource) {
    int columns = current.length;
    int[] out = new int[columns];
    int low = Integer.MAX_VALUE;
    int high = Integer.MIN_VALUE;
    Arrays.fill(out, FAR);
    for (int i = 0; i < columns; i++) {
      if (isSource[i]) {
        low = Math.min(low, value[i]);
        high = Math.max(high, value[i]);
      }
    }
    if (low == Integer.MAX_VALUE) {
      return out;
    }

    IntArrayList[] buckets = new IntArrayList[high - low + 1 + columns];
    for (int i = 0; i < columns; i++) {
      if (isSource[i]) {
        out[i] = value[i];
        enqueue(buckets, value[i] - low, i);
      }
    }
    for (int slot = 0; slot < buckets.length; slot++) {
      IntArrayList bucket = buckets[slot];
      if (bucket == null) {
        continue;
      }
      for (int entry = 0; entry < bucket.size(); entry++) {
        int i = bucket.getInt(entry);
        if (out[i] != slot + low) {
          continue;
        }
        int x = i % width;
        int z = i / width;
        relax(out, buckets, low, i, x - 1, z);
        relax(out, buckets, low, i, x + 1, z);
        relax(out, buckets, low, i, x, z - 1);
        relax(out, buckets, low, i, x, z + 1);
      }
    }
    return out;
  }

  private void relax(int[] out, IntArrayList[] buckets, int low, int from, int x, int z) {
    if (x < 0 || z < 0 || x >= width || z >= depth) {
      return;
    }
    int to = z * width + x;
    if (!connected(from, to)) {
      return;
    }
    int candidate = out[from] + 1;
    if (candidate < out[to]) {
      out[to] = candidate;
      enqueue(buckets, candidate - low, to);
    }
  }

  private static void enqueue(IntArrayList[] buckets, int slot, int column) {
    if (buckets[slot] == null) {
      buckets[slot] = new IntArrayList();
    }
    buckets[slot].add(column);
  }

  private int neighbour(int x, int z, int[] step) {
    int neighbourX = x + step[0];
    int neighbourZ = z + step[1];
    if (neighbourX < 0 || neighbourZ < 0 || neighbourX >= width || neighbourZ >= depth) {
      return -1;
    }
    return neighbourZ * width + neighbourX;
  }

  private boolean connected(int first, int second) {
    if (current[first] == HoleCoverPlanner.NO_SURFACE
        || current[second] == HoleCoverPlanner.NO_SURFACE) {
      return false;
    }
    return apron[first] || apron[second]
        || Math.abs(shape[first] - shape[second]) <= GradingSurvey.MAX_STEP;
  }
}
