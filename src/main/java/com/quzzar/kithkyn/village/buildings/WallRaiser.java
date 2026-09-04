package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.savedata.PlacedBlockStore;
import com.quzzar.kithkyn.village.TreeFelling;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Executes compiled wall cells against the live world.
 *
 * The catalog owns appearance, {@link WallProject} owns progress and claims,
 * and this class owns the world-sensitive questions: terrain, occupancy,
 * placement and the exact material bill.
 */
public final class WallRaiser {

  /** Natural trunks this close are felled before the final foliage sweep. */
  private static final int TREE_CLEARANCE_RADIUS = 3;

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
   * Plans a deck against land or the open waterline, whichever is higher. The
   * separate natural-ground profile still lets the wall seal down to the seabed.
   */
  public static List<Integer> deckProfile(Level level, List<Long> ring,
      List<Integer> ground, int wallHeight) {
    if (ring.size() != ground.size()) {
      throw new IllegalArgumentException(
          "Wall ring and ground profile must have the same length");
    }
    List<Integer> defensiveSurface = new ArrayList<>(ring.size());
    for (int index = 0; index < ring.size(); index++) {
      long column = ring.get(index);
      int naturalGround = ground.get(index);
      defensiveSurface.add(WallTerrain.defensiveSurfaceY(
          naturalGround, BlockPos.getX(column), BlockPos.getZ(column),
          (x, z) -> waterSurfaceY(level, x, z, naturalGround)));
    }
    return WallTerraces.deckProfile(ground, defensiveSurface, wallHeight);
  }

  /** First air block above open water, or the natural ground on a dry column. */
  private static int waterSurfaceY(Level level, int x, int z, int naturalGround) {
    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    PlacedBlockStore placed = level instanceof ServerLevel serverLevel
        ? PlacedBlockStore.get(serverLevel)
        : null;
    for (int y = top - 1; y >= naturalGround; y--) {
      cursor.set(x, y, z);
      BlockState state = level.getBlockState(cursor);
      if (!state.getFluidState().isEmpty()) {
        return y + 1;
      }
      boolean owned = placed != null
          && (placed.isPlayerPlaced(cursor) || placed.isVillagePlaced(cursor));
      if (state.isAir() || state.is(SitePreparation.CLEARABLE) || owned) {
        continue;
      }
      break;
    }
    return naturalGround;
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
    return requiredBlocks(level, ring, gates, ground, tier, VillageStyle.PLAINS);
  }

  /** Exact material count using the village's saved regional wall palette. */
  public static int requiredBlocks(Level level, List<Long> ring, Set<Long> gates,
      List<Integer> ground, WallTier tier, VillageStyle style) {
    return requiredBlocks(level, ring, gates, ground, tier, style, Set.of());
  }

  public static int requiredBlocks(Level level, List<Long> ring, Set<Long> gates,
      List<Integer> ground, WallTier tier, VillageStyle style,
      Set<Long> towerExclusions) {
    List<Integer> deck = deckProfile(level, ring, ground, tier.height());
    WallProject plan = new WallProject(
        ring, gates, ground, deck, tier, style, towerExclusions);
    return requiredBlocks(level, plan);
  }

