package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

  /**
   * The y a block sits at to rest on the real ground of this column. Trees,
   * brush and placed structures are obstacles over the terrain, not terrain:
   * reading the top of a trunk as ground made a wall target the top of a tree.
   */
  public static int surfaceY(Level level, int x, int z) {
    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    PlacedBlockStore placed = level instanceof ServerLevel serverLevel
        ? PlacedBlockStore.get(serverLevel)
        : null;
    return WallTerrain.surfaceY(top, level.getMinBuildHeight(), y -> {
      cursor.set(x, y, z);
      BlockState state = level.getBlockState(cursor);
      return !state.is(SitePreparation.CLEARABLE)
          && (placed == null || (!placed.isPlayerPlaced(cursor) && !placed.isVillagePlaced(cursor)))
          && state.isFaceSturdy(level, cursor, Direction.UP);
    });
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
   * alternate columns, and seats its foot over any void below the floor. A gate leaves
   * its two doorway courses at {@code base} to the caller, so it can install the door
   * directly while the surrounding column remains solid.
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

  /** Resolves the current project's live segment geometry, including old-save correction. */
  public static int[] currentSegmentRange(Level level, WallProject wall) {
    long column = wall.nextColumn();
    int x = BlockPos.getX(column);
    int z = BlockPos.getZ(column);
    int base;
    int floor;
    if (wall.hasGround()) {
      wall.lowerCurrentGround(surfaceY(level, x, z));
      int index = wall.currentIndex();
      base = wall.groundAt(index);
      floor = Math.min(base, wall.seamFloor(index));
    } else {
      base = surfaceY(level, x, z);
      floor = base;
    }
    return segmentRange(level, x, z, wall.getTier(), wall.isGate(column), base, floor);
  }

  /**
   * Planned heights that still need a wall block. Any collidable block already
   * closes that part of the barrier and is preserved. A stone upgrade replaces
   * only village-owned wooden wall blocks, not natural or player construction.
   * Doorway cells are omitted because {@link #placeGateDoor} fills them.
   */
  public static List<Integer> missingHeights(Level level, int x, int z, int[] range,
      WallTier tier, boolean gate) {
    PlacedBlockStore placed = level instanceof ServerLevel serverLevel
        ? PlacedBlockStore.get(serverLevel)
        : null;
    return WallOccupancy.missingHeights(range[0], range[1], y -> {
      if (gate && (y == range[2] || y == range[2] + 1)) {
        return true;
      }
      BlockPos pos = new BlockPos(x, y, z);
      BlockState state = level.getBlockState(pos);
      boolean hasCollision = !state.getCollisionShape(level, pos).isEmpty();
      boolean replacesPreviousTier = isVillageWoodBeingUpgraded(placed, pos, state, tier);
      return WallOccupancy.isSatisfied(hasCollision, replacesPreviousTier);
    });
  }

  /** Exact number of wall-material blocks the current world still needs. */
  public static int requiredBlocks(Level level, List<Long> ring, Set<Long> gates,
      List<Integer> ground, WallTier tier) {
    int required = 0;
    for (int i = 0; i < ring.size(); i++) {
      long column = ring.get(i);
      int x = BlockPos.getX(column);
      int z = BlockPos.getZ(column);
      boolean gate = gates.contains(column);
      int base = ground.get(i);
      int[] range = segmentRange(level, x, z, tier, gate, base, seamFloor(ground, i));
      if (range != null) {
        required += missingHeights(level, x, z, range, tier, gate).size();
      }
    }
    return required;
  }

  /** Places only the missing blocks in a segment; returns how many were placed. */
  public static int place(Level level, int x, int z, List<Integer> missing, WallTier tier) {
    BlockState state = tier.block().defaultBlockState();
    for (int y : missing) {
      BlockPos pos = new BlockPos(x, y, z);
      level.setBlock(pos, state, 3);
      // The wall is the village's: the wood tier's logs must never read as trees.
      if (level instanceof ServerLevel serverLevel) {
        PlacedBlockStore.get(serverLevel).markVillagePlaced(pos);
      }
    }
    return missing.size();
  }

  /** Whether a stone project should replace this block from the prior wooden tier. */
  private static boolean isVillageWoodBeingUpgraded(PlacedBlockStore placed, BlockPos pos,
      BlockState state, WallTier tier) {
    return tier == WallTier.STONE
        && state.is(WallTier.WOOD.block())
        && placed != null
        && placed.isVillagePlaced(pos);
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
