package com.quzzar.kithkyn.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.quzzar.kithkyn.Utils;
import com.quzzar.kithkyn.village.JobAssignment;
import com.quzzar.kithkyn.village.Occupation;
import com.quzzar.kithkyn.village.Village;
import com.quzzar.kithkyn.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The village's money: real emeralds in the market's own chests
 * (docs/economy.md). Deliberately physical, like everything else here - it is
 * visible when you walk in, it can be given, and it can be stolen.
 *
 * A village with no market has no treasury and cannot trade at all, and a
 * market with no merchant in it is a building rather than a business. Both
 * gates live here so every caller inherits them.
 */
public final class Treasury {

  /** Category id of the building that holds the treasury. */
  public static final String MARKET = "market";

  private Treasury() {
  }

  /** The village's market, if it has built one. */
  public static Optional<Building> market(Village village) {
    for (Building building : village.getBuildings()) {
      if (MARKET.equals(building.getInfo().getCategory())) {
        return Optional.of(building);
      }
    }
    return Optional.empty();
  }

  /** True when a living villager actually holds the market's merchant post. */
  public static boolean hasMerchant(Village village, ServerLevel level) {
    Optional<Building> market = market(village);
    if (market.isEmpty()) {
      return false;
    }
    for (var personId : village.getPopulation()) {
      JobAssignment job = village.getJobAssignment(personId);
      if (job != null && job.getOccupation() == Occupation.MERCHANT
          && job.getBuildingUUID().equals(market.get().getUUID())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether this village can trade at all: a market, staffed. The reason is
   * returned rather than a bare false so callers can say why.
   */
  public static Optional<String> tradeBlocker(Village village, ServerLevel level) {
    if (market(village).isEmpty()) {
      return Optional.of("no market");
    }
    if (!hasMerchant(village, level)) {
      return Optional.of("no merchant");
    }
    return Optional.empty();
  }

  /**
   * What the village counts as money. It is never merchandise: a stall that
   * offered its own treasury would be selling the means of payment, and the
   * price it put on a gem would not be one emerald.
   */
  public static final Item CURRENCY = Items.EMERALD;

  /** Where the market keeps its money, for inspection when a stall misbehaves. */
  public static List<BlockPos> chestPositions(Village village, ServerLevel level) {
    List<BlockPos> positions = new ArrayList<>();
    Optional<Building> market = market(village);
    if (market.isEmpty()) {
      return positions;
    }
    Building building = market.get();
    for (long offset : building.getInfo().getContainerLocations()) {
      BlockPos pos = BlockPos.of(building.getOriginLocation())
          .offset(BlockPos.of(offset).rotate(building.getRotation()));
      if (level.getBlockEntity(pos) instanceof Container) {
        positions.add(pos);
      }
    }
    return positions;
  }

  /** Every container belonging to the market building. */
  public static List<Container> chests(Village village, ServerLevel level) {
    List<Container> containers = new ArrayList<>();
    Optional<Building> market = market(village);
    if (market.isEmpty()) {
      return containers;
    }
    Building building = market.get();
    for (long offset : building.getInfo().getContainerLocations()) {
      BlockPos pos = BlockPos.of(building.getOriginLocation())
          .offset(BlockPos.of(offset).rotate(building.getRotation()));
      BlockEntity entity = level.getBlockEntity(pos);
      if (entity instanceof Container container) {
        containers.add(container);
      }
    }
    return containers;
  }

  /** Emeralds currently in the market's chests. */
  public static int balance(Village village, ServerLevel level) {
    int total = 0;
    for (Container container : chests(village, level)) {
      total += Utils.getAmountOfItemType(container, Items.EMERALD);
    }
    return total;
  }

  /**
   * Takes up to {@code amount} emeralds out, returning how many were actually
   * taken: a village that cannot pay in full pays what it has, and callers
   * check before committing to a trade.
   */
  public static int withdraw(Village village, ServerLevel level, int amount) {
    int remaining = amount;
    for (Container container : chests(village, level)) {
      if (remaining <= 0) {
        break;
      }
      ItemStack taken = Utils.removeItem(container, Items.EMERALD, remaining);
      remaining -= taken.getCount();
    }
    return amount - remaining;
  }

  /** Puts emeralds in, returning how many did not fit. */
  public static int deposit(Village village, ServerLevel level, int amount) {
    int remaining = amount;
    for (Container container : chests(village, level)) {
      if (remaining <= 0) {
        break;
      }
      remaining = insert(container, new ItemStack(Items.EMERALD, remaining));
    }
    return remaining;
  }

  /** Fills existing stacks first, then empty slots; returns what is left over. */
  /**
   * Puts goods into the market's own chests, returning what would not fit.
   * The public way in: a caller doing the village's side of a trade needs this
   * and has no business reaching for a single container.
   */
  public static ItemStack store(Village village, ServerLevel level, ItemStack stack) {
    ItemStack remaining = stack.copy();
    for (Container container : chests(village, level)) {
      if (remaining.isEmpty()) {
        break;
      }
      int leftover = insert(container, remaining);
      remaining = leftover <= 0 ? ItemStack.EMPTY : remaining.copyWithCount(leftover);
    }
    return remaining;
  }

  static int insert(Container container, ItemStack stack) {
    int remaining = stack.getCount();
    for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
      ItemStack existing = container.getItem(slot);
      if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) {
        continue;
      }
      int room = Math.min(existing.getMaxStackSize(), container.getMaxStackSize()) - existing.getCount();
      int moved = Math.min(room, remaining);
      if (moved > 0) {
        existing.grow(moved);
        container.setItem(slot, existing);
        remaining -= moved;
      }
    }
    for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
      if (!container.getItem(slot).isEmpty()) {
        continue;
      }
      int moved = Math.min(Math.min(stack.getMaxStackSize(), container.getMaxStackSize()), remaining);
      ItemStack placed = stack.copy();
      placed.setCount(moved);
      container.setItem(slot, placed);
      remaining -= moved;
    }
    container.setChanged();
    return remaining;
  }
}
