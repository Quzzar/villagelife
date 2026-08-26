package com.quzzar.villagelife.village.tiers;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * The loaded tier ladder, reloaded from datapacks by {@link VillageTierLoader}.
 * Ordered by rank ascending; ties broken by id for determinism.
 */
public final class VillageTiers {

  /** Hardcoded fallback so a village founded before any datapack load has a tier. */
  public static final String DEFAULT_TIER_ID = "villagelife:camp";

  private static volatile Map<String, VillageTier> byId = Map.of();
  private static volatile List<VillageTier> ordered = List.of();

  private VillageTiers() {
  }

  static void reload(Map<String, VillageTier> tiers) {
    byId = Map.copyOf(tiers);
    ordered = tiers.values().stream()
        .sorted(Comparator.comparingInt(VillageTier::rank).thenComparing(VillageTier::id))
        .toList();
  }

  @Nullable
  public static VillageTier byId(String id) {
    return byId.get(id);
  }

  /**
   * The highest tier this population qualifies for, or null when no ladder is
   * loaded. Villages only ever move up (high-water mark), so callers compare
   * against their stored tier's rank before adopting the result.
   */
  @Nullable
  public static VillageTier classify(int population) {
    VillageTier best = null;
    for (VillageTier tier : ordered) {
      if (population >= tier.minPopulation()) {
        best = tier;
      }
    }
    return best;
  }

  /**
   * The rank of a stored tier id; unknown ids (datapack removed since the save)
   * count as rank -1 so the village reclassifies purely from population and
   * only ever upward from there.
   */
  public static int rankOf(String id) {
    VillageTier tier = byId.get(id);
    return tier != null ? tier.rank() : -1;
  }

  public static List<VillageTier> ladder() {
    return ordered;
  }

}
