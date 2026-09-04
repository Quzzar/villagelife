package com.quzzar.kithkyn.village;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The storehouse's own containers, their flattened slots, and what sits in them.
 * Shared by the quartermaster's consolidation loop ({@code ConsolidateStep}) and
 * the shelving planner ({@link QuartermasterPlanner}) so both read one
 * definition of "the shelves", and it owns the one mapping between a plan's
 * global slot numbers and the physical (chest, slot) pairs they stand for.
 */
public final class Storehouse {

  private Storehouse() {
  }

  /**
   * World positions of the quartermaster's storehouse containers, in the
   * building's own container order. Empty when they have no workplace yet.
   */
  public static List<BlockPos> chests(RealPerson quartermaster) {
    List<BlockPos> out = new ArrayList<>();
    Building building = LocationManager.getJobBuilding(quartermaster);
    if (building != null && building.getInfo() != null) {
      BlockPos origin = BlockPos.of(building.getOriginLocation());
      for (Long local : building.getInfo().getContainerLocations()) {
        out.add(origin.offset(BlockPos.of(local).rotate(building.getRotation())));
      }
    }
    return out;
  }

  /** The loaded storehouse containers, in chest order; the plan's slot numbering runs across these. */
  public static List<Container> containers(RealPerson quartermaster) {
    List<Container> out = new ArrayList<>();
    for (BlockPos pos : chests(quartermaster)) {
      if (quartermaster.level().getBlockEntity(pos) instanceof Container container) {
        out.add(container);
      }
    }
    return out;
  }

  /** Total slot count across the storehouse chests: the size of the space a plan must partition. */
  public static int totalSlots(List<Container> containers) {
    int total = 0;
    for (Container container : containers) {
      total += container.getContainerSize();
    }
    return total;
  }

  /**
   * Distinct items across the storehouse mapped to their total count, in a
   * stable first-seen order. Empty when the shelves are bare or their chunk is
   * not loaded, so a caller can treat "nothing to organise" and "cannot see the
   * shelves" the same way.
   */
  public static LinkedHashMap<Item, Integer> snapshot(RealPerson quartermaster) {
    LinkedHashMap<Item, Integer> counts = new LinkedHashMap<>();
    for (Container store : containers(quartermaster)) {
      for (int slot = 0; slot < store.getContainerSize(); slot++) {
        ItemStack stack = store.getItem(slot);
        if (!stack.isEmpty()) {
          counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
      }
    }
    return counts;
  }

  /**
   * Lays the storehouse out to match a {@link ShelvingPlan}: every stack is
   * lifted, compacted by item, and set back into the slots its category owns, in
   * category order. Anything that overruns its category's slots, or belongs to
   * no category (an item the plan never saw), spills into whatever slots remain
   * free, so nothing is ever lost. Count-preserving: compaction only frees room,
   * so what came out always goes back.
   */
  public static void applyPlan(RealPerson quartermaster, ShelvingPlan plan) {
    List<Container> containers = containers(quartermaster);
    if (containers.isEmpty()) {
      return;
    }
    List<int[]> slots = new ArrayList<>(); // each entry: {container index, local slot}
    for (int c = 0; c < containers.size(); c++) {
      for (int s = 0; s < containers.get(c).getContainerSize(); s++) {
        slots.add(new int[] {c, s});
      }
    }
    int total = slots.size();

    // Lift everything, compacting identical stacks as we go, grouped by item id.
    LinkedHashMap<String, List<ItemStack>> byItem = new LinkedHashMap<>();
    for (int[] ref : slots) {
      Container container = containers.get(ref[0]);
      ItemStack stack = container.getItem(ref[1]);
      if (!stack.isEmpty()) {
        addCompacted(byItem, stack.copy());
        container.setItem(ref[1], ItemStack.EMPTY);
      }
    }

    boolean[] filled = new boolean[total];
    List<ItemStack> spill = new ArrayList<>();
    for (ShelvingPlan.Category category : plan.categories()) {
      int start = clamp(category.firstSlot(), total);
      int end = clamp(category.firstSlot() + category.slotCount(), total);
      int cursor = start;
      for (String id : category.itemIds()) {
        List<ItemStack> stacks = byItem.remove(id);
        if (stacks == null) {
          continue;
        }
        for (ItemStack stack : stacks) {
          if (cursor < end) {
            place(containers, slots.get(cursor), stack);
            filled[cursor] = true;
            cursor++;
          } else {
            spill.add(stack); // this category holds more than its slots; park it
          }
        }
      }
    }
    // Items the plan never named, plus any category overflow, go to free slots.
    for (List<ItemStack> remaining : byItem.values()) {
      spill.addAll(remaining);
    }
    int free = 0;
    for (ItemStack stack : spill) {
      while (free < total && filled[free]) {
        free++;
      }
      if (free < total) {
        place(containers, slots.get(free), stack);
        filled[free] = true;
        free++;
      } else {
        Kithkyn.LOGGER.warn("[quartermaster] storehouse has no free slot to reseat {} x{}",
            stack.getItem(), stack.getCount());
      }
    }
  }

  /** Merges a stack into the compacted list for its item, respecting components; appends the remainder. */
  private static void addCompacted(Map<String, List<ItemStack>> byItem, ItemStack stack) {
    List<ItemStack> list = byItem.computeIfAbsent(idOf(stack.getItem()), key -> new ArrayList<>());
    for (ItemStack existing : list) {
      if (ItemStack.isSameItemSameComponents(existing, stack) && existing.getCount() < existing.getMaxStackSize()) {
        int room = existing.getMaxStackSize() - existing.getCount();
        int move = Math.min(room, stack.getCount());
        existing.grow(move);
        stack.shrink(move);
        if (stack.isEmpty()) {
          return;
        }
      }
    }
    if (!stack.isEmpty()) {
      list.add(stack);
    }
  }

  private static void place(List<Container> containers, int[] ref, ItemStack stack) {
    containers.get(ref[0]).setItem(ref[1], stack);
  }

  private static int clamp(int slot, int total) {
    return Math.max(0, Math.min(slot, total));
  }

  static String idOf(Item item) {
    return BuiltInRegistries.ITEM.getKey(item).toString();
  }
}
