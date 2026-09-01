package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.economy.Treasury;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.ShelvingPlan;
import com.quzzar.villagelife.village.Storehouse;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.buildings.Building;
import com.quzzar.villagelife.village.buildings.BuildingInfo;

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
 * Three kinds of chest are never swept. The storehouse's own containers are the
 * destination, not a source; the market's chests hold the treasury and the
 * merchant's goods (a quartermaster shelving those would move the village's money
 * into a barrel and fight the merchant); and HOMES hold villagers' private
 * belongings, so their chests are left alone.
 *
 * When the shelves fall quiet the quartermaster tidies the storehouse, ordering
 * like goods together and compacting partial stacks, so a full sweep leaves one
 * organised store rather than a heap.
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
  /** Raised by a delivery, cleared by the next idle tidy: the shelves changed. */
  private boolean needsTidy;

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
      // nothing to move. One organise pass when the shelves first fall quiet
      // after a delivery, then standing is bounded so the legs come free again.
      if (this.mindingTicks == 0 && this.needsTidy) {
        tidyStorehouse(person);
        this.needsTidy = false;
      }
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
  public String activity() {
    return "sorting the storehouse shelves";
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
    for (BlockPos other : Storehouse.chests(person)) {
      if (!other.equals(target)) {
        stores.add(other);
      }
    }
    int moved = 0;
    for (BlockPos pos : stores) {
      if (!(person.level().getBlockEntity(pos) instanceof Container into)) {
        continue;
      }
      for (int slot = 0; slot < pack.getContainerSize(); slot++) {
        ItemStack stack = pack.getItem(slot);
        if (stack.isEmpty()) {
          continue;
        }
        int before = stack.getCount();
        pack.setItem(slot, HopperBlockEntity.addItem(pack, into, stack, null));
        moved += before - pack.getItem(slot).getCount();
      }
    }
    if (moved > 0) {
      this.needsTidy = true;
      Villagelife.LOGGER.debug("[resource-flow] {} (QUARTERMASTER) consolidated {} item(s) into the storehouse at {}",
          person.getName().getString(), moved, target.toShortString());
    }
    if (village != null) {
      village.setStorageStrained(!packIsEmpty(pack));
    }
  }

  /**
   * Tidy the storehouse when it is quiet: lift every stack out and lay it back
   * shelved. With a {@link ShelvingPlan} each item goes to its category's chest
   * (spilling to the others only when that chest is full); with no plan yet, like
   * goods are simply ordered together as before.
   * {@link HopperBlockEntity#addItem} merges compatible stacks as it refills,
   * compacting partial stacks and respecting item components, so enchanted or
   * named items are never wrongly merged. Count-preserving: exactly what came out
   * goes back into the same shelves, and merging only ever frees room, so it
   * always fits. Public and static so the plan prototype command can apply a
   * fresh plan at once, rather than waiting for the next quiet spell.
   */
  public static void tidyStorehouse(RealPerson person) {
    Village village = person.getVillage();
    ShelvingPlan plan = village == null ? null : ShelvingPlan.load(village);
    if (plan != null) {
      // A plan owns the slot layout; the storehouse lays itself out to match it.
      Storehouse.applyPlan(person, plan);
      Villagelife.LOGGER.debug("[quartermaster] {} shelved the storehouse to plan",
          person.getName().getString());
      return;
    }

    // No plan yet: the original behaviour, order like goods together and compact.
    List<Container> stores = new ArrayList<>();
    for (BlockPos pos : Storehouse.chests(person)) {
      if (person.level().getBlockEntity(pos) instanceof Container store) {
        stores.add(store);
      }
    }
    if (stores.isEmpty()) {
      return;
    }
    List<ItemStack> goods = new ArrayList<>();
    for (Container store : stores) {
      for (int slot = 0; slot < store.getContainerSize(); slot++) {
        ItemStack stack = store.getItem(slot);
        if (!stack.isEmpty()) {
          goods.add(stack.copy());
          store.setItem(slot, ItemStack.EMPTY);
        }
      }
    }
    goods.sort(Comparator.comparing((ItemStack stack) -> stack.getItem().toString()));
    for (ItemStack stack : goods) {
      reseat(stores, stack);
    }
    if (goods.size() > 1) {
      Villagelife.LOGGER.debug("[quartermaster] {} tidied the storehouse ({} stacks)",
          person.getName().getString(), goods.size());
    }
  }

  /** Lay a stack back across the storehouse, merging where it can; warns only if nothing fits. */
  private static void reseat(List<Container> stores, ItemStack stack) {
    ItemStack remaining = stack;
    for (Container store : stores) {
      if (remaining.isEmpty()) {
        return;
      }
      remaining = HopperBlockEntity.addItem(null, store, remaining, null);
    }
    if (!remaining.isEmpty()) {
      Villagelife.LOGGER.warn("[quartermaster] storehouse tidy could not reseat {} x{}",
          remaining.getItem(), remaining.getCount());
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
   * The nearest village container that is neither the storehouse, the market, nor
   * a home, and actually holds something. Stale positions - a chest broken or
   * built over - are skipped rather than walked to.
   */
  @Nullable
  private BlockPos sourceChest(RealPerson person, Village village) {
    if (!(person.level() instanceof ServerLevel level)) {
      return null;
    }
    List<BlockPos> skip = new ArrayList<>(Storehouse.chests(person));
    skip.addAll(Treasury.chestPositions(village, level));
    // Homes hold villagers' private belongings; never sweep them into the shared
    // storehouse (a home is a residence - a building with beds).
    skip.addAll(homeChests(village));
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

  /**
   * Container positions inside HOMES, which the quartermaster leaves alone. A home
   * is a residence - a building with beds, where a villager lives - so its chest
   * is personal, not part of the shared stores.
   */
  private List<BlockPos> homeChests(Village village) {
    List<BlockPos> out = new ArrayList<>();
    for (Building building : village.getBuildings()) {
      if (building == null || !isHome(building)) {
        continue;
      }
      BlockPos origin = BlockPos.of(building.getOriginLocation());
      for (Long local : building.getInfo().getContainerLocations()) {
        out.add(origin.offset(BlockPos.of(local).rotate(building.getRotation())));
      }
    }
    return out;
  }

  /** A home is a residence: a building with beds. */
  private boolean isHome(Building building) {
    BuildingInfo info = building.getInfo();
    return info != null && !info.getBedLocations().isEmpty();
  }

  /** The first storehouse container that exists, as the walk target for a delivery. */
  @Nullable
  private BlockPos storehouseChest(RealPerson person) {
    for (BlockPos pos : Storehouse.chests(person)) {
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
