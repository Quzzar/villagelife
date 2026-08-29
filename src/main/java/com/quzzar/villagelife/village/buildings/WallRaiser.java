package com.quzzar.villagelife.village.buildings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The geometry of a single wall segment (docs/walls.md), shared by the builder's
 * {@code WallStep}, which raises one column at a time as it walks the ring, and
 * the dev command, which rings a village all at once to inspect the result.
 * Holding the shape of a segment in one place is what lets those two agree on
 * what a wall looks like.
 */
public final class WallRaiser {

  private WallRaiser() {
  }

  /** How far a column reaches down to meet the ground where the land falls away. */
  private static final int MAX_STEP_FILL = 4;

  /** The open height left at a gateway, so a villager can walk through it. */
  private static final int GATE_CLEARANCE = 2;

  /** The y a block sits at to rest on the ground of this column. */
  public static int surfaceY(Level level, int x, int z) {
    return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
  }

  /**
   * The vertical span a segment fills at this column, as {@code {lo, hi, base}},
   * or null when there is nothing to place. A solid run reaches down to close a
   * slope seam and crenellates the stone with a merlon on alternate columns; a
   * gateway leaves a doorway open below and carries the wall over it as a lintel.
   */
  public static int[] segmentRange(Level level, int x, int z, WallTier tier, boolean gate) {
    int base = surfaceY(level, x, z);
    int top = base + tier.height() - 1;
    int lo;
    int hi;
    if (gate) {
      lo = base + GATE_CLEARANCE;
      hi = top;
    } else {
      lo = base;
      for (int d = 1; d <= MAX_STEP_FILL; d++) {
        BlockPos below = new BlockPos(x, base - d, z);
        if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
          break;
        }
        lo = base - d;
      }
      hi = tier.crenellated() && Math.floorMod(x + z, 2) == 0 ? top + 1 : top;
    }
    return hi < lo ? null : new int[] {lo, hi, base};
  }

  /** Places a segment's blocks over its range; returns how many were placed. */
  public static int place(Level level, int x, int z, int[] range, WallTier tier) {
    BlockState state = tier.block().defaultBlockState();
    for (int y = range[0]; y <= range[1]; y++) {
      level.setBlock(new BlockPos(x, y, z), state, 3);
    }
    return range[1] - range[0] + 1;
  }

  /**
   * Hangs a wooden door in a gateway (docs/walls.md). Our villagers already open
   * and close doors as they path (Person sets canOpenDoors, RealPerson runs an
   * OpenDoorGoal), so a plain door is a gate that shuts behind them at night and
   * that mobs, bar a hard-difficulty zombie, cannot work. It faces the town so it
   * opens inward.
   */
  public static void placeGateDoor(Level level, int x, int z, int base, Direction facing) {
    BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
        .setValue(DoorBlock.FACING, facing)
        .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
        .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
        .setValue(DoorBlock.OPEN, Boolean.FALSE)
        .setValue(DoorBlock.POWERED, Boolean.FALSE);
    level.setBlock(new BlockPos(x, base, z), lower, 3);
    level.setBlock(new BlockPos(x, base + 1, z),
        lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
  }

  /** The horizontal direction from a gate column toward a point, so a gate faces the town. */
  public static Direction gateFacing(int x, int z, BlockPos toward) {
    int dx = toward.getX() - x;
    int dz = toward.getZ() - z;
    return Math.abs(dx) >= Math.abs(dz)
        ? (dx >= 0 ? Direction.EAST : Direction.WEST)
        : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
  }
}