  /** Exact material count for one already compiled candidate project. */
  public static int requiredBlocks(Level level, WallProject plan) {
    int required = 0;
    for (int i = 0; i < plan.sectionCount(); i++) {
      for (WallBlockPlan block : plan.section(i).blocks()) {
        if (!isSatisfied(level, block, plan.getTier(), plan.getStyle())) {
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
      while (!section.isComplete()
          && isSatisfied(level, section.next(), wall.getTier(), wall.getStyle())) {
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
    return isSatisfied(level, block, tier, VillageStyle.PLAINS);
  }

  /** Whether a cell is satisfied under the village's regional wall palette. */
  public static boolean isSatisfied(Level level, WallBlockPlan block, WallTier tier,
      VillageStyle style) {
    BlockPos pos = block.pos();
    BlockState state = level.getBlockState(pos);
    BlockState desired = block.desiredState(tier, style);
    if (state.equals(desired)) {
      return true;
    }
    PlacedBlockStore placed = level instanceof ServerLevel serverLevel
        ? PlacedBlockStore.get(serverLevel)
        : null;
    boolean hasCollision = !state.getCollisionShape(level, pos).isEmpty();
    boolean replacesPreviousTier = isVillageWoodBeingUpgraded(placed, pos, state, tier);
    boolean isClearableVegetation = isNaturalClearable(level, pos, state);
    return WallOccupancy.isSatisfied(
        hasCollision, replacesPreviousTier, isClearableVegetation);
  }

  /** Places one planned cell. */
  public static void place(Level level, WallBlockPlan block, WallTier tier) {
    place(level, block, tier, VillageStyle.PLAINS);
  }

  /** Places one planned cell using the village's regional wall palette. */
  public static void place(Level level, WallBlockPlan block, WallTier tier,
      VillageStyle style) {
    BlockPos pos = block.pos();
    BlockState state = block.desiredState(tier, style);
    if (block.piece() == WallBlockPlan.Piece.POST
        || block.role() == WallCellRole.FOUNDATION) {
      extendFoundationToGround(level, pos, state);
    } else if (block.piece() == WallBlockPlan.Piece.BODY) {
      embedExposedSurface(level, pos, state);
    }
    level.setBlock(pos, state, 3);
    markVillagePlaced(level, pos);
  }

  /**
   * Rigid authored features can project several blocks away from the sampled
   * route. Resolve their foundations against the exact live terrain column so
   * a downhill corner cannot leave a watchtower or its access hanging in air.
   */
  private static void extendFoundationToGround(Level level, BlockPos foundation,
      BlockState state) {
    int surface = surfaceY(level, foundation.getX(), foundation.getZ());
    boolean embedSurface = shouldEmbedSurface(
        level, foundation.getX(), foundation.getZ(), surface);
    for (BlockPos support : foundationPositions(foundation, surface, embedSurface)) {
      BlockState existing = level.getBlockState(support);
      if (!existing.getCollisionShape(level, support).isEmpty()
          && !isNaturalClearable(level, support, existing)) {
        continue;
      }
      level.setBlock(support, state, 3);
      markVillagePlaced(level, support);
    }
  }

  static List<BlockPos> foundationPositions(BlockPos post, int surfaceY) {
    return foundationPositions(post, surfaceY, false);
  }

  /** Foundation cells optionally replace the exposed soil course below the surface. */
  static List<BlockPos> foundationPositions(BlockPos post, int surfaceY,
      boolean embedSurface) {
    List<BlockPos> positions = new ArrayList<>();
    int firstY = embedSurface ? surfaceY - 1 : surfaceY;
    for (int y = firstY; y < post.getY(); y++) {
      positions.add(new BlockPos(post.getX(), y, post.getZ()));
    }
    return List.copyOf(positions);
  }

  /** Embeds the first ordinary body cell without letting later cells dig repeatedly. */
  private static void embedExposedSurface(Level level, BlockPos wallBlock,
      BlockState wallState) {
    int surface = surfaceY(level, wallBlock.getX(), wallBlock.getZ());
    if (wallBlock.getY() != surface
        || !shouldEmbedSurface(level, wallBlock.getX(), wallBlock.getZ(), surface)) {
      return;
    }
    BlockPos soil = wallBlock.below();
    level.setBlock(soil, wallState, 3);
    markVillagePlaced(level, soil);
  }

  private static boolean shouldEmbedSurface(Level level, int x, int z, int surfaceY) {
    return shouldEmbedSoil(level, new BlockPos(x, surfaceY - 1, z));
  }

  private static boolean shouldEmbedSoil(Level level, BlockPos soil) {
    BlockState soilState = level.getBlockState(soil);
    PlacedBlockStore placed = level instanceof ServerLevel serverLevel
        ? PlacedBlockStore.get(serverLevel)
        : null;
    boolean isOwned = placed != null
        && (placed.isPlayerPlaced(soil) || placed.isVillagePlaced(soil));
    boolean hasExposedSide = false;
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos neighbor = soil.relative(direction);
      BlockState neighborState = level.getBlockState(neighbor);
      if (neighborState.getFluidState().isEmpty()
          && neighborState.getCollisionShape(level, neighbor).isEmpty()) {
        hasExposedSide = true;
        break;
      }
    }
    return WallTerrain.shouldEmbedSurface(
        soilState.is(BlockTags.DIRT), isOwned, hasExposedSide);
  }

  /** Places a compiled project instantly for the dev preview command. */
  public static int placeAll(Level level, WallProject wall) {
    int placed = 0;
    for (int i = 0; i < wall.sectionCount(); i++) {
      for (WallBlockPlan block : wall.section(i).blocks()) {
        if (!isSatisfied(level, block, wall.getTier(), wall.getStyle())) {
          place(level, block, wall.getTier(), wall.getStyle());
          placed++;
        }
      }
    }
    finishWall(level, wall);
    return placed;
  }

  /** Applies the terrain and vegetation cleanup shared by previews and builders. */
  public static void finishWall(Level level, WallProject wall) {
    fellTreesNearWall(level, wall);
    clearVegetationBuffer(level, wall);
    settleFoundations(level, wall);
  }

  /**
   * Uses the shared lumberjack rules to open a three-block tree line around the
   * wall. Only natural trees come down, and their wood remains in the world for
   * villagers to collect.
   */
  public static int fellTreesNearWall(Level level, WallProject wall) {
    if (!(level instanceof ServerLevel serverLevel)) {
      return 0;
    }
    Set<Long> treeLine = WallTerrain.horizontalReach(
        occupiedColumns(wall), TREE_CLEARANCE_RADIUS);
    List<TreeFelling.FelledTree> felled = TreeFelling.fellWithin(serverLevel, treeLine);
    for (TreeFelling.FelledTree tree : felled) {
      for (ItemStack drop : tree.drops()) {
        Block.popResource(serverLevel, tree.struck(), drop);
      }
    }
    return felled.size();
  }

  /**
   * Clears natural vegetation through the wall's own columns and one block on
   * both sides. Including occupied columns removes canopy above the palisade;
   * the vertical scan stops at the wall, real ground, or protected construction.
   */
  public static int clearVegetationBuffer(Level level, WallProject wall) {
    Set<Long> clearance = WallTerrain.horizontalReach(occupiedColumns(wall), 1);
    int cleared = 0;
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (long column : clearance) {
      int x = BlockPos.getX(column);
      int z = BlockPos.getZ(column);
      int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
      for (int y = top; y >= level.getMinBuildHeight(); y--) {
        cursor.set(x, y, z);
        BlockState state = level.getBlockState(cursor);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
          continue;
        }
        if (!isNaturalClearable(level, cursor, state)) {
          break;
        }
        level.removeBlock(cursor, false);
        cleared++;
      }
    }
    return cleared;
  }

  /** Every horizontal column occupied by the compiled wall, including features. */
  private static Set<Long> occupiedColumns(WallProject wall) {
    Set<Long> occupiedColumns = new java.util.HashSet<>();
    for (int sectionIndex = 0; sectionIndex < wall.sectionCount(); sectionIndex++) {
      for (WallBlockPlan block : wall.section(sectionIndex).blocks()) {
        BlockPos pos = block.pos();
        occupiedColumns.add(BlockPos.asLong(pos.getX(), 0, pos.getZ()));
      }
    }
    return Set.copyOf(occupiedColumns);
  }

  /**
   * Rechecks the finished silhouette after neighboring cells have cleared its
   * final sightlines. This catches a bank edge that was concealed while its
   * first post was placed, then became exposed later in the build.
   */
  public static void settleFoundations(Level level, WallProject wall) {
    java.util.Map<Long, BlockPos> bases = new java.util.HashMap<>();
    for (int sectionIndex = 0; sectionIndex < wall.sectionCount(); sectionIndex++) {
      for (WallBlockPlan block : wall.section(sectionIndex).blocks()) {
        if (!isStructuralFoundationPiece(block.piece())) {
          continue;
        }
        BlockPos base = block.pos();
        if (!isVillageStructuralBlock(level, base)) {
          continue;
        }
        while (base.getY() > level.getMinBuildHeight()
            && isVillageStructuralBlock(level, base.below())) {
          base = base.below();
        }
        long column = BlockPos.asLong(base.getX(), 0, base.getZ());
        bases.merge(column, base,
            (left, right) -> left.getY() <= right.getY() ? left : right);
      }
    }
    for (BlockPos base : bases.values()) {
      if (!shouldEmbedSoil(level, base.below())) {
        continue;
      }
      BlockPos soil = base.below();
      level.setBlock(soil, level.getBlockState(base), 3);
      markVillagePlaced(level, soil);
    }
  }

  private static boolean isVillageStructuralBlock(Level level, BlockPos pos) {
    return isVillagePlaced(level, pos)
        && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
  }

  private static boolean isStructuralFoundationPiece(WallBlockPlan.Piece piece) {
    return piece == WallBlockPlan.Piece.BODY
        || piece == WallBlockPlan.Piece.POST
        || piece == WallBlockPlan.Piece.BEAM_NORTH_SOUTH
        || piece == WallBlockPlan.Piece.BEAM_EAST_WEST;
  }

  private static void markVillagePlaced(Level level, BlockPos pos) {
    if (level instanceof ServerLevel serverLevel) {
      PlacedBlockStore.get(serverLevel).markVillagePlaced(pos);
    }
  }

  private static boolean isVillagePlaced(Level level, BlockPos pos) {
    return level instanceof ServerLevel serverLevel
        && PlacedBlockStore.get(serverLevel).isVillagePlaced(pos);
  }

  /** Whether a tree or plant is world vegetation rather than owned construction. */
  private static boolean isNaturalClearable(Level level, BlockPos pos, BlockState state) {
    if (!state.is(SitePreparation.CLEARABLE)) {
      return false;
    }
    if (!(level instanceof ServerLevel serverLevel)) {
      return true;
    }
    PlacedBlockStore placed = PlacedBlockStore.get(serverLevel);
    return !placed.isPlayerPlaced(pos) && !placed.isVillagePlaced(pos);
  }

  /** Whether a stone project should replace this block from the prior wooden tier. */
  private static boolean isVillageWoodBeingUpgraded(PlacedBlockStore placed, BlockPos pos,
      BlockState state, WallTier tier) {
    return tier == WallTier.STONE
        && WoodWallPalette.isWallWood(state.getBlock())
        && placed != null
        && placed.isVillagePlaced(pos);
  }

}
