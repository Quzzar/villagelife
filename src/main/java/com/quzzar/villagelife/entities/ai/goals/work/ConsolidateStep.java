package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.economy.Treasury;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * What a quartermaster does: consolidate the village's scattered stores into one
 * place (docs/worker-loops.md). A CARRY step with two phases, the mirror of
 * {@link MarketStep}.
 *
 * Every workplace keeps its own container - the barrel at the mine, the chest at
 * the lumberyard - so a village's goods end up spread across a dozen buildings.
 * The quartermaster sweeps those chests, carries the goods to the storehouse,
 * and empties them in, so anyone looking for anything has one organised place to
 * look. It changes nothing about what a village can AFFORD, since affordability
 * already reads every container at once; it makes the stores findable, and it
 * surfaces when they overflow.
 *
 * Two chests are never swept. The storehouse's own containers are the
 * destination, not a source, and the market's chests hold the treasury and the
 * merchant's goods - a quartermaster shelving those would move the village's
 * money into a barrel and fight the merchant, who is trying to clear the same
 * till the other way.
 *
 * A full storehouse is not an error. Whatever will not fit stays in the pack and
 * the strain flag goes up, which the planner reads as a reason to want a bigger
 * storehouse - a village that has outgrown its shelves.
 */
public final class ConsolidateStep implements BlockWorkStep {

  /** Of 36 slots. Collect at least this much before a delivery trip is worth it. */
  private static final int SLOTS_BEFORE_TRIP = 18;

  /** How long the quartermaster minds the storehouse when there is nothing to move. */
  private static final int MIND_TICKS = 200;

  /** Dead container positions to step past before giving up on a source. */
  private static final int MAX_STALE_CHESTS = 8;

  /** Set in select, read in act: which phase this target belongs to. */
  private boolean delivering;
  private int mindingTicks;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    Village village = person.getVillage();
    if (village == null) {
      return null;
    }
    this.mindingTicks = 0;

    int used = usedSlots(person);
    // Keep collecting until the pack is worth a trip; only then deliver. This
    // is what stops the quartermaster ping-ponging one stack at a time.
    BlockPos source = used >= SLOTS_BEFORE_TRIP ? null : sourceChest(person, village);
    if (source != null) {
      this.delivering = false;
      return source;
    }
    if (used > 0) {
      this.delivering = true;
      return storehouseChest(person);
    }
    // Nothing to carry and nothing to fetch: mind the storehouse, if there is one.
    BlockPos station = LocationManager.getJobLocation(person);
    return station.equals(BlockPos.ZERO) ? null : station;
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    if (this.delivering) {
      deliver(person, target);
      return false; // the next select decides whether to fetch more or deliver again
    }

    if (!(person.level().getBlockEntity(target) instanceof Container source)) {
      // The chest went, or this is the storehouse station and there is simply
      // nothing to move. Standing is bounded so the legs come free again.
      return ++this.mindingTicks < MIND_TICKS;
    }
    ItemStack taken = takeOneStack(person, source);
    return !taken.isEmpty(); // keep draining this chest while it still gives
  }

  @Override
  public String describe() {
    return "the storehouse";
  }

  @Override
  public double reachSqr(RealPerson person) {
    return 6.0D;
  }

  /**
   * Empty the pack into the storehouse, across all of its containers. Whatever
   * does not fit stays in the pack and raises the strain flag; a clean empty
   * lowers it, so the signal tracks the shelves rather than latching on.
   */
  private void deliver(RealPerson person, BlockPos target) {
    Village village = person.getVillage();
    Container pack = person.personMainInv;
    List<BlockPos> stores = new ArrayList<>();
    stores.add(target);
    for (BlockPos other : storehouseChests(person)) {
      if (!other.equals(target)) {
        stores.add(other);
      }
    }
    for (BlockPos pos : stores) {
      if (!(person.level().getBlockEntity(pos) instanceof Container into)) {
        continue;
      }
      for (int slot = 0; slot < pack.getContainerSize(); slot++) {
        ItemStack stack = pack.getItem(slot);
        if (stack.isEmpty()) {
          continue;
        }
        pack.setItem(slot, HopperBlockEntity.addItem(pack, into, stack, null));
      }
    }
    if (village != null) {
      village.setStorageStrained(!packIsEmpty(pack));
    }
  }

  /** Lift one stack out of a source chest and into the pack; empty if none fit. */
  private ItemStack takeOneStack(RealPerson person, Container source) {
    Container pack = person.personMainInv;
    for (int slot = 0; slot < source.getContainerSize(); slot++) {
      ItemStack stack = source.getItem(slot);
      if (stack.isEmpty()) {
        continue;
      }
      ItemStack leftover = HopperBlockEntity.addItem(source, pack, stack, null);
      source.setItem(slot, leftover);
      if (leftover.getCount() < stack.getCount()) {
        return stack; // moved at least some of it
      }
    }
    return ItemStack.EMPTY;
  }

  /**
   * The nearest village container that is neither the storehouse nor the market,
   * and actually holds something. Stale positions - a chest broken or built over
   * - are skipped rather than walked to.
   */
  @Nullable
  private BlockPos sourceChest(RealPerson person, Village village) {
    if (!(person.level() instanceof ServerLevel level)) {
      return null;
    }
    List<BlockPos> skip = new ArrayList<>(storehouseChests(person));
    skip.addAll(Treasury.chestPositions(village, level));
    for (int attempt = 0; attempt < MAX_STALE_CHESTS; attempt++) {
      BlockPos found = village.getNearestContainer(person.blockPosition(), skip);
      if (found.equals(BlockPos.ZERO)) {
        return null;
      }
      if (level.getBlockEntity(found) instanceof Container container && hasGoods(container)) {
        return found;
      }
      skip.add(found); // empty or gone: never a source
    }
    return null;
  }

  /** The storehouse's own containers: the quartermaster's workplace, in world space. */
  private List<BlockPos> storehouseChests(RealPerson person) {
    List<BlockPos> out = new ArrayList<>();
    Building building = LocationManager.getJobBuilding(person);
    if (building != null && building.getInfo() != null) {
      BlockPos origin = BlockPos.of(building.getOriginLocation());
      for (Long local : building.getInfo().getContainerLocations()) {
        out.add(origin.offset(BlockPos.of(local).rotate(building.getRotation())));
      }
    }
    return out;
  }

  /** The first storehouse container that exists, as the walk target for a delivery. */
  @Nullable
  private BlockPos storehouseChest(RealPerson person) {
    for (BlockPos pos : storehouseChests(person)) {
      if (person.level().getBlockEntity(pos) instanceof Container) {
        return pos;
      }
    }
    return null;
  }

  private boolean hasGoods(Container container) {
    for (int slot = 0; slot < container.getContainerSize(); slot++) {
      if (!container.getItem(slot).isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private boolean packIsEmpty(Container pack) {
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      if (!pack.getItem(slot).isEmpty()) {
        return false;
      }
    }
    return true;
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
