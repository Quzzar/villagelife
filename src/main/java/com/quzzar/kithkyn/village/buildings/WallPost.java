package com.quzzar.kithkyn.village.buildings;

import net.minecraft.core.BlockPos;

/** One exact guard station derived from a completed village wall. */
public record WallPost(BlockPos position, BlockPos lookAt, long anchor, Duty duty) {

  /** The four staffing tiers requested by the wall's defensive plan. */
  public enum Duty {
    GATE_SWORD_PRIMARY(0, false),
    WATCHTOWER_CROSSBOW(1, true),
    GATE_CROSSBOW(2, true),
    GATE_SWORD_SECONDARY(3, false);

    private final int fillPriority;
    private final boolean crossbow;

    Duty(int fillPriority, boolean crossbow) {
      this.fillPriority = fillPriority;
      this.crossbow = crossbow;
    }

    /** Lower values are staffed first across the whole wall. */
    public int fillPriority() {
      return this.fillPriority;
    }

    public boolean usesCrossbow() {
      return this.crossbow;
    }
  }
}
