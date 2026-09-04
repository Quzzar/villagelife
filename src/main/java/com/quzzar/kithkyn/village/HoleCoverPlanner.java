package com.quzzar.kithkyn.village;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import com.quzzar.kithkyn.village.buildings.SitePreparation;

/**
 * Finds small, deep openings in a sampled ground surface that should be
 * covered instead of treated as terrain to grade.
 */
final class HoleCoverPlanner {

  static final int NO_SURFACE = Integer.MIN_VALUE;
  static final int NO_COVER = Integer.MIN_VALUE;

  /** A deeper depression is shape rather than ordinary surface grading. */
  private static final int MINIMUM_DEPTH = SitePreparation.MAX_COLUMN_DELTA + 1;

  /** Most blocks a single cave mouth may cost to cover. */
  private static final int MAXIMUM_OPENING_BLOCKS = 24;

  /** Long narrow trenches and ravines are not cave mouths. */
  private static final int MAXIMUM_OPENING_SPAN = 6;

  private static final int[][] NEIGHBOURS = { {-1, 0}, {1, 0}, {0, -1}, {0, 1} };

  /** One frontier column and the lowest height at which its ground drains out. */
  private record Frontier(int index, int spillHeight) {
  }

  private HoleCoverPlanner() {
  }

  /**
   * Returns the surface height at which each opening column should be covered,
   * or {@link #NO_COVER} where the sampled ground should be left alone.
   */
  static int[] plan(int width, int depth, int[] surface, boolean[] coverable) {
    int size = width * depth;
    if (width <= 0 || depth <= 0 || surface.length != size || coverable.length != size) {
      throw new IllegalArgumentException("Ground dimensions must match the sampled columns");
    }

    int[] coverHeight = spillHeights(width, depth, surface, coverable);
    bridgeNarrowOpenings(width, depth, surface, coverable, coverHeight);
    int[] cover = new int[size];
    Arrays.fill(cover, NO_COVER);

    boolean[] visited = new boolean[size];
    ArrayDeque<Integer> frontier = new ArrayDeque<>();
    for (int start = 0; start < size; start++) {
      if (visited[start] || !needsCover(start, surface, coverable, coverHeight)) {
        continue;
      }

      List<Integer> opening = new ArrayList<>();
      int deepest = 0;
      int minX = width;
      int maxX = -1;
      int minZ = depth;
      int maxZ = -1;
      boolean touchesBoundary = false;
      boolean touchesUnknown = false;
      visited[start] = true;
      frontier.add(start);
      while (!frontier.isEmpty()) {
        int column = frontier.removeFirst();
        opening.add(column);
        deepest = Math.max(deepest, coverHeight[column] - surface[column]);
        int x = column % width;
        int z = column / width;
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
        minZ = Math.min(minZ, z);
        maxZ = Math.max(maxZ, z);
        touchesBoundary |= x == 0 || z == 0 || x == width - 1 || z == depth - 1;

        for (int[] step : NEIGHBOURS) {
          int neighbour = index(width, depth, x + step[0], z + step[1]);
          if (neighbour >= 0 && surface[neighbour] == NO_SURFACE) {
            touchesUnknown = true;
          }
          if (neighbour >= 0 && !visited[neighbour]
              && needsCover(neighbour, surface, coverable, coverHeight)) {
            visited[neighbour] = true;
            frontier.addLast(neighbour);
          }
        }
      }

      int spanX = maxX - minX + 1;
      int spanZ = maxZ - minZ + 1;
      if (touchesBoundary || touchesUnknown || deepest < MINIMUM_DEPTH
          || opening.size() > MAXIMUM_OPENING_BLOCKS
          || spanX > MAXIMUM_OPENING_SPAN || spanZ > MAXIMUM_OPENING_SPAN) {
        continue;
      }
      for (int column : opening) {
        cover[column] = coverHeight[column];
      }
    }
    return cover;
  }

