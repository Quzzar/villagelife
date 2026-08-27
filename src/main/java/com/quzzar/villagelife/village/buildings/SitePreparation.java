package com.quzzar.villagelife.village.buildings;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.village.Village;

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
 * a village changes the surface of the land, never its shape.
 *
 * Preparation cost is space's own currency, deliberately separate from a
 * building's item recipe (building-spec.md). The planner currently gates on a
 * free site (cost zero), so this scoring changes which sites are DESCRIBED,
 * not which are chosen; paying nonzero costs arrives with the prepare phase.
 *
 * Removal is a whitelist: only blocks in the villagelife:clearable tag may
 * ever be counted as removable, never a block entity, never claimed ground.
 * Anything else in the way makes the site impossible rather than a target.
 */
public final class SitePreparation {

  public static final TagKey<Block> CLEARABLE =
      TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "clearable"));

  /** Per-column cut or fill above this is reshaping, not levelling: not a site. */
  public static final int MAX_COLUMN_DELTA = 3;

  /** Average cut plus fill per column above this exceeds the levelling budget. */
  public static final double MAX_AVERAGE_DELTA = 1.5;

  /** How far above the build plane each column is checked for obstructions. */
  public static final int CLEARANCE_HEIGHT = 8;

  /** One candidate site's bill, in blocks moved, and why it failed if it did. */
  public record SiteCost(int clearCount, int cutCount, int fillCount, boolean impossible, String reason) {

    public static final SiteCost IMPOSSIBLE = new SiteCost(0, 0, 0, true, "unknown");

    static SiteCost impossible(String reason) {
      return new SiteCost(0, 0, 0, true, reason);
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
    java.util.List<Long> toBreak = new java.util.ArrayList<>();
    java.util.List<Long> toFill = new java.util.ArrayList<>();
    int plane = origin.getY();

    for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
      for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
        int worldX = origin.getX() + x;
        int worldZ = origin.getZ() + z;
        BlockPos groundProbe = new BlockPos(worldX, plane, worldZ);
        if (!level.getLevel().isLoaded(groundProbe) || village.hasClaimed(groundProbe)) {
          return PrepWork.impossible();
        }

        int y = plane + CLEARANCE_HEIGHT;
        int surface = Integer.MIN_VALUE;
        while (y >= plane - MAX_COLUMN_DELTA - 1) {
          BlockPos pos = new BlockPos(worldX, y, worldZ);
          BlockState state = level.getBlockState(pos);
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

        int delta = surface - (plane - 1);
        if (Math.abs(delta) > MAX_COLUMN_DELTA) {
          return PrepWork.impossible();
        }
        // Cut everything standing above the build plane, fill everything below it.
        for (int cut = 0; cut < delta; cut++) {
          toBreak.add(new BlockPos(worldX, plane - 1 + delta - cut, worldZ).asLong());
        }
        for (int fill = 0; fill < -delta; fill++) {
          toFill.add(new BlockPos(worldX, plane - 1 - fill, worldZ).asLong());
        }
      }
    }
    return new PrepWork(java.util.List.copyOf(toBreak), java.util.List.copyOf(toFill), true);
  }

  /**
   * Scores the footprint of {@code bounds} placed with its origin at
   * {@code origin}. The build plane is the origin's Y: the structure floor
   * sits there, on ground whose top block is one below it.
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
    int deltaSum = 0;
    int columns = 0;
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
        while (y >= plane - MAX_COLUMN_DELTA - 1) {
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

        int delta = surface - (plane - 1);
        if (Math.abs(delta) > MAX_COLUMN_DELTA) {
          return SiteCost.impossible("ground at " + worldX + "," + worldZ + " is " + delta
              + " off the build plane, past the levelling budget");
        }
        if (delta > 0) {
          cut += delta;
        } else {
          fill += -delta;
        }
        deltaSum += Math.abs(delta);
        columns++;
      }
    }

    if (columns > 0 && (double) deltaSum / columns > MAX_AVERAGE_DELTA) {
      return SiteCost.impossible(String.format("too uneven: %.1f blocks per column against a budget of %.1f",
          (double) deltaSum / columns, MAX_AVERAGE_DELTA));
    }
    return new SiteCost(clear, cut, fill, false, "");
  }

}
