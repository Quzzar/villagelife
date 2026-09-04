package com.quzzar.kithkyn.village.buildings;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Builds a disposable wall plan around an arbitrary rectangle for live review. */
public final class WallPreview {

  public static final int MIN_SPAN = 16;
  public static final int MAX_SPAN = 128;

  private WallPreview() {
  }

  /** Plans and places the same wall cells ordinary village builders consume. */
  public static Result build(ServerLevel level, BlockPos first, BlockPos second,
      WallTier tier, VillageStyle style) {
    int minX = Math.min(first.getX(), second.getX());
    int maxX = Math.max(first.getX(), second.getX());
    int minZ = Math.min(first.getZ(), second.getZ());
    int maxZ = Math.max(first.getZ(), second.getZ());
    validateSpan(maxX - minX, maxZ - minZ);
    requireLoaded(level, minX - 8, maxX + 8, minZ - 8, maxZ + 8);

    List<Long> ring = WallRoute.aroundBox(minX, maxX, minZ, maxZ);
    Set<Long> preferredGates = cardinalGates(ring, minX, maxX, minZ, maxZ);
    WallFeaturePlacement.Plan features = WallFeaturePlacement.resolve(
        level, ring, preferredGates);
    List<Integer> ground = WallRaiser.groundProfile(level, ring);
    List<Integer> deck = WallRaiser.deckProfile(level, ring, ground, tier.height());
    WallProject project = new WallProject(ring, features.gates(), ground, deck,
        tier, style, features.towerExclusions());
    int placed = WallRaiser.placeAll(level, project);
    return new Result(project.sectionCount(), placed, ring.size(), features.gates().size(), style);
  }

  static Set<Long> cardinalGates(List<Long> ring, int minX, int maxX, int minZ, int maxZ) {
    Set<Long> route = new HashSet<>(ring);
    Set<Long> candidates = Set.of(
        BlockPos.asLong((minX + maxX) / 2, 0, minZ),
        BlockPos.asLong(maxX, 0, (minZ + maxZ) / 2),
        BlockPos.asLong((minX + maxX) / 2, 0, maxZ),
        BlockPos.asLong(minX, 0, (minZ + maxZ) / 2));
    Set<Long> gates = new HashSet<>(candidates);
    gates.retainAll(route);
    return Set.copyOf(gates);
  }

  private static void validateSpan(int spanX, int spanZ) {
    if (spanX < MIN_SPAN || spanZ < MIN_SPAN
        || spanX > MAX_SPAN || spanZ > MAX_SPAN) {
      throw new IllegalArgumentException(
          "Wall preview spans must each be " + MIN_SPAN + " to " + MAX_SPAN + " blocks");
    }
  }

  /**
   * Heightmap reads against an unloaded chunk return an empty-looking column.
   * Refuse that input instead of silently compiling an underground preview.
   */
  private static void requireLoaded(ServerLevel level, int minX, int maxX,
      int minZ, int maxZ) {
    int minChunkX = Math.floorDiv(minX, 16);
    int maxChunkX = Math.floorDiv(maxX, 16);
    int minChunkZ = Math.floorDiv(minZ, 16);
    int maxChunkZ = Math.floorDiv(maxZ, 16);
    for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
      for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
        BlockPos probe = new BlockPos(chunkX * 16, level.getMinBuildHeight(), chunkZ * 16);
        if (!level.hasChunkAt(probe)) {
          throw new IllegalArgumentException(
              "Wall preview area is not loaded; stand nearby or force-load its chunks first");
        }
      }
    }
  }

  /** Compact feedback for the command and repeatable live-world checks. */
  public record Result(int sections, int placedBlocks, int routeColumns,
      int gates, VillageStyle style) {
  }
}
