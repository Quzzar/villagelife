package com.quzzar.villagelife.village;

import javax.annotation.Nullable;

import com.quzzar.villagelife.savedata.PlacedBlockStore;
import com.quzzar.villagelife.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Who owns the block at a position. The rule is the simple one it sounds
 * like: a block a player placed is the player's, a block a village placed is
 * the village's, and a block neither placed is nobody's. Both facts are
 * recorded at placement time in {@link PlacedBlockStore} and dropped when the
 * block is broken; nothing here is derived from geometry.
 *
 * The first caller is tree-clearing, through {@link #mayFell}: a worker may
 * fell a log that nobody placed. That protects a mine's timber supports and a
 * player's cabin block-by-block, while a natural tree on village ground - or
 * one grown from a planted sapling, which is deliberately never recorded -
 * stays a tree anyone may cut.
 */
public final class BlockOwnership {

  private BlockOwnership() {
  }

  /**
   * The ownership of a position.
   *
   * @param village       the village whose ground claim covers this column, or null
   * @param building      the building whose circle covers it, or null
   * @param playerPlaced  whether a player placed the block and it still stands
   * @param villagePlaced whether a village placed the block and it still stands
   */
  public record Ownership(@Nullable Village village, @Nullable Building building,
      boolean playerPlaced, boolean villagePlaced) {
  }

  public static Ownership query(ServerLevel level, BlockPos pos) {
    PlacedBlockStore store = PlacedBlockStore.get(level);
    Village village = owningVillage(level, pos);
    Building building = village == null ? null : owningBuilding(village, pos);
    return new Ownership(village, building, store.isPlayerPlaced(pos), store.isVillagePlaced(pos));
  }

  /** Whether a player placed the block here and it has not since been broken. */
  public static boolean isPlayerPlaced(ServerLevel level, BlockPos pos) {
    return PlacedBlockStore.get(level).isPlayerPlaced(pos);
  }

  /** Whether a village placed the block here and it has not since been broken. */
  public static boolean isVillagePlaced(ServerLevel level, BlockPos pos) {
    return PlacedBlockStore.get(level).isVillagePlaced(pos);
  }

  /**
   * The verdict tree-clearing asks for: a worker may fell this block if
   * nobody placed it - not a player, not a village. A log that fails this is
   * somebody's structure; a log that passes grew here.
   */
  public static boolean mayFell(ServerLevel level, BlockPos pos) {
    PlacedBlockStore store = PlacedBlockStore.get(level);
    return !store.isPlayerPlaced(pos) && !store.isVillagePlaced(pos);
  }

  /** The village whose building footprints claim this ground, or null if none does. */
  @Nullable
  public static Village owningVillage(ServerLevel level, BlockPos pos) {
    for (Village village : VillageManager.get(level).getVillages().values()) {
      if (village.hasClaimed(pos)) {
        return village;
      }
    }
    return null;
  }

  /** Best-effort: the village's building whose circle covers this position, nearest first. */
  @Nullable
  public static Building owningBuilding(Village village, BlockPos pos) {
    Building nearest = null;
    double nearestDistSqr = Double.MAX_VALUE;
    for (Building building : village.getBuildings()) {
      BlockPos center = BlockPos.of(building.getCenterLocation());
      double distSqr = pos.distSqr(center);
      double radius = building.getRadius();
      if (distSqr <= radius * radius && distSqr < nearestDistSqr) {
        nearestDistSqr = distSqr;
        nearest = building;
      }
    }
    return nearest;
  }

}
