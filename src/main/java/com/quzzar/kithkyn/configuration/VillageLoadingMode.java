package com.quzzar.kithkyn.configuration;

/**
 * How much of the world's villages stay awake when no player is near
 * (docs/village-loading.md).
 *
 * <p>A village runs its cheap bookkeeping every second whatever the mode; this
 * setting only decides whether its chunks are held loaded and ticking, so it
 * keeps building, mining, farming, and defending itself unattended.
 */
public enum VillageLoadingMode {
  /** No village keeps chunks loaded. The world freezes the moment the last player leaves. */
  OFF,
  /** Every village in the world stays loaded, seen or not. The most faithful, and the most costly. */
  ALL,
  /** A village stays loaded while a player has visited it within the grace window, then goes dormant. */
  HYBRID
}
