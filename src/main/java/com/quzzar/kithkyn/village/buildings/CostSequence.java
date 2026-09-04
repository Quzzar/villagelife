package com.quzzar.kithkyn.village.buildings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Combines the material counts from an ordered sequence of construction steps. */
final class CostSequence {

  private CostSequence() {
  }

  static <T> Map<T, Integer> combine(List<Map<T, Integer>> steps) {
    Map<T, Integer> combined = new LinkedHashMap<>();
    for (Map<T, Integer> step : steps) {
      step.forEach((material, count) -> combined.merge(material, count, Integer::sum));
    }
    return combined;
  }

  static int increase(int previous, int target) {
    return Math.max(0, target - previous);
  }
}
