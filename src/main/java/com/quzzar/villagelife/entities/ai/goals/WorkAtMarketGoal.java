package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.economy.Treasury;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * What a merchant does when nobody is buying (docs/economy.md).
 *
 * A village sells out of its own stores, but everything it BUYS from a player
 * lands in the market's chests — the same chests that hold the treasury. So a
 * market that trades fills up, and a full market cannot buy anything: the stall
 * turns customers away while the village still wants what they are carrying.
 * Clearing the till is therefore real work with a real consequence, not
 * housekeeping, and it is the merchant's.
 *
 * Emeralds are never carried. They are the money, not the merchandise, and a
 * merchant who shelved the till would be moving the shop's ability to pay into
 * a barrel across the village.
 *
 * Between trips the merchant stands at their stall, in bounded stretches rather
 * than forever: a goal that holds a villager's legs with no end condition is how
 * every worker in this village once ended up standing at their workplace for
 * life.
 */
public class WorkAtMarketGoal extends Goal {

  private static final double REACH_SQR = 6.0D;
  private static final double SPEED = 0.5D;

  /** How long the merchant minds the stall before yielding to everything else. */
  private static final int MIND_TICKS = 200;

  /** Ticks of walking without getting closer before the trip is given up. */
  private static final int STALL_TICKS = 100;

  private double closestApproachSqr = Double.MAX_VALUE;
  private int ticksWithoutProgress = 0;

  private final RealPerson person;

  @Nullable
  private BlockPos target;

  /** True once a stack is in the pack and needs somewhere else to live. */
  private boolean carrying;

  private int mindingTicks;

  public WorkAtMarketGoal(RealPerson person) {
    // Walks the villager somewhere, so it competes for movement rather than
    // running alongside the goals that also move them (#74).
    this.setFlags(EnumSet.of(Flag.MOVE));
    this.person = person;
  }

  @Override
  public boolean canUse() {
    Village village = person.getVillage();
    if (village == null || shouldInterrupt()) {
      return false;
    }
    this.mindingTicks = 0;
    this.closestApproachSqr = Double.MAX_VALUE;
    this.ticksWithoutProgress = 0;
    // Anything already in the pack gets delivered before anything else is
    // lifted. Without this a merchant interrupted mid-trip goes back for
    // another stack and hoards the shop in their pockets.
    this.carrying = packHoldsGoods();
    if (this.carrying) {
      this.target = deliveryChest(village);
      return this.target != null;
    }
    this.target = fullTillChest(village);
    if (this.target != null) {
      return true;
    }
    // Nothing to clear: mind the stall instead, if there is one to mind.
    BlockPos station = LocationManager.getJobLocation(person);
    this.target = station.equals(BlockPos.ZERO) ? null : station;
    return this.target != null;
  }

  @Override
  public boolean canContinueToUse() {
    return this.target != null && !shouldInterrupt();
  }

  @Override
  public void stop() {
    this.target = null;
    this.carrying = false;
  }

  @Override
  public void tick() {
    if (this.target == null) {
      return;
    }
    double distance = person.blockPosition().distSqr(this.target);
    if (distance > REACH_SQR) {
      // A chest the merchant cannot actually walk to — a stall that landed on a
      // rise, a path that no longer exists — must not hold their legs for ever.
      if (distance < this.closestApproachSqr - 0.5D) {
        this.closestApproachSqr = distance;
        this.ticksWithoutProgress = 0;
      } else if (++this.ticksWithoutProgress > STALL_TICKS) {
        this.target = null;
        return;
      }
      person.getNavigation().moveTo(
          this.target.getX() + 0.5D, this.target.getY(), this.target.getZ() + 0.5D, SPEED);
      return;
    }
    person.getLookControl().setLookAt(
        this.target.getX(), this.target.getY(), this.target.getZ(), 30.0F, 30.0F);

    if (this.carrying) {
      emptyPackInto(this.target);
      this.target = null;
      return;
    }

    Village village = person.getVillage();
    if (village == null || !(person.level().getBlockEntity(this.target) instanceof Container till)) {
      // Either the chest went, or this was the stall and the merchant is simply
      // standing at it. Standing is bounded so the legs come free again.
      if (++this.mindingTicks >= MIND_TICKS) {
        this.target = null;
      }
      return;
    }

    ItemStack taken = takeOneStack(till);
    if (taken.isEmpty()) {
      if (++this.mindingTicks >= MIND_TICKS) {
        this.target = null;
      }
      return;
    }
    if (!person.swinging) {
      person.swing(person.getUsedItemHand());
    }
    this.carrying = true;
    this.closestApproachSqr = Double.MAX_VALUE;
    this.ticksWithoutProgress = 0;
    this.target = deliveryChest(village);
    if (this.target == null) {
      // Nowhere else in the village to put it: give it back rather than walk it
      // around, and let the shortage show as a market that stays full.
      putBack(till, taken);
      this.carrying = false;
      Villagelife.LOGGER.debug("Village '{}' has nowhere but the market to store {}",
          village.getName(), taken.getItem());
    }
  }

  /**
   * Somewhere in the village that is not the market's own chests. The village
   * returns {@link BlockPos#ZERO} rather than null when it has nowhere, which
   * is a real answer for a village whose only container is its till.
   */
  @Nullable
  private BlockPos deliveryChest(Village village) {
    if (!(person.level() instanceof ServerLevel level)) {
      return null;
    }
    BlockPos found = village.getNearestContainer(person.blockPosition(),
        Treasury.chestPositions(village, level));
    return found.equals(BlockPos.ZERO) ? null : found;
  }

  /** True when the pack holds anything the merchant should be shelving. */
  private boolean packHoldsGoods() {
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
  private BlockPos fullTillChest(Village village) {
    if (!(person.level() instanceof ServerLevel level)) {
      return null;
    }
    List<BlockPos> positions = Treasury.chestPositions(village, level);
    for (BlockPos pos : positions) {
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
  private ItemStack takeOneStack(Container till) {
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

  private void putBack(Container till, ItemStack taken) {
    Container pack = person.personMainInv;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      if (stack.isEmpty() || stack.getItem() != taken.getItem()) {
        continue;
      }
      pack.setItem(slot, HopperBlockEntity.addItem(pack, till, stack, null));
    }
  }

  private void emptyPackInto(BlockPos pos) {
    BlockEntity entity = person.level().getBlockEntity(pos);
    if (!(entity instanceof Container into)) {
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

  private boolean shouldInterrupt() {
    return person.getLastHurtByMob() != null
        || person.isFreezing()
        || person.isOnFire()
        || person.level().isNight()
        || person.isInterrupted();
  }

}
