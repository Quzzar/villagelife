package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.economy.Treasury;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * What a merchant does when nobody is buying (docs/economy.md): a CARRY step
 * with two phases.
 *
 * A village sells out of its own stores, but everything it BUYS from a player
 * lands in the market's chests - the same chests that hold the treasury. So a
 * market that trades fills up, and a full market cannot buy anything: the stall
 * turns customers away while the village still wants what they are carrying.
 * Clearing the till is real work with a real consequence, and it is the
 * merchant's.
 *
 * Emeralds are never carried. They are the money, not the merchandise, and a
 * merchant who shelved the till would be moving the shop's ability to pay into
 * a barrel across the village.
 *
 * The goal this replaces hand-rolled its own stall detection - a closest-
 * approach distance and a patience counter - which is precisely what
 * {@link com.quzzar.villagelife.entities.ai.goals.ApproachWatch} already does
 * for every other worker. That copy goes; the loop watches this merchant the
 * same way it watches everyone.
 */
public final class MarketStep implements BlockWorkStep {

  /** How long the merchant will stand at the stall before letting their legs go. */
  private static final int MIND_TICKS = 200;

  /** Dead container positions to step past before giving up on a delivery. */
  private static final int MAX_STALE_CHESTS = 8;

  private boolean carrying;
  private int mindingTicks;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    Village village = person.getVillage();
    if (village == null) {
      return null;
    }
    this.mindingTicks = 0;
    // Anything already in the pack is delivered before anything else is lifted.
    // Without this a merchant interrupted mid-trip goes back for another stack
    // and hoards the shop in their pockets.
    this.carrying = packHoldsGoods(person);
    if (this.carrying) {
      return deliveryChest(person, village);
    }
    BlockPos till = fullTillChest(person, village);
    if (till != null) {
      return till;
    }
    // Nothing to clear: mind the stall instead, if there is one to mind.
    BlockPos station = LocationManager.getJobLocation(person);
    return station.equals(BlockPos.ZERO) ? null : station;
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    if (this.carrying) {
      emptyPackInto(person, target);
      this.carrying = false;
      return false;
    }

    Village village = person.getVillage();
    if (village == null || !(person.level().getBlockEntity(target) instanceof Container till)) {
      // Either the chest went, or this is the stall and the merchant is simply
      // standing at it. Standing is bounded so the legs come free again.
      return ++this.mindingTicks < MIND_TICKS;
    }

    ItemStack taken = takeOneStack(person, till);
    if (taken.isEmpty()) {
      return ++this.mindingTicks < MIND_TICKS;
    }
    this.carrying = true;

    // Nowhere else in the village to put it: give it back rather than walk it
    // around, and let the shortage show as a market that stays full.
    if (deliveryChest(person, village) == null) {
      putBack(person, till, taken);
      this.carrying = false;
    }
    return false; // either way the next select decides where to go
  }

  @Override
  public String describe() {
    return "the market";
  }

  @Override
  public String activity() {
    return "minding the market";
  }

  @Override
  public double reachSqr(RealPerson person) {
    return 6.0D;
  }

  /** Lifting and shelving are per-tick decisions; minding the stall counts ticks. */
  @Override
  public int actEveryTicks() {
    return 1;
  }

  /**
   * Somewhere in the village that is not the market's own chests.
   *
   * A village keeps container positions long after the block itself is gone - a
   * chest broken, built over, or replaced by an upgrade. Readers skip those
   * harmlessly, but a merchant aiming at one arrives, finds nothing to put
   * anything in, and starts the whole trip again: 313 times, in the run that
   * showed this up.
   */
  @Nullable
  private BlockPos deliveryChest(RealPerson person, Village village) {
    if (!(person.level() instanceof ServerLevel level)) {
      return null;
    }
    List<BlockPos> skip = new ArrayList<>(Treasury.chestPositions(village, level));
    for (int attempt = 0; attempt < MAX_STALE_CHESTS; attempt++) {
      BlockPos found = village.getNearestContainer(person.blockPosition(), skip);
      if (found.equals(BlockPos.ZERO)) {
        return null;
      }
      if (level.getBlockEntity(found) instanceof Container) {
        return found;
      }
      skip.add(found);
    }
    return null;
  }

  /** True when the pack holds anything the merchant should be shelving. */
  private boolean packHoldsGoods(RealPerson person) {
    Container pack = person.personMainInv;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      if (!stack.isEmpty() && stack.getItem() != Treasury.CURRENCY) {
        return true;
      }
    }
    return false;
  }

  /** The first market chest holding something that is not money. */
  @Nullable
  private BlockPos fullTillChest(RealPerson person, Village village) {
    if (!(person.level() instanceof ServerLevel level)) {
      return null;
    }
    for (BlockPos pos : Treasury.chestPositions(village, level)) {
      if (!(level.getBlockEntity(pos) instanceof Container container)) {
        continue;
      }
      for (int slot = 0; slot < container.getContainerSize(); slot++) {
        ItemStack stack = container.getItem(slot);
        if (!stack.isEmpty() && stack.getItem() != Treasury.CURRENCY) {
          return pos;
        }
      }
    }
    return null;
  }

  /** Lifts one non-currency stack out of the till and into the merchant's pack. */
  private ItemStack takeOneStack(RealPerson person, Container till) {
    Container pack = person.personMainInv;
    for (int slot = 0; slot < till.getContainerSize(); slot++) {
      ItemStack stack = till.getItem(slot);
      if (stack.isEmpty() || stack.getItem() == Treasury.CURRENCY) {
        continue;
      }
      ItemStack leftover = HopperBlockEntity.addItem(till, pack, stack, null);
      till.setItem(slot, leftover);
      if (leftover.getCount() < stack.getCount()) {
        return stack;
      }
    }
    return ItemStack.EMPTY;
  }

  private void putBack(RealPerson person, Container till, ItemStack taken) {
    Container pack = person.personMainInv;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      if (stack.isEmpty() || stack.getItem() != taken.getItem()) {
        continue;
      }
      pack.setItem(slot, HopperBlockEntity.addItem(pack, till, stack, null));
    }
  }

  private void emptyPackInto(RealPerson person, BlockPos pos) {
    if (!(person.level().getBlockEntity(pos) instanceof Container into)) {
      return;
    }
    Container pack = person.personMainInv;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      if (stack.isEmpty()) {
        continue;
      }
      pack.setItem(slot, HopperBlockEntity.addItem(pack, into, stack, null));
    }
  }

}
