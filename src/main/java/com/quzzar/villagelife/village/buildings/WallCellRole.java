package com.quzzar.villagelife.village.buildings;

/** How construction decides whether an authored wall cell is already done. */
public enum WallCellRole {
  /** Any solid, collidable block closes this part of the defensive shell. */
  BARRIER,
  /** Prefer the authored state, while still preserving unrelated solid construction. */
  EXACT;
}
