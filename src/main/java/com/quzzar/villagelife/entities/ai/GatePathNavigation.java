package com.quzzar.villagelife.entities.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Ground navigation for a person who can open fence gates.
 *
 * Vanilla reads a closed fence gate as a fence, so no mob ever plans a route
 * through one: a butchery pen with two gates and a door was, to its own
 * butcher, a yard with one exit, and every trip to the storehouse three
 * blocks beyond the fence became a thirty-block walk out through the
 * building and round it, which his pathfinder could not see the end of. He
 * stood at the fence until the loop gave up on him.
 *
 * A closed gate is now to a person what a closed wooden door is to a
 * villager: a node the route may pass through, at the door's cost, with the
 * opening left to {@link com.quzzar.villagelife.entities.ai.goals.OpenFenceGateGoal}
 * when they reach it. Nothing else about walking changes; the evaluator is
 * vanilla's with one answer corrected, and the door rules (no diagonal moves
 * through it, orthogonal approach only) apply to gates unchanged because
 * they hang off the node type, not the block.
 */
public final class GatePathNavigation extends GroundPathNavigation {

  public GatePathNavigation(Mob mob, Level level) {
    super(mob, level);
  }

  @Override
  protected PathFinder createPathFinder(int maxVisitedNodes) {
    this.nodeEvaluator = new GateNodeEvaluator();
    this.nodeEvaluator.setCanPassDoors(true);
    return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
  }

  /** Vanilla's walking evaluator, reading a closed gate as a closed wooden door. */
  private static final class GateNodeEvaluator extends WalkNodeEvaluator {

    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
      PathType type = super.getPathType(context, x, y, z);
      if (type == PathType.FENCE
          && context.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof FenceGateBlock) {
        return PathType.DOOR_WOOD_CLOSED;
      }
      return type;
    }
  }
}
