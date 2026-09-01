package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.Arrays;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Restocking the feeder from the farm's own shelf: a CARRY step, container to
 * pack, and the fetch half of the farmer's bone meal shelf.
 *
 * The bedtime restock already tops the pack up once a night; this is the
 * daytime half, so bone meal the composter banked in the barrel that morning
 * reaches the crops the same day rather than waiting for tomorrow. It only
 * fires when the field actually has something still growing, because fetching
 * fertiliser nothing wants is just a walk.
 */
public final class FetchBonemealStep implements BlockWorkStep {

  /** Same helping as the bedtime restock takes; the pack is not a warehouse. */
  private static final int PACK_TARGET = 16;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    if (person.hasItem(Items.BONE_MEAL) || person.isInventoryFull()) {
      return null; // stocked already, or nowhere to put it
    }
    BlockPos station = LocationManager.getJobLocation(person);
    if (station.equals(BlockPos.ZERO)) {
      return null;
    }
    if (BonemealStep.findGrowable(person, station) == null) {
      return null; // nothing in the field wants feeding
    }
    for (BlockPos pos : LocationManager.getJobContainerPositions(person)) {
      if (person.level().getBlockEntity(pos) instanceof Container shelf
          && shelf.countItem(Items.BONE_MEAL) > 0) {
        return pos;
      }
    }
    return null;
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    if (!(person.level().getBlockEntity(target) instanceof Container shelf)) {
      return false; // the shelf was taken out from under them
    }
    int wanted = PACK_TARGET;
    int taken = 0;
    for (int slot = 0; slot < shelf.getContainerSize() && wanted > 0; slot++) {
      ItemStack stack = shelf.getItem(slot);
      if (!stack.is(Items.BONE_MEAL)) {
        continue;
      }
      ItemStack out = shelf.removeItem(slot, Math.min(wanted, stack.getCount()));
      wanted -= out.getCount();
      taken += out.getCount();
      person.addItems(Arrays.asList(out));
    }
    if (taken > 0) {
      Villagelife.LOGGER.debug("[resource-flow] {} ({}) took {} bone meal from the shelf at {}",
          person.getName().getString(), person.getOccupation(), taken, target.toShortString());
    }
    return false;
  }

  @Override
  public String describe() {
    return "the shelf with the bone meal";
  }

  /** Filling the pack is one action, taken on arrival. */
  @Override
  public int actEveryTicks() {
    return 1;
  }

}
