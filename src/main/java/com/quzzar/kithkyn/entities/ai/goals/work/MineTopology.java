package com.quzzar.kithkyn.entities.ai.goals.work;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import com.quzzar.kithkyn.village.buildings.MineShaft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The mine's planned interior in mine-local coordinates.
 *
 * <p>The descending ramp and its horizontal prospecting ribs are one connected
 * volume. Construction, waterproofing, and ore exposure must agree on that
 * volume. In particular, the first cell of a rib is an intentional doorway
 * through the ramp wall, while a neighbour outside both shapes is mine lining.
 */
final class MineTopology {

  static final int FAN_LENGTH = MineShaft.RIB_LENGTH;
  static final int FAN_PITCH = MineShaft.RIB_PITCH;
  static final int FAN_HEIGHT = MineShaft.RIB_HEIGHT;
  static final int FAN_MIN_LINE = MineShaft.RIB_MIN_LINE;

  private MineTopology() {
  }

  /** The walk-cell Y shared by a ramp column and any rib cut from it. */
  static int floorY(int z) {
    return z < 0 ? -1 : -(z + 2);
  }

  /** The five-cell-high descending shaft, capped below the surface structure. */
  static boolean isRamp(BlockPos local) {
    return MineShaft.withinCorridor(local)
        && local.getY() >= -(local.getZ() + 2)
        && local.getY() <= -(local.getZ() - 2);
  }

  /** A one-cell-wide horizontal prospecting rib on one of the fixed grid lines. */
  static boolean isRib(BlockPos local) {
    return MineShaft.withinRib(local);
  }

  /**
   * The first cell of the rib outside the ramp wall, at the same height as a
   * flooded cell farther along that rib. This is the safe place for a temporary
   * bulkhead: it gives up the wet branch without filling or narrowing the ramp.
   */
  static BlockPos ribDoorway(BlockPos local) {
    if (!isRib(local)) {
      return null;
    }
    int side = Integer.signum(local.getX());
    return new BlockPos(side * (MineShaft.RADIUS + 1), local.getY(), local.getZ());
  }

  /** Every cell the mine deliberately opens, whether it belongs to the ramp or a rib. */
  static boolean isInterior(BlockPos local) {
    return isRamp(local) || isRib(local);
  }

  /**
   * Whether two cells belong to one independently advancing excavation front.
   *
   * <p>The descending ramp is one front. Each rib is its own front, identified
   * by its depth and side of the ramp. They remain one connected navigable mine,
   * but a flood at the end of one rib must not make supports in the ramp, the
   * opposite rib, or another depth look like part of that flood barrier.
   */
  static boolean sameFront(BlockPos first, BlockPos second) {
    boolean firstRamp = isRamp(first);
    boolean secondRamp = isRamp(second);
    if (firstRamp || secondRamp) {
      return firstRamp && secondRamp;
    }
    if (!isRib(first) || !isRib(second)) {
      return false;
    }
    return first.getZ() == second.getZ()
        && Integer.signum(first.getX()) == Integer.signum(second.getX());
  }

  /**
   * Whether the connected support cluster at {@code start} touches water on
   * that same excavation front. The supplied predicates keep this topology
   * rule independent of world storage and make the flooded-rib boundary
   * deterministic to test.
   */
  static boolean supportClusterTouchesWater(BlockPos start,
      Predicate<BlockPos> isPlacedSupport, Predicate<BlockPos> isWater, int maxCells) {
    Deque<BlockPos> frontier = new ArrayDeque<>();
    Set<BlockPos> seen = new HashSet<>();
    frontier.add(start);
    seen.add(start);
    while (!frontier.isEmpty() && seen.size() <= maxCells) {
      BlockPos local = frontier.poll();
      for (Direction direction : Direction.values()) {
        BlockPos next = local.relative(direction);
        if (!sameFront(start, next)) {
          continue;
        }
        if (isWater.test(next)) {
          return true;
        }
        if (isPlacedSupport.test(next) && seen.add(next)) {
          frontier.add(next);
        }
      }
    }
    return false;
  }

  /**
   * Whether two adjacent cells cross from the planned mine interior into its
   * exterior lining. The caller decides whether the exterior block's current
   * state is solid, air, or fluid.
   */
  static boolean crossesExteriorBoundary(BlockPos inside, BlockPos neighbour) {
    int distance = Math.abs(inside.getX() - neighbour.getX())
        + Math.abs(inside.getY() - neighbour.getY())
        + Math.abs(inside.getZ() - neighbour.getZ());
    return distance == 1 && isInterior(inside) && !isInterior(neighbour);
  }
}
