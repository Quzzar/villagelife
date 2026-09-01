package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.List;

import com.quzzar.villagelife.savedata.PlacedBlockStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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

  /** How far a column reaches down to seat its foot over a void below the seam floor. */
  private static final int MAX_STEP_FILL = 4;

  /** The y a block sits at to rest on the ground of this column. */
  public static int surfaceY(Level level, int x, int z) {
    return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
  }

  /**
   * Each ring column's natural ground height, read once before any wall is placed.
   * Seam-closing drops a column to a lower neighbour's ground, so it has to know
   * that neighbour's ORIGINAL surface: read live during the build, a neighbour
   * already raised reads its own wall back as the surface and the profile creeps.
   * Captured up front and kept on the {@link WallProject}, it stays the true ground.
   */
  public static List<Integer> groundProfile(Level level, List<Long> ring) {
    List<Integer> ground = new ArrayList<>(ring.size());
    for (long column : ring) {
      ground.add(surfaceY(level, BlockPos.getX(column), BlockPos.getZ(column)));
    }
    return ground;
  }

  /**
   * The lowest ground of a ring column and its two neighbours (the ring is a closed
   * loop). A segment drops its foot to this floor so it overlaps a lower neighbour
   * and leaves no vertical seam where the ground steps down (docs/walls.md, "No
   * gaps"): the older run only closed a void it hung over, never a solid step.
   */
  public static int seamFloor(List<Integer> ground, int index) {
    int n = ground.size();
    int prev = ground.get((index - 1 + n) % n);
    int next = ground.get((index + 1) % n);
    return Math.min(ground.get(index), Math.min(prev, next));
  }

  /**
   * The vertical span a segment fills at this column, as {@code {lo, hi, base}}, or
   * null when there is nothing to place. {@code base} is the column's own ground and
   * {@code floor} the seam floor from its neighbours. The run reaches from that floor
   * up to a tier's height above the ground, crenellating the stone with a merlon on
   * alternate columns, and seats its foot over any void below the floor. A gate fills
   * solid like any column; the caller stamps the door over the two courses at
   * {@code base}, so the doorway is flanked and capped by solid wall rather than air.
   */
  public static int[] segmentRange(Level level, int x, int z, WallTier tier, boolean gate,
      int base, int floor) {
    int top = base + tier.height() - 1;
    int hi = !gate && tier.crenellated() && Math.floorMod(x + z, 2) == 0 ? top + 1 : top;
    int lo = floor;
    for (int d = 1; d <= MAX_STEP_FILL; d++) {
      BlockPos below = new BlockPos(x, lo - 1, z);
      if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
        break;
      }
      lo--;
    }
    return hi < lo ? null : new int[] {lo, hi, base};
  }

  /** Places a segment's blocks over its range; returns how many were placed. */
  public static int place(Level level, int x, int z, int[] range, WallTier tier) {
    BlockState state = tier.block().defaultBlockState();
    for (int y = range[0]; y <= range[1]; y++) {
      BlockPos pos = new BlockPos(x, y, z);
      level.setBlock(pos, state, 3);
      // The wall is the village's: the wood tier's logs must never read as trees.
      if (level instanceof ServerLevel serverLevel) {
        PlacedBlockStore.get(serverLevel).markVillagePlaced(pos);
      }
    }
    return range[1] - range[0] + 1;
  }

  /**
   * Hangs a wooden door in a gateway (docs/walls.md), over the two solid courses the
   * segment placed at the ground. Our villagers already open and close doors as they
   * path (Person sets canOpenDoors, RealPerson runs an OpenDoorGoal), so a plain door
   * is a gate that shuts behind them at night and that mobs, bar a hard-difficulty
   * zombie, cannot work. It faces the town so it opens inward.
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
    if (level instanceof ServerLevel serverLevel) {
      PlacedBlockStore store = PlacedBlockStore.get(serverLevel);
      store.markVillagePlaced(new BlockPos(x, base, z));
      store.markVillagePlaced(new BlockPos(x, base + 1, z));
    }
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
