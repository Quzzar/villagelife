package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Carry the haul home.
 *
 * A gathering worker breaks real blocks and pockets the drops; this is the other
 * half of that loop. When their pack is heavy enough to be worth a trip, they
 * walk to a container in their OWN workplace and empty it in — the chest at the
 * mine, the barrel at the lumberyard.
 *
 * Without this a worker gathers forever into a 36-slot inventory and the village
 * stores never grow. That matters beyond tidiness: affordability is checked
 * against real container contents, so nothing a village wants to build would
 * ever become affordable, no matter how long its miners worked.
 *
 * A full chest is not an error. The worker keeps whatever did not fit and tries
 * again later, which reads as a village that has outgrown its storage.
 */
public class DepositHaulGoal extends Goal {

  /** Of 36 slots. Below this a trip costs more time than it banks. */
  private static final int SLOTS_BEFORE_TRIP = 18;
  private static final double REACH_SQR = 6.0D;
  private static final double SPEED = 0.5D;

  private final RealPerson person;
  private BlockPos target;

  public DepositHaulGoal(RealPerson person) {
    // Walks the villager somewhere, so it competes for movement rather than
    // running alongside the work goal that also moves them (#74).
    this.setFlags(EnumSet.of(Flag.MOVE));
    this.person = person;
  }

  @Override
  public boolean canUse() {
    if (usedSlots() < SLOTS_BEFORE_TRIP) {
      return false;
    }
    this.target = workplaceContainer();
    return this.target != null;
  }

  @Override
  public boolean canContinueToUse() {
    return this.target != null && usedSlots() > 0 && !this.person.isInterrupted();
  }

  @Override
  public void stop() {
    this.target = null;
  }

  @Override
  public void tick() {
    if (this.target == null) {
      return;
    }
    if (this.person.blockPosition().distSqr(this.target) > REACH_SQR) {
      this.person.getNavigation().moveTo(
          this.target.getX() + 0.5D, this.target.getY(), this.target.getZ() + 0.5D, SPEED);
      return;
    }

    this.person.getLookControl().setLookAt(
        this.target.getX(), this.target.getY(), this.target.getZ(), 30.0F, 30.0F);

    BlockEntity entity = this.person.level().getBlockEntity(this.target);
    if (!(entity instanceof Container into)) {
      this.target = null; // the chest was taken out from under them
      return;
    }
    deposit(into);
    this.target = null;
  }

  /** Moves what fits, keeps what does not. Never spawns an item entity. */
  private void deposit(Container into) {
    Container pack = this.person.personMainInv;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      if (stack.isEmpty()) {
        continue;
      }
      ItemStack leftover = HopperBlockEntity.addItem(pack, into, stack, null);
      pack.setItem(slot, leftover);
      if (!leftover.isEmpty()) {
        return; // the container is full; the rest stays in the pack
      }
    }
  }

  private int usedSlots() {
    Container pack = this.person.personMainInv;
    int used = 0;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      if (!pack.getItem(slot).isEmpty()) {
        used++;
      }
    }
    return used;
  }

  /**
   * A container belonging to the worker's own workplace, in world space. Falls
   * back to the nearest village container so a haul is never stranded by a
   * workplace that declares none.
   */
  private BlockPos workplaceContainer() {
    Building building = LocationManager.getJobBuilding(this.person);
    if (building != null && building.getInfo() != null) {
      BlockPos origin = BlockPos.of(building.getOriginLocation());
      for (Long local : building.getInfo().getContainerLocations()) {
        BlockPos pos = origin.offset(BlockPos.of(local).rotate(building.getRotation()));
        if (this.person.level().getBlockEntity(pos) instanceof Container) {
          return pos;
        }
      }
    }
    return LocationManager.getNearestContainerPos(this.person);
  }
}
