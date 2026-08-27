package com.quzzar.villagelife.entities.ai.goals.work;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Carrying the haul home: a CARRY step, which moves items between an inventory
 * and a container.
 *
 * The other half of every gathering loop. A worker breaks real blocks and
 * pockets the drops; when the pack is heavy enough to be worth the walk, they
 * empty it into a container at their OWN workplace - the chest at the mine, the
 * barrel at the lumberyard.
 *
 * Without it a worker gathers forever into 36 slots and the village stores
 * never grow, which matters well beyond tidiness: affordability is checked
 * against real container contents, so nothing a village wanted to build would
 * ever become affordable no matter how long its miners worked.
 *
 * A full chest is not an error. Whatever did not fit stays in the pack and goes
 * again later, which reads as a village that has outgrown its storage.
 */
public final class HaulStep implements WorkStep {

  /** Of 36 slots. Below this a trip costs more time than it banks. */
  private static final int SLOTS_BEFORE_TRIP = 18;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    if (usedSlots(person) < SLOTS_BEFORE_TRIP) {
      return null;
    }
    return workplaceContainer(person);
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    if (!(person.level().getBlockEntity(target) instanceof Container into)) {
      return false; // the chest was taken out from under them
    }
    Container pack = person.personMainInv;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      if (stack.isEmpty()) {
        continue;
      }
      ItemStack leftover = HopperBlockEntity.addItem(pack, into, stack, null);
      pack.setItem(slot, leftover);
      if (!leftover.isEmpty()) {
        break; // the container is full; the rest stays in the pack
      }
    }
    return false;
  }

  @Override
  public String describe() {
    return "anywhere to put what I am carrying";
  }

  @Override
  public double reachSqr() {
    return 6.0D;
  }

  /** Emptying the pack is one action, taken as soon as the worker is there. */
  @Override
  public int actEveryTicks() {
    return 1;
  }

  /**
   * A container belonging to the worker's own workplace, in world space. Falls
   * back to the nearest village container so a haul is never stranded by a
   * workplace that declares none.
   */
  @Nullable
  private BlockPos workplaceContainer(RealPerson person) {
    Building building = LocationManager.getJobBuilding(person);
    if (building != null && building.getInfo() != null) {
      BlockPos origin = BlockPos.of(building.getOriginLocation());
      for (Long local : building.getInfo().getContainerLocations()) {
        BlockPos pos = origin.offset(BlockPos.of(local).rotate(building.getRotation()));
        if (person.level().getBlockEntity(pos) instanceof Container) {
          return pos;
        }
      }
    }
    // Already null rather than ZERO when there is nothing: the loop reads null
    // as "no work", and walking to world origin is not a haul.
    return LocationManager.getNearestContainerPos(person);
  }

  private int usedSlots(RealPerson person) {
    Container pack = person.personMainInv;
    int used = 0;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      if (!pack.getItem(slot).isEmpty()) {
        used++;
      }
    }
    return used;
  }

}
