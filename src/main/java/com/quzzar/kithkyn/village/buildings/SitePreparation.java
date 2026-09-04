package com.quzzar.kithkyn.village.buildings;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Scores a candidate site's preparation cost in blocks moved
 * (docs/site-selection.md). A site is not valid or invalid: it costs. Tier 0
 * clears vegetation (always allowed), tier 1 cuts and fills terrain toward the
 * build plane (budgeted), and anything past the budget is not a site, because
 * a village changes the surface of the land, never its shape. The one exception
 * is deeper fill in a compact low corner; broad depressions and high terrain keep
 * the ordinary limit.
 *
 * Preparation cost is space's own currency, deliberately separate from a
 * building's item recipe (building-spec.md). The planner takes a free site
 * when the search finds one and the cheapest preparable ground otherwise; the
 * builder pays the bill as the first phase of construction.
 *
 * Removal is a whitelist: only blocks in the kithkyn:clearable tag may
 * ever be counted as removable, never a block entity, never claimed ground.
 * Anything else in the way makes the site impossible rather than a target.
 */
public final class SitePreparation {

  public static final TagKey<Block> CLEARABLE =
      TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, "clearable"));

  /** Ordinary per-column cut or fill limit; compact low spots have a bounded exception. */
  public static final int MAX_COLUMN_DELTA = 3;

  /** Average cut plus fill per column above this exceeds the levelling budget. */
  public static final double MAX_AVERAGE_DELTA = 1.5;

  /** How far above the build plane each column is checked for obstructions. */
  public static final int CLEARANCE_HEIGHT = 8;

  /**
   * One candidate site's bill, in blocks moved, and why it failed if it did.
   * {@code earthwork} marks a refusal that only more digging than the village
   * allows would overturn: the ground is there, it just stands too far off the
   * plane. Water, claimed ground and someone's chest are not earthwork, and no
   * amount of levelling makes them a site.
   */
  public record SiteCost(int clearCount, int cutCount, int fillCount, boolean impossible, boolean earthwork,
      String reason) {

    static SiteCost impossible(String reason) {
      return new SiteCost(0, 0, 0, true, false, reason);
    }

    static SiteCost earthwork(String reason) {
      return new SiteCost(0, 0, 0, true, true, reason);
    }

    public int blocksMoved() {
      return clearCount + cutCount + fillCount;
    }

    public boolean isFree() {
      return !impossible && blocksMoved() == 0;
    }

    public String describe() {
      if (impossible) {
        return "impossible: " + reason;
      }
      return String.format("%d blocks moved (clear %d, cut %d, fill %d)%s",
          blocksMoved(), clearCount, cutCount, fillCount, isFree() ? " - free" : "");
    }
  }

  /**
   * The actual work a site needs: blocks to take away, and columns to raise.
   * Ground that cannot be prepared at all is {@link #impossible()}, which is
   * not the same as needing no work: the caller must refuse the site rather
   * than read an empty queue as "nothing to do here".
   */
  public record PrepWork(java.util.List<Long> toBreak, java.util.List<Long> toFill, boolean possible) {

    static PrepWork impossible() {
      return new PrepWork(java.util.List.of(), java.util.List.of(), false);
    }

    public boolean isEmpty() {
      return toBreak.isEmpty() && toFill.isEmpty();
    }

    public int size() {
      return toBreak.size() + toFill.size();
    }
  }

  private SitePreparation() {
  }

  /**
   * The same walk as {@link #score}, but collecting positions instead of
   * counting them: what the builder must break, and where they must place fill
   * to bring a column up to the build plane. Returns an empty queue when the
   * site needs nothing, and {@link PrepWork#impossible()} when it cannot be
   * prepared at all — the two must not be confused.
   */
  public static PrepWork planWork(ServerLevelAccessor level, Village village, BlockPos origin, BoundingBox bounds) {
    return planWork(level, village, origin, bounds, null);
  }

  /**
   * The same walk, ignoring the columns of {@code standing}. An upgrade needs
   * this for the same reason its fit check does: the old building's own walls
   * are not ground to be levelled, they are what the new template replaces, and
   * measuring them would report every upgrade as impossible.
   */
  public static PrepWork planWork(ServerLevelAccessor level, Village village, BlockPos origin, BoundingBox bounds,
      BoundingBox standing) {
    return planAfterRemoval(level, village, origin, bounds, standing, java.util.Set.of(), java.util.Set.of());
  }

  /** Surveys the actual ground left after named blocks and claims have been removed. */
  public static PrepWork planAfterRemoval(ServerLevelAccessor level, Village village, BlockPos origin,
      BoundingBox bounds, BoundingBox standing, java.util.Set<Long> removedBlocks,
      java.util.Set<Long> vacatedColumns) {
    java.util.List<Long> toBreak = new java.util.ArrayList<>();
    java.util.List<Long> toFill = new java.util.ArrayList<>();
    java.util.List<SiteTerrainPolicy.Column> terrain = new java.util.ArrayList<>();
    int plane = origin.getY();

    for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
      for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
        if (standing != null && x >= standing.minX() && x <= standing.maxX()
            && z >= standing.minZ() && z <= standing.maxZ()) {
          continue;
        }
        int worldX = origin.getX() + x;
        int worldZ = origin.getZ() + z;
        BlockPos groundProbe = new BlockPos(worldX, plane, worldZ);
        if (!level.getLevel().isLoaded(groundProbe)
            || village.hasClaimed(groundProbe) && !vacatedColumns.contains(BlockPos.asLong(worldX, 0, worldZ))) {
          return PrepWork.impossible();
        }

        int y = plane + CLEARANCE_HEIGHT;
        int surface = Integer.MIN_VALUE;
        while (y >= plane - SiteTerrainPolicy.MAX_LOCAL_DEPRESSION_DEPTH - 1) {
          BlockPos pos = new BlockPos(worldX, y, worldZ);
          BlockState state = removedBlocks.contains(pos.asLong())
              ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : level.getBlockState(pos);
          if (state.isAir()) {
            y--;
            continue;
          }
          if (level.getBlockEntity(pos) != null) {
            return PrepWork.impossible();
          }
          if (state.is(CLEARABLE)) {
            toBreak.add(pos.asLong());
            y--;
            continue;
          }
          if (!state.getFluidState().isEmpty()) {
            y--;
            continue;
          }
          surface = y;
          break;
        }
        if (surface == Integer.MIN_VALUE) {
          return PrepWork.impossible();
        }

        // The foundation course (structure y=0) is placed AT the plane and either
        // replaces the surface block or rests on it, so ground level at the plane
        // is free -- not one below. Seating floors on the ground (56affc1) made the
        // old "surface one under the plane" assumption read every flat column as a
        // 1-block cut, so prep dug the whole footprint out and never filled. Cut
        // only what stands above the plane; fill only up to the block beneath the
        // foundation (plane - 1).
        int delta = surface - plane;
        terrain.add(new SiteTerrainPolicy.Column(worldX, worldZ, delta));
        int cutCount = Math.max(0, surface - plane);
        int fillCount = Math.max(0, (plane - 1) - surface);
        for (int cut = 0; cut < cutCount; cut++) {
          toBreak.add(new BlockPos(worldX, plane + 1 + cut, worldZ).asLong());
        }
        for (int fill = 0; fill < fillCount; fill++) {
          toFill.add(new BlockPos(worldX, plane - 1 - fill, worldZ).asLong());
        }
      }
    }
    if (!SiteTerrainPolicy.assess(terrain).allowed()) {
      return PrepWork.impossible();
    }
    return new PrepWork(java.util.List.copyOf(toBreak), java.util.List.copyOf(toFill), true);
  }

  /**
   * Scores the footprint of {@code bounds} placed with its origin at
   * {@code origin}. The build plane is the origin's Y: the foundation course
   * sits there, replacing the surface block or resting on the ground one below,
   * so a column whose surface is at the plane (or one under it) needs no work.
   */
  public static SiteCost score(ServerLevelAccessor level, Village village, BlockPos origin, BoundingBox bounds) {
    return score(level, village, origin, bounds, null);
  }

  /**
   * Scores a footprint while ignoring the columns of {@code standing}, in the
   * same origin-relative frame. That is what an upgrade needs: the ground the
   * old building occupies is not an obstacle, it is the thing being replaced,
   * and only the ring the larger footprint reaches into has to be free.
   */
  public static SiteCost score(ServerLevelAccessor level, Village village, BlockPos origin, BoundingBox bounds,
      BoundingBox standing) {
    int clear = 0;
    int cut = 0;
    int fill = 0;
    java.util.List<SiteTerrainPolicy.Column> terrain = new java.util.ArrayList<>();
    int plane = origin.getY();

    for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
      for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
        if (standing != null && x >= standing.minX() && x <= standing.maxX()
            && z >= standing.minZ() && z <= standing.maxZ()) {
          continue;
        }
        int worldX = origin.getX() + x;
        int worldZ = origin.getZ() + z;
        BlockPos groundProbe = new BlockPos(worldX, plane, worldZ);

        // Never scan unloaded chunks; never touch claimed ground.
        if (!level.getLevel().isLoaded(groundProbe)) {
          return SiteCost.impossible("chunk not loaded");
        }
        if (village.hasClaimed(groundProbe)) {
          return SiteCost.impossible("overlaps ground the village has already built on");
        }

        // Walk down from the clearance top: clearable blocks are tier 0 cost,
        // liquids are not ground, and the first other solid is the column's
        // true surface. A block entity anywhere in the band kills the site.
        int y = plane + CLEARANCE_HEIGHT;
        int surface = Integer.MIN_VALUE;
        while (y >= plane - SiteTerrainPolicy.MAX_LOCAL_DEPRESSION_DEPTH - 1) {
          BlockPos pos = new BlockPos(worldX, y, worldZ);
          BlockState state = level.getBlockState(pos);
          if (state.isAir()) {
            y--;
            continue;
          }
          if (level.getBlockEntity(pos) != null) {
            return SiteCost.impossible("something with contents is in the way at " + pos.toShortString());
          }
          if (state.is(CLEARABLE)) {
            clear++;
            y--;
            continue;
          }
          if (!state.getFluidState().isEmpty()) {
            y--;
            continue;
          }
          surface = y;
          break;
        }

        if (surface == Integer.MIN_VALUE) {
          // No real ground within reach of the budget below the plane: a
          // ravine, cave mouth, or deep water. That is shape, not surface.
          return SiteCost.impossible("no ground under " + worldX + "," + worldZ);
        }

        int delta = surface - plane;
        terrain.add(new SiteTerrainPolicy.Column(worldX, worldZ, delta));
        // Match planWork: cut what stands above the plane, fill up to plane-1. A
        // column whose surface is at the plane (or one below) is free.
        cut += Math.max(0, surface - plane);
        fill += Math.max(0, (plane - 1) - surface);
      }
    }

    SiteTerrainPolicy.Assessment assessment = SiteTerrainPolicy.assess(terrain);
    if (!assessment.allowed()) {
      return SiteCost.earthwork(assessment.reason());
    }
    return new SiteCost(clear, cut, fill, false, false, "");
  }

}
