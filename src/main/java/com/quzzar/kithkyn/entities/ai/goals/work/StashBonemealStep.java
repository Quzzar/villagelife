package com.quzzar.kithkyn.entities.ai.goals.work;

import java.util.Arrays;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Shelving bone meal nothing wants yet: a CARRY step, pack to container, and
 * the stash half of the farmer's bone meal shelf.
 *
 * The composter hands its produce straight to the pack; when the field has
 * nothing left to feed, the surplus goes into the farm's own barrel instead of
 * riding around all day. That is what makes the chain legible from outside:
 * the barrel is where the farm's fertiliser lives, whoever made it and
 * whenever it gets used - the same shelf the fetch step and the bedtime
 * restock draw from.
 */
public final class StashBonemealStep implements BlockWorkStep {

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    if (!person.hasItem(Items.BONE_MEAL)) {
      return null;
    }
    BlockPos station = LocationManager.getJobLocation(person);
    if (station.equals(BlockPos.ZERO)) {
      return null;
    }
    if (BonemealStep.findGrowable(person, station) != null) {
      return null; // the field still wants it; feeding outranks shelving
    }
    for (BlockPos pos : LocationManager.getJobContainerPositions(person)) {
      if (person.level().getBlockEntity(pos) instanceof Container) {
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
    // One stack a trip: over 64 held is not a case worth a second pass for.
    int held = Math.min(person.personMainInv.countItem(Items.BONE_MEAL), 64);
    if (held <= 0) {
      return false;
    }
    ItemStack carrying = person.removeItem(Items.BONE_MEAL, held);
    ItemStack leftover = HopperBlockEntity.addItem(person.personMainInv, shelf, carrying, null);
    if (!leftover.isEmpty()) {
      person.addItems(Arrays.asList(leftover)); // the shelf is full; keep the rest
    }
    int shelved = held - leftover.getCount();
    if (shelved > 0) {
      Kithkyn.LOGGER.debug("[resource-flow] {} ({}) shelved {} bone meal into the chest at {}",
          person.getName().getString(), person.getOccupation(), shelved, target.toShortString());
    }
    return false;
  }

  @Override
  public String describe() {
    return "somewhere to shelve the bone meal";
  }

  @Override
  public String activity() {
    return "shelving bone meal";
  }

  /** Emptying the pack is one action, taken on arrival. */
  @Override
  public int actEveryTicks() {
    return 1;
  }

}
