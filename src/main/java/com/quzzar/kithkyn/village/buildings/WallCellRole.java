package com.quzzar.kithkyn.village.buildings;

/** How construction decides whether an authored wall cell is already done. */
public enum WallCellRole {
  /** Any solid, collidable block closes this part of the defensive shell. */
  BARRIER,
  /** Prefer the authored state, while still preserving unrelated solid construction. */
  EXACT,
  /** Repeat this authored state down to the exact live terrain in its column. */
  FOUNDATION,
  /** Removes lower-priority generated cells from an authored feature's empty volume. */
  CLEARANCE;
}
