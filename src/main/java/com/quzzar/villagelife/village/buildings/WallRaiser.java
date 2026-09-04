package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.quzzar.villagelife.savedata.PlacedBlockStore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Executes compiled wall cells against the live world.
 *
 * The catalog owns appearance, {@link WallProject} owns progress and claims,
 * and this class owns the world-sensitive questions: terrain, occupancy,
 * placement and the exact material bill.
 */
public final class WallRaiser {

  private WallRaiser() {
  }

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

  /** Exact number of wall-material blocks the current world still needs. */
  public static int requiredBlocks(Level level, List<Long> ring, Set<Long> gates,
      List<Integer> ground, WallTier tier) {
    WallProject plan = new WallProject(ring, gates, ground, tier);
    int required = 0;
    for (int i = 0; i < plan.sectionCount(); i++) {
      for (WallBlockPlan block : plan.section(i).blocks()) {
        if (!isSatisfied(level, block, tier)) {
          required++;
        }
      }
    }
    return required;
  }

  /** The exact construction cell currently leased to a builder. */
  public record WallWork(int section, WallBlockPlan block) {
  }

  /**
   * Claims a nearby section and skips cells the world already satisfies. This
   * is the wall equivalent of a building project's next template block.
   */
  @Nullable
  public static WallWork nextWork(Level level, WallProject wall, UUID builder, BlockPos from) {
    int attempts = Math.max(1, wall.sectionCount());
    for (int attempt = 0; attempt < attempts; attempt++) {
      int sectionIndex = wall.claimSection(builder, from, level.getGameTime());
      if (sectionIndex < 0) {
        return null;
      }
      WallSection section = wall.section(sectionIndex);
      while (!section.isComplete() && isSatisfied(level, section.next(), wall.getTier())) {
        wall.advance(builder, sectionIndex);
      }
      if (!section.isComplete()) {
        return new WallWork(sectionIndex, section.next());
      }
    }
    return null;
  }

  /** Whether this is still the cell a builder owns after travelling to it. */
  public static boolean isCurrent(WallProject wall, UUID builder, WallWork work) {
    return wall.owns(builder, work.section(), work.block());
  }

  /**
   * Every occupied wall cell cares first that collision closes it. Exact cells
   * get their authored state when the cell is open, but any existing solid is
   * preserved. Village-owned palisade is the one replaceable case during the
   * deliberate stone upgrade.
   */
  public static boolean isSatisfied(Level level, WallBlockPlan block, WallTier tier) {
    BlockPos pos = block.pos();
    BlockState state = level.getBlockState(pos);
    BlockState desired = block.desiredState(tier);
    if (state.equals(desired)) {
      return true;
    }
    PlacedBlockStore placed = level instanceof ServerLevel serverLevel
        ? PlacedBlockStore.get(serverLevel)
        : null;
    boolean hasCollision = !state.getCollisionShape(level, pos).isEmpty();
    boolean replacesPreviousTier = isVillageWoodBeingUpgraded(placed, pos, state, tier);
    return WallOccupancy.isSatisfied(hasCollision, replacesPreviousTier);
  }

  /** Places one planned cell, including the two inseparable halves of a door. */
  public static void place(Level level, WallBlockPlan block, WallTier tier) {
    BlockPos pos = block.pos();
    BlockState state = block.desiredState(tier);
    level.setBlock(pos, state, 3);
    markVillagePlaced(level, pos);
    if (block.piece().name().startsWith("DOOR_LOWER_")) {
      BlockPos upper = pos.above();
      level.setBlock(upper, state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
      markVillagePlaced(level, upper);
    }
  }

  /** Places a compiled project instantly for the dev preview command. */
  public static int placeAll(Level level, WallProject wall) {
    int placed = 0;
    for (int i = 0; i < wall.sectionCount(); i++) {
      for (WallBlockPlan block : wall.section(i).blocks()) {
        if (!isSatisfied(level, block, wall.getTier())) {
          place(level, block, wall.getTier());
          placed++;
        }
      }
    }
    return placed;
  }

  private static void markVillagePlaced(Level level, BlockPos pos) {
    if (level instanceof ServerLevel serverLevel) {
      PlacedBlockStore.get(serverLevel).markVillagePlaced(pos);
    }
  }

  /** Whether a stone project should replace this block from the prior wooden tier. */
  private static boolean isVillageWoodBeingUpgraded(PlacedBlockStore placed, BlockPos pos,
      BlockState state, WallTier tier) {
    return tier == WallTier.STONE
        && state.is(WallTier.WOOD.block())
        && placed != null
        && placed.isVillagePlaced(pos);
  }

}
