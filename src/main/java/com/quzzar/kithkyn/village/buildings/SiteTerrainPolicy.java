package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a footprint is surface the village may level or landform it
 * must leave alone. Most columns keep the ordinary three-block limit. A small,
 * compact low spot gets a deeper fill-only exception so one dipped corner does
 * not disqualify otherwise suitable ground.
 */
final class SiteTerrainPolicy {

  /** A compact depression may be this far below the proposed build plane. */
  static final int MAX_LOCAL_DEPRESSION_DEPTH = SitePreparation.MAX_COLUMN_DELTA * 2;

  /** At most one deep low column in this many footprint columns. */
  private static final int DEEP_COLUMNS_PER = 8;

  /** A deep low component may span at most this many blocks on either axis. */
  private static final int MAX_LOCAL_DEPRESSION_SPAN = 3;

  record Column(int x, int z, int delta) {
  }

  record Assessment(boolean allowed, String reason, int deltaSum, int deepColumns) {
  }

  private SiteTerrainPolicy() {
  }

  /** The heightmap may send this many steep columns on to the exact block scan. */
  static int allowedOutlierColumns(int columns) {
    return Math.max(1, columns / DEEP_COLUMNS_PER);
  }

  static Assessment assess(List<Column> columns) {
    int deltaSum = 0;
    Map<Long, Column> deep = new HashMap<>();
    for (Column column : columns) {
      if (column.delta() > SitePreparation.MAX_COLUMN_DELTA) {
        return refused(deltaSum, deep.size(), "ground at " + column.x() + "," + column.z()
            + " is " + column.delta() + " off the build plane, past the levelling budget");
      }
      if (column.delta() < -MAX_LOCAL_DEPRESSION_DEPTH) {
        return refused(deltaSum, deep.size(), "ground at " + column.x() + "," + column.z()
            + " is " + column.delta() + " off the build plane, too deep to fill safely");
      }
      deltaSum += Math.abs(column.delta());
      if (column.delta() < -SitePreparation.MAX_COLUMN_DELTA) {
        deep.put(key(column.x(), column.z()), column);
      }
    }

    int allowedDeep = allowedOutlierColumns(columns.size());
    if (deep.size() > allowedDeep) {
      return refused(deltaSum, deep.size(), deep.size() + " of its " + columns.size()
          + " columns are deep low ground; only " + allowedDeep + " compact low outlier"
          + (allowedDeep == 1 ? " is" : "s are") + " allowed");
    }
    String broadDepression = broadDepression(deep);
    if (broadDepression != null) {
      return refused(deltaSum, deep.size(), broadDepression);
    }
    if (!columns.isEmpty() && (double) deltaSum / columns.size() > SitePreparation.MAX_AVERAGE_DELTA) {
      return refused(deltaSum, deep.size(), String.format(
          "too uneven: %.1f blocks per column against a budget of %.1f",
          (double) deltaSum / columns.size(), SitePreparation.MAX_AVERAGE_DELTA));
    }
    return new Assessment(true, "", deltaSum, deep.size());
  }

  /** Refuses a long trench even when a large footprint makes its ratio small. */
  private static String broadDepression(Map<Long, Column> deep) {
    Set<Long> visited = new HashSet<>();
    for (Column start : deep.values()) {
      if (!visited.add(key(start.x(), start.z()))) {
        continue;
      }
      int minX = start.x();
      int maxX = start.x();
      int minZ = start.z();
      int maxZ = start.z();
      ArrayDeque<Column> open = new ArrayDeque<>();
      open.add(start);
      while (!open.isEmpty()) {
        Column column = open.removeFirst();
        minX = Math.min(minX, column.x());
        maxX = Math.max(maxX, column.x());
        minZ = Math.min(minZ, column.z());
        maxZ = Math.max(maxZ, column.z());
        visit(deep, visited, open, column.x() - 1, column.z());
        visit(deep, visited, open, column.x() + 1, column.z());
        visit(deep, visited, open, column.x(), column.z() - 1);
        visit(deep, visited, open, column.x(), column.z() + 1);
      }
      int spanX = maxX - minX + 1;
      int spanZ = maxZ - minZ + 1;
      if (spanX > MAX_LOCAL_DEPRESSION_SPAN || spanZ > MAX_LOCAL_DEPRESSION_SPAN) {
        return "deep low ground forms a " + spanX + " by " + spanZ
            + " depression, too broad to count as a local dip";
      }
    }
    return null;
  }

  private static void visit(Map<Long, Column> deep, Set<Long> visited, ArrayDeque<Column> open,
      int x, int z) {
    long key = key(x, z);
    Column neighbour = deep.get(key);
    if (neighbour != null && visited.add(key)) {
      open.addLast(neighbour);
    }
  }

  private static Assessment refused(int deltaSum, int deepColumns, String reason) {
    return new Assessment(false, reason, deltaSum, deepColumns);
  }

  private static long key(int x, int z) {
    return ((long) x << 32) ^ (z & 0xffffffffL);
  }
}