  /**
   * Finds a narrow notch even when it drains downhill and therefore is not a
   * closed depression. Opposing ground within the opening span supplies a
   * conservative cap at the lower rim. The area, depth, edge and unknown-ground
   * checks in {@link #plan} still decide whether the collected notch is a cave
   * mouth small enough to cover.
   */
  private static void bridgeNarrowOpenings(int width, int depth, int[] surface, boolean[] coverable,
      int[] coverHeight) {
    for (int z = 0; z < depth; z++) {
      for (int left = 0; left < width; left++) {
        int leftColumn = z * width + left;
        if (surface[leftColumn] == NO_SURFACE) {
          continue;
        }
        int lastRight = Math.min(width - 1, left + MAXIMUM_OPENING_SPAN + 1);
        for (int right = left + 2; right <= lastRight; right++) {
          int rightColumn = z * width + right;
          if (surface[rightColumn] == NO_SURFACE) {
            continue;
          }
          bridgeBetween(leftColumn, rightColumn, 1, surface, coverable, coverHeight);
        }
      }
    }
    for (int x = 0; x < width; x++) {
      for (int top = 0; top < depth; top++) {
        int topColumn = top * width + x;
        if (surface[topColumn] == NO_SURFACE) {
          continue;
        }
        int lastBottom = Math.min(depth - 1, top + MAXIMUM_OPENING_SPAN + 1);
        for (int bottom = top + 2; bottom <= lastBottom; bottom++) {
          int bottomColumn = bottom * width + x;
          if (surface[bottomColumn] == NO_SURFACE) {
            continue;
          }
          bridgeBetween(topColumn, bottomColumn, width, surface, coverable, coverHeight);
        }
      }
    }
  }

  /** Raises the eligible columns between two rims to no more than the lower rim. */
  private static void bridgeBetween(int first, int last, int stride, int[] surface, boolean[] coverable,
      int[] coverHeight) {
    int rim = Math.min(surface[first], surface[last]);
    for (int column = first + stride; column < last; column += stride) {
      if (coverable[column] && surface[column] != NO_SURFACE && surface[column] < rim) {
        coverHeight[column] = Math.max(coverHeight[column], rim);
      }
    }
  }

  /**
   * Raises every enclosed depression to the lowest height at which it reaches
   * the survey edge, unknown terrain, or protected ground. This is the standard
   * priority-flood reading of a height field: a cave mouth has a high spill
   * rim, while a valley that runs out of the survey drains at its own height.
   */
  private static int[] spillHeights(int width, int depth, int[] surface, boolean[] coverable) {
    int[] spill = new int[surface.length];
    Arrays.fill(spill, NO_SURFACE);
    boolean[] visited = new boolean[surface.length];
    PriorityQueue<Frontier> frontier = new PriorityQueue<>(Comparator.comparingInt(Frontier::spillHeight));

    for (int column = 0; column < surface.length; column++) {
      if (surface[column] != NO_SURFACE && drainsOut(column, width, depth, surface, coverable)) {
        visited[column] = true;
        spill[column] = surface[column];
        frontier.add(new Frontier(column, surface[column]));
      }
    }

    while (!frontier.isEmpty()) {
      Frontier from = frontier.remove();
      int x = from.index() % width;
      int z = from.index() / width;
      for (int[] step : NEIGHBOURS) {
        int to = index(width, depth, x + step[0], z + step[1]);
        if (to < 0 || visited[to] || surface[to] == NO_SURFACE) {
          continue;
        }
        visited[to] = true;
        spill[to] = Math.max(surface[to], from.spillHeight());
        frontier.add(new Frontier(to, spill[to]));
      }
    }
    return spill;
  }

  /** Ground here has an honest outlet, so a depression connected to it is not covered. */
  private static boolean drainsOut(int column, int width, int depth, int[] surface, boolean[] coverable) {
    int x = column % width;
    int z = column / width;
    if (x == 0 || z == 0 || x == width - 1 || z == depth - 1 || !coverable[column]) {
      return true;
    }
    for (int[] step : NEIGHBOURS) {
      int neighbour = index(width, depth, x + step[0], z + step[1]);
      if (neighbour < 0 || surface[neighbour] == NO_SURFACE) {
        return true;
      }
    }
    return false;
  }

  private static boolean needsCover(int column, int[] surface, boolean[] coverable, int[] coverHeight) {
    return coverable[column] && surface[column] != NO_SURFACE && coverHeight[column] > surface[column];
  }

  /** Grid index for one cardinal neighbour, or -1 beyond the sampled area. */
  private static int index(int width, int depth, int x, int z) {
    if (x < 0 || z < 0 || x >= width || z >= depth) {
      return -1;
    }
    return z * width + x;
  }
}
