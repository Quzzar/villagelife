package com.quzzar.villagelife.village;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.savedata.PlacedBlockStore;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Bringing a natural tree down whole. One log is struck and every log
 * connected to it comes away at once, a bounded flood fill over the trunk and
 * its branches, each log yielding its drops. The leaves are left to decay on
 * their own schedule, as natural leaves do once their tree is gone.
 *
 * <b>It only ever cuts real trees.</b> Two guards keep it off the village's
 * own timber and off anything a player built:
 * <ul>
 * <li>A log counts as a tree only with a <i>natural</i> canopy nearby, leaves
 * whose {@code persistent} flag is false. Player-placed leaves are persistent
 * and a building's timber has none of its own, so this tells a living tree
 * from authored timber far more reliably than leaf-proximity alone did.</li>
 * <li>{@link BlockOwnership#mayFell} vetoes any block a player or a village
 * placed. The flood fill re-checks every log, so a wild tree overhanging a
 * roof drops its own wood and spares the building's.</li>
 * </ul>
 *
 * Two callers share it: the lumberjack's and guard's {@code ChopStep}, which
 * fells one tree per chop and pockets the wood, and building placement, which
 * fells whatever still stands over a building the moment it goes up
 * ({@link #fellOver}). Where the drops go is each caller's business. The one
 * tree the ownership guard does not cover is the lumberjack's own stand, the
 * lumberjack's to cut whatever the store says of it ({@link #fellStand}).
 */
public final class TreeFelling {

  /** Natural leaves this close mark a trunk as a living tree, not authored timber. */
  private static final int LEAF_RADIUS = 3;

  /** Most logs one fell removes: a ceiling for giant trees so the flood fill always ends. */
  private static final int TREE_LOG_CAP = 256;

  /** One tree brought down: the log it was struck at, and what its logs dropped. */
  public record FelledTree(BlockPos struck, List<ItemStack> drops) {
  }

  private TreeFelling() {
  }

  /**
   * Whether the block here is a log any feller may bring down: a burnable log
   * with a natural canopy near it, that nobody placed. Unloaded chunks are
   * never paged in to answer; a position in one is simply not fellable.
   */
  public static boolean isFellableLog(ServerLevel level, BlockPos pos) {
    return level.hasChunkAt(pos)
        && level.getBlockState(pos).is(BlockTags.LOGS_THAT_BURN)
        && hasNaturalCanopy(level, pos)
        && BlockOwnership.mayFell(level, pos);
  }

  /**
   * Brings the whole tree down from the struck log and returns what it
   * dropped. Each connected log the village and players do not own is removed
   * and its drops taken, as though {@code feller} broke it with {@code tool};
   * one break sound plays for the lot. A null feller with an empty tool is a
   * plain break, which is what site clearing does.
   */
  public static List<ItemStack> fell(ServerLevel level, BlockPos struck, @Nullable Entity feller,
      ItemStack tool) {
    return fell(level, struck, feller, tool, false);
  }

  /**
   * Brings down the tree at a lumberjack's stand. Ownership is not asked at
   * all here (Aaron, 2026-09-02): every log connected to the station comes
   * down, whoever placed it, whatever its height and however far its branches
   * reach, and each one's record goes with it. The lodge once shipped a grown
   * tree, stamped as the village's, and under {@link #fell} the lumberjack
   * struck that trunk, nothing came away, and the loop offered the same log
   * again; the lodge now ships a sapling, and the exemption stays so that
   * nothing anyone places against the stand can jam the loop. Otherwise
   * identical: whole tree, drops returned, leaves left to decay.
   */
  public static List<ItemStack> fellStand(ServerLevel level, BlockPos struck, @Nullable Entity feller,
      ItemStack tool) {
    return fell(level, struck, feller, tool, true);
  }

  /**
   * Every log removed has its ownership record dropped with it, as a break
   * does: the stand's next tree grows on the first one's positions, and must
   * not inherit the template's protection.
   */
  private static List<ItemStack> fell(ServerLevel level, BlockPos struck, @Nullable Entity feller,
      ItemStack tool, boolean stand) {
    List<ItemStack> drops = new ArrayList<>();
    BlockState soundFrom = null;
    PlacedBlockStore placed = PlacedBlockStore.get(level);
    for (BlockPos pos : treeLogs(level, struck)) {
      BlockState state = level.getBlockState(pos);
      if (!state.is(BlockTags.LOGS_THAT_BURN) || (!stand && !BlockOwnership.mayFell(level, pos))) {
        continue;
      }
      drops.addAll(Block.getDrops(state, level, pos, level.getBlockEntity(pos), feller, tool));
      if (soundFrom == null) {
        soundFrom = state;
      }
      level.removeBlock(pos, false);
      placed.clearPlaced(pos);
    }
    if (soundFrom != null) {
      level.playSound((Player) null, struck.getX(), struck.getY(), struck.getZ(),
          soundFrom.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F,
          level.getRandom().nextFloat() * 0.4F + 0.8F);
    }
    return drops;
  }

  /**
   * Fells every tree with a log in or over {@code bounds}, a building's volume
   * in world coordinates. Each column is read from the world surface down to
   * the box's floor, so the top of a tall tree, a trunk remnant the ground
   * clearing stopped short of, or a branch reaching in from a neighbour all
   * come down with the rest of their tree rather than being left floating
   * over the roof. The building's own timber is safe: it was recorded as the
   * village's the instant it was stamped, and {@link BlockOwnership#mayFell}
   * refuses it. Columns in unloaded chunks are skipped, never paged in.
   */
  public static List<FelledTree> fellOver(ServerLevel level, BoundingBox bounds) {
    List<FelledTree> felled = new ArrayList<>();
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
      for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
        if (!level.hasChunkAt(cursor.set(x, bounds.minY(), z))) {
          continue;
        }
        // The heightmap's value is the first air above the tallest block in the column.
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        for (int y = top; y >= bounds.minY(); y--) {
          if (isFellableLog(level, cursor.set(x, y, z))) {
            BlockPos struck = cursor.immutable();
            felled.add(new FelledTree(struck, fell(level, struck, null, ItemStack.EMPTY)));
          }
        }
      }
    }
    return felled;
  }

  /**
   * The connected logs of one tree, reached from any of its logs by a bounded
   * breadth-first walk over all twenty-six neighbours so branches and leaning
   * trunks come with it. Capped so a giant tree cannot make the fill unbounded.
   * This is also how a tree's base is found: the lowest of these. Walking
   * straight down from a log is not enough, because a mangrove is all
   * branches and the walk stops at a log in the canopy with leaves under it.
   */
  public static List<BlockPos> treeLogs(ServerLevel level, BlockPos start) {
    List<BlockPos> logs = new ArrayList<>();
    ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
    LongOpenHashSet visited = new LongOpenHashSet();
    frontier.add(start);
    visited.add(start.asLong());
    while (!frontier.isEmpty() && logs.size() < TREE_LOG_CAP) {
      BlockPos pos = frontier.poll();
      if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(BlockTags.LOGS_THAT_BURN)) {
        continue;
      }
      logs.add(pos);
      for (int dx = -1; dx <= 1; dx++) {
        for (int dy = -1; dy <= 1; dy++) {
          for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) {
              continue;
            }
            BlockPos next = pos.offset(dx, dy, dz);
            if (visited.add(next.asLong())) {
              frontier.add(next);
            }
          }
        }
      }
    }
    return logs;
  }

  /**
   * Whether naturally grown leaves ({@code persistent == false}) sit close to a
   * log. This is what tells a living tree from a stack of authored timber: a
   * player's placed leaves are persistent, and a building carries none.
   */
  private static boolean hasNaturalCanopy(ServerLevel level, BlockPos log) {
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int x = -LEAF_RADIUS; x <= LEAF_RADIUS; ++x) {
      for (int y = -LEAF_RADIUS; y <= LEAF_RADIUS; ++y) {
        for (int z = -LEAF_RADIUS; z <= LEAF_RADIUS; ++z) {
          cursor.setWithOffset(log, x, y, z);
          if (!level.hasChunkAt(cursor)) {
            continue;
          }
          BlockState state = level.getBlockState(cursor);
          if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT)
              && !state.getValue(LeavesBlock.PERSISTENT)) {
            return true;
          }
        }
      }
    }
    return false;
  }

}
