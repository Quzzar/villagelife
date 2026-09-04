package com.quzzar.kithkyn.entities.ai.goals.work;

import net.minecraft.core.BlockPos;

/**
 * A {@link WorkStep} whose target is a place: the common case, and everything
 * that breaks, places, hauls or crafts.
 *
 * Exists only so those steps need not restate that a block position is where a
 * block position is. Steps that follow something which moves - a blacksmith
 * chasing the villager whose armour they are mending - implement
 * {@code WorkStep} directly and answer for themselves.
 */
public interface BlockWorkStep extends WorkStep<BlockPos> {

  @Override
  default BlockPos positionOf(BlockPos target) {
    return target;
  }

}
