package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The shared mechanics of carrying things by hand: finding the chest that has
 * what a worker needs, lifting it into their pack, and finding the chest with
 * room for what they made (docs/worker-loops.md, "Nothing teleports").
 *
 * Every consuming loop used to pull its materials out of village storage from
 * wherever its worker stood - the items were real and correctly debited, but
 * the fetch trip was skipped, so a cook at the campfire was fed by a chest on
 * the other side of the village. The builder's {@link GatherStep} always did
 * this honestly; these helpers are its chest-finding and pack-filling mechanics
 * extracted so every other step can walk the same walk without re-writing them.
 *
 * All of it is pack-relative: "wanted" always means the amount still missing
 * from the worker's own inventory, so a half-loaded pack asks only for the
 * rest.
 */
final class PackLogistics {

  /** Dead container positions to step past before giving up on a search. */
  private static final int MAX_STALE_CHESTS = 12;

  private PackLogistics() {
  }

  /** How many of this item the worker already carries. */
  static int carried(RealPerson person, Item item) {
    return Utils.getAmountOfItemType(person.personMainInv, item);
  }

  /** Whether the pack is still short of any of these, counts and all. */
  static boolean packShort(RealPerson person, List<ItemStack> wanted) {
    for (ItemStack want : wanted) {
      if (carried(person, want.getItem()) < want.getCount()) {
        return true;
      }
    }
    return false;
  }

  /**
   * The nearest village container holding something the pack is still short
   * of, or null when no reachable chest has any of it.
   */
  @Nullable
  static BlockPos chestHolding(RealPerson person, Village village, List<ItemStack> wanted) {
    return nearestChest(person, village, chest -> {
      for (ItemStack want : wanted) {
        int need = want.getCount() - carried(person, want.getItem());
        if (need > 0 && Utils.getAmountOfItemType(chest, want.getItem()) > 0) {
          return true;
        }
      }
      return false;
    });
  }

  /**
   * The nearest village container with room for at least one of this item:
   * an empty slot, or a matching stack that is not full.
   */
  @Nullable
  static BlockPos chestWithRoomFor(RealPerson person, Village village, ItemStack sample) {
    return nearestChest(person, village, chest -> {
      for (int slot = 0; slot < chest.getContainerSize(); slot++) {
        ItemStack there = chest.getItem(slot);
        if (there.isEmpty()) {
          return true;
        }
        if (ItemStack.isSameItemSameComponents(there, sample)
            && there.getCount() < there.getMaxStackSize()) {
          return true;
        }
      }
      return false;
    });
  }

  /**
   * Lift what this chest can offer toward the still-missing part of the list
   * into the worker's pack. Returns how many items moved.
   */
  static int pullWanted(RealPerson person, Container chest, List<ItemStack> wanted, String role) {
    int pulledTotal = 0;
    for (ItemStack want : wanted) {
      int need = want.getCount() - carried(person, want.getItem());
      if (need <= 0) {
        continue;
      }
      ItemStack pulled = Utils.removeItem(chest, want.getItem(), need);
      if (!pulled.isEmpty()) {
        Utils.insertItems(person.personMainInv, List.of(pulled), person);
        pulledTotal += pulled.getCount();
      }
    }
    if (pulledTotal > 0) {
      Villagelife.LOGGER.debug("[resource-flow] {} ({}) fetched {} item(s) from a chest",
          person.getName().getString(), role, pulledTotal);
    }
    return pulledTotal;
  }

  /**
   * Set everything of this item the worker carries down into the chest,
   * dropping nothing: whatever does not fit stays in the pack for the next
   * trip. Returns how many items moved.
   */
  static int depositCarried(RealPerson person, Container chest, Item item, String role) {
    Container pack = person.personMainInv;
    int moved = 0;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      if (stack.isEmpty() || !stack.is(item)) {
        continue;
      }
      int before = stack.getCount();
      ItemStack leftover = net.minecraft.world.level.block.entity.HopperBlockEntity
          .addItem(pack, chest, stack, null);
      pack.setItem(slot, leftover);
      moved += before - leftover.getCount();
    }
    if (moved > 0) {
      Villagelife.LOGGER.debug("[resource-flow] {} ({}) shelved {} item(s) into a chest",
          person.getName().getString(), role, moved);
    }
    return moved;
  }

  /** The chest at this position, or null when it is gone or not loaded. */
  @Nullable
  static Container containerAt(RealPerson person, BlockPos pos) {
    return person.level().getBlockEntity(pos) instanceof Container chest ? chest : null;
  }

  /**
   * The nearest registered container that passes the test, walking the
   * village's nearest-first order and skipping the stale.
   */
  @Nullable
  private static BlockPos nearestChest(RealPerson person, Village village,
      java.util.function.Predicate<Container> test) {
    if (!(person.level() instanceof ServerLevel level)) {
      return null;
    }
    List<BlockPos> skip = new ArrayList<>();
    for (int attempt = 0; attempt < MAX_STALE_CHESTS; attempt++) {
      BlockPos found = village.getNearestContainer(person.blockPosition(), skip);
      if (found.equals(BlockPos.ZERO)) {
        return null;
      }
      if (level.getBlockEntity(found) instanceof Container chest && test.test(chest)) {
        return found;
      }
      skip.add(found);
    }
    return null;
  }
}
