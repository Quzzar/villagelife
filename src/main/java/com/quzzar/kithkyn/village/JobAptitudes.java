package com.quzzar.kithkyn.village;

import java.util.Map;

import com.quzzar.kithkyn.entities.genetics.Stat;
import com.quzzar.kithkyn.entities.genetics.StatBlock;

/**
 * Aptitude profiles for the campfire map's stat-based job placement (#38):
 * per-occupation stat weights loaded from datapack JSON by
 * {@link JobAptitudeLoader}. An aptitude score is the weighted average of the
 * person's stats on the same 3-18 scale the stats use, so the swap threshold
 * config reads directly in stat points.
 */
public final class JobAptitudes {

  private static volatile Map<Occupation, Map<Stat, Integer>> profiles = Map.of();

  private JobAptitudes() {
  }

  static void reload(Map<Occupation, Map<Stat, Integer>> loaded) {
    profiles = Map.copyOf(loaded);
  }

  /**
   * How suited this person is to the occupation, on the 3-18 stat scale. A
   * missing stat block or an occupation with no profile scores as the average
   * (10.0), which keeps placement effectively FIFO for unprofiled jobs.
   */
  public static double score(StatBlock stats, Occupation occupation) {
    Map<Stat, Integer> weights = profiles.get(occupation);
    if (stats == null || weights == null || weights.isEmpty()) {
      return Stat.AVERAGE;
    }
    double weighted = 0;
    double total = 0;
    for (Map.Entry<Stat, Integer> entry : weights.entrySet()) {
      weighted += stats.get(entry.getKey()) * entry.getValue();
      total += entry.getValue();
    }
    return total == 0 ? Stat.AVERAGE : weighted / total;
  }
}
