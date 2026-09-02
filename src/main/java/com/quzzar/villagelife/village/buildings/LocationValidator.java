package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Finds a spot for a new building near the village (docs/site-selection.md).
 *
 * Two passes. The first scores a handful of random candidates in the village's
 * own ring, which is what gives a village its organic spread; a free site there
 * is taken at once. When that finds nothing free, the second sweeps a grid
 * outward from the fire, nearest first, as far as {@link #SWEEP_BEYOND} past
 * the ring. The sweep is what makes "no room" mean something: it used to be
 * twenty-four random rolls, and a refusal could not be told from bad luck.
 *
 * Every candidate is screened on the chunk heightmaps before anything is
 * block-scanned. The heights of the whole search square are read once into a
 * {@link HeightGrid}, so screening is arithmetic, and the volume scan is spent
 * only on ground the heightmap could not rule out.
 *
 * In the ring a free site wins outright. In the sweep the nearest usable ground
 * wins, cost deciding only among neighbours, because a village that sprawls has
 * a wall ring it can never afford and ground it can never finish grading. A
 * search that finds nothing hands back the nearest thing to a site it saw, so
 * the village can say where its room ran out rather than only that it did.
 */
public class LocationValidator {

  public static final int SEARCH_RADIUS_PER_4_BUILDINGS = 20;

  /** Random candidates tried in the village's own ring before the sweep. */
  public static final int CANDIDATES = 24;

  /** Blocks of clear ground kept between the town centre and its neighbours. */
  public static final int MIN_STAND_OFF = 2;

  /**
   * Grid stride of the sweep. A footprint is wider than this, so a site with a
   * few blocks of slack round it cannot fall between two grid points; a site
   * that fits only exactly can, and the doc says so.
   */
  public static final int SWEEP_STRIDE = 4;

  /** How far past the village's own ring the sweep looks before it gives up. */
  public static final int SWEEP_BEYOND = 32;

  /**
   * How much further out than the nearest usable ground the sweep keeps
   * reading, so cost can still choose between neighbours without reaching for
   * cheap ground far away.
   */
  public static final int SWEEP_BAND = 8;

  /** No village looks further than this for a site, however large it grows. */
  public static final int MAX_SEARCH_RADIUS = 96;

  /**
   * A footprint may carry one column in this many past the per-column budget
   * and still be worth a scan: a tree reads as a tall column on the heightmap
   * and is cleared, not levelled. More than that is a cliff or a wood.
   */
  public static final int STEEP_COLUMNS_PER = 8;

  /**
   * What a search found: a site, or failing that the nearest thing to one, and
   * how far out (in blocks from the fire) it actually managed to read ground.
   */
  public record Search(@Nullable BlockPos site, @Nullable SiteMemory.NearMiss nearMiss, int reach) {

    public boolean found() {
      return site != null;
    }
  }

  public static Search findValidLocation(ServerLevelAccessor levelAccess, BlockPos centerPos, BoundingBox bounds,
      Village village, Random random) {
    ServerLevel level = levelAccess.getLevel();

    // The village grows outwards as it builds, but the ring never collapses: a
    // village with nothing built yet still has to put its first building
    // somewhere. When this was a plain multiple of the building count, a young
    // village searched a radius of zero, every candidate landed on the town
    // centre's own footprint, and no site was ever scored at all.
    int ringRadius = Math.min(MAX_SEARCH_RADIUS,
        SEARCH_RADIUS_PER_4_BUILDINGS * (1 + village.getBuildings().size() / 4));
    int sweepRadius = Math.min(MAX_SEARCH_RADIUS, ringRadius + SWEEP_BEYOND);
    // Never propose the ground the town centre is standing on.
    int standOff = (int) village.getTownCenter().getRadius() + MIN_STAND_OFF;

    int span = Math.max(bounds.getXSpan(), bounds.getZSpan());
    HeightGrid grid = new HeightGrid(level, centerPos, sweepRadius + span);
    Hunt hunt = new Hunt(level, village, bounds, grid, centerPos);

    // Pass one: random candidates in the village's own ring, biased toward the
    // fire, so a village with room to spare grows in its own irregular way.
    for (int attempt = 0; attempt < CANDIDATES; attempt++) {
      int n = 1 + random.nextInt(Math.max(1, ringRadius));
      int relX = Math.max(random.nextInt(n), random.nextInt(n));
      int relZ = Math.max(random.nextInt(n), random.nextInt(n));
      if (random.nextBoolean()) {
        relX *= -1;
      }
      if (random.nextBoolean()) {
        relZ *= -1;
      }
      // Push anything that landed on the town centre out past it, along one
      // axis only, so buildings can still sit due north or east of the fire
      // instead of only on the diagonals.
      if (Math.abs(relX) < standOff && Math.abs(relZ) < standOff) {
        relX += relX < 0 ? -standOff : standOff;
      }
      if (hunt.consider(relX, relZ)) {
        return hunt.result(ringRadius, sweepRadius);
      }
    }

    // Pass two: the sweep, nearest first, on a grid with a random phase so a
    // tight village does not fill in along a fixed lattice.
    int phaseX = random.nextInt(SWEEP_STRIDE);
    int phaseZ = random.nextInt(SWEEP_STRIDE);
    List<int[]> offsets = new ArrayList<>();
    for (int relX = -sweepRadius + phaseX; relX <= sweepRadius; relX += SWEEP_STRIDE) {
      for (int relZ = -sweepRadius + phaseZ; relZ <= sweepRadius; relZ += SWEEP_STRIDE) {
        if (Math.abs(relX) < standOff && Math.abs(relZ) < standOff) {
          continue;
        }
        offsets.add(new int[] {relX, relZ});
      }
    }
    offsets.sort(Comparator.comparingInt(offset -> offset[0] * offset[0] + offset[1] * offset[1]));
    // The random pass found nothing free, so what it priced is set aside: the
    // sweep decides by distance and would read that ground again anyway.
    hunt.forgetCheapest();
    // Nearest first, and the first ground that will do settles a band: the sweep
    // reads only SWEEP_BAND blocks further and takes the cheapest in it, free
    // winning outright. Taking the cheapest in the whole reach (the first cut of
    // this) put Wildflower Downs' lumberjack 90 blocks from its fire, 78 blocks
    // of work there against 211 within 50.
    int settledAt = -1;
    for (int[] offset : offsets) {
      double distance = Math.sqrt((double) offset[0] * offset[0] + (double) offset[1] * offset[1]);
      if (settledAt >= 0 && distance > settledAt + SWEEP_BAND) {
        break;
      }
      if (hunt.consider(offset[0], offset[1])) {
        break;
      }
      if (settledAt < 0 && hunt.hasCheapest()) {
        settledAt = (int) Math.ceil(distance);
      }
    }
    return hunt.result(ringRadius, sweepRadius);
  }

  public static double getBuildingRadius(BoundingBox bounds){
    return Math.max(bounds.getXSpan()/2, bounds.getZSpan()/2);
  }

  /**
   * A footprint's ground as the heightmap sees it, seated at the height most of
   * its columns share: how many columns stand past the per-column budget, and
   * how far off that plane the ground stands in total and, among the columns
   * within budget, on average.
   */
  record Reading(int plane, int columns, int steepColumns, int offPlane, int levelOffPlane) {

    /**
     * Why the heightmap alone rules this ground out, or null when a block scan
     * is worth its cost. Mirrors the scorer's two budgets, with the allowance
     * that a few tall columns are trees, which are cleared rather than levelled.
     */
    @Nullable
    String screenedOut() {
      if (steepColumns * STEEP_COLUMNS_PER > columns) {
        return steepSentence();
      }
      int levelColumns = columns - steepColumns;
      if (levelColumns > 0 && (double) levelOffPlane / levelColumns > SitePreparation.MAX_AVERAGE_DELTA) {
        return String.format("its ground runs %.1f blocks off level per column, where the village levels at most %.1f",
            (double) levelOffPlane / levelColumns, SitePreparation.MAX_AVERAGE_DELTA);
      }
      return null;
    }

    /** The steep columns as a fact, for a briefing. */
    String steepSentence() {
      return steepColumns + " of its " + columns + " columns stand more than "
          + SitePreparation.MAX_COLUMN_DELTA + " blocks off level";
    }
  }

  /**
   * Ground heights for the whole search square, read once from the chunk
   * heightmaps so every candidate's flatness is arithmetic rather than a block
   * scan (docs/site-selection.md, the first runtime mitigation). Chunks that
   * are not loaded read as {@link #NO_GROUND} and are never loaded by the read:
   * a village cannot site anything on ground nobody is near.
   */
  private static final class HeightGrid {

    static final int NO_GROUND = Integer.MIN_VALUE;

    private final int minX;
    private final int minZ;
    private final int width;
    private final int depth;
    private final int[] heights;

    HeightGrid(ServerLevel level, BlockPos centre, int reach) {
      this.minX = centre.getX() - reach;
      this.minZ = centre.getZ() - reach;
      this.width = reach * 2 + 1;
      this.depth = reach * 2 + 1;
      this.heights = new int[width * depth];
      Arrays.fill(heights, NO_GROUND);
      int maxX = minX + width - 1;
      int maxZ = minZ + depth - 1;
      for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
        for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
          LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
          if (chunk == null) {
            continue;
          }
          int fromX = Math.max(minX, chunkX << 4);
          int toX = Math.min(maxX, (chunkX << 4) + 15);
          int fromZ = Math.max(minZ, chunkZ << 4);
          int toZ = Math.min(maxZ, (chunkZ << 4) + 15);
          for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
              // The top block that stops movement and is not a leaf: the real
              // ground under a canopy, the same reading founding and the old
              // per-candidate snap used.
              heights[(x - minX) * depth + (z - minZ)] =
                  chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
            }
          }
        }
      }
    }

    int ground(int x, int z) {
      int gridX = x - minX;
      int gridZ = z - minZ;
      if (gridX < 0 || gridZ < 0 || gridX >= width || gridZ >= depth) {
        return NO_GROUND;
      }
      return heights[gridX * depth + gridZ];
    }

    /**
     * The footprint of {@code bounds} seated at column (x, z), or null when any
     * of its columns is unloaded. The plane is the height most columns share,
     * ties to the higher ground, so a building is never seated in the lower
     * half of a split; that is where a house went a block low when its origin
     * column happened to be a dip in a level plain.
     */
    @Nullable
    Reading read(int originX, int originZ, BoundingBox bounds) {
      int[] footprint = new int[bounds.getXSpan() * bounds.getZSpan()];
      int i = 0;
      for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
          int height = ground(originX + x, originZ + z);
          if (height == NO_GROUND) {
            return null;
          }
          footprint[i++] = height;
        }
      }
      Arrays.sort(footprint);
      int plane = footprint[0];
      int run = 0;
      int bestRun = 0;
      for (int j = 0; j < footprint.length; j++) {
        run = j > 0 && footprint[j] == footprint[j - 1] ? run + 1 : 1;
        if (run >= bestRun) {
          bestRun = run;
          plane = footprint[j];
        }
      }
      int steep = 0;
      int offPlane = 0;
      int levelOffPlane = 0;
      for (int height : footprint) {
        int delta = Math.abs(height - plane);
        offPlane += delta;
        if (delta > SitePreparation.MAX_COLUMN_DELTA) {
          steep++;
        } else {
          levelOffPlane += delta;
        }
      }
      return new Reading(plane, footprint.length, steep, offPlane, levelOffPlane);
    }
  }

  /**
   * One search's running state: the candidates considered so far and what they
   * came to. Both passes feed the same accumulator, so the summary line and the
   * near-miss cover everything the search looked at.
   */
  private static final class Hunt {

    private final ServerLevel level;
    private final Village village;
    private final BoundingBox bounds;
    private final HeightGrid grid;
    private final BlockPos centre;

    @Nullable
    private BlockPos free;
    @Nullable
    private BlockPos cheapest;
    private int cheapestCost = Integer.MAX_VALUE;
    @Nullable
    private SiteMemory.NearMiss nearMiss;
    private int reach;
    private int screened;
    private int scanned;
    private int claimed;
    private int unloaded;

    Hunt(ServerLevel level, Village village, BoundingBox bounds, HeightGrid grid, BlockPos centre) {
      this.level = level;
      this.village = village;
      this.bounds = bounds;
      this.grid = grid;
      this.centre = centre;
    }

    /** Weighs one candidate origin; true when it is free, which ends the search. */
    boolean consider(int relX, int relZ) {
      int x = centre.getX() + relX;
      int z = centre.getZ() + relZ;
      // Never start from ground the village has already built on. Without this
      // the heightmap hands back the ROOF of an existing building as the
      // surface, and every such candidate is scored as a site metres above the
      // ground it is supposed to sit on.
      if (village.hasClaimed(new BlockPos(x, centre.getY(), z))) {
        claimed++;
        return false;
      }
      Reading reading = grid.read(x, z, bounds);
      if (reading == null) {
        unloaded++;
        return false;
      }
      reach = Math.max(reach, (int) Math.round(Math.sqrt((double) relX * relX + (double) relZ * relZ)));
      BlockPos candidate = new BlockPos(x, reading.plane(), z);
      // The footprint extends from the origin, so a candidate whose far corner
      // sits on someone's roof is no better than one that starts there.
      if (village.hasClaimed(candidate.offset(bounds.maxX(), 0, bounds.maxZ()))
          || village.hasClaimed(candidate.offset(bounds.maxX(), 0, 0))
          || village.hasClaimed(candidate.offset(0, 0, bounds.maxZ()))) {
        claimed++;
        return false;
      }

      String screenedOut = reading.screenedOut();
      if (screenedOut != null) {
        screened++;
        noteNearMiss(candidate, reading, screenedOut);
        return false;
      }

      scanned++;
      SitePreparation.SiteCost cost = SitePreparation.score(level, village, candidate, bounds);
      Villagelife.LOGGER.debug("Site at {} costs {}", candidate.toShortString(), cost.describe());
      if (cost.isFree()) {
        free = candidate;
        return true;
      }
      if (cost.impossible()) {
        if (cost.earthwork()) {
          // The heightmap let it through and the scan found the drop under the
          // cover: the steep columns are the fact, when there are any.
          noteNearMiss(candidate, reading, reading.steepColumns() > 0 ? reading.steepSentence() : cost.reason());
        }
        return false;
      }
      if (cost.blocksMoved() < cheapestCost) {
        // Worth having if nothing free turns up: the builder can prepare it.
        cheapest = candidate;
        cheapestCost = cost.blocksMoved();
      }
      return false;
    }

    boolean hasCheapest() {
      return cheapest != null;
    }

    void forgetCheapest() {
      cheapest = null;
      cheapestCost = Integer.MAX_VALUE;
    }

    /** Keeps the refused ground with the least earth standing off level. */
    private void noteNearMiss(BlockPos candidate, Reading reading, String reason) {
      if (nearMiss != null && reading.offPlane() >= nearMiss.blocksOffPlane()) {
        return;
      }
      // The corner check lets a footprint straddle the village's own ground, and
      // the heightmap reads that ground as flat, so a footprint half on the plat
      // and half up a bank ranked as the nearest thing to a site (seen live at
      // Brindlemark: "6 blocks south-west of the fire"). Claimed ground is never
      // a near miss; it is not the village's to level. The scan would have said
      // so, but a screened candidate never reaches the scan, so ask here, per
      // column, only for the few candidates that would take the title.
      if (overlapsClaim(candidate)) {
        return;
      }
      BlockPos middle = candidate.offset((bounds.minX() + bounds.maxX()) / 2, 0, (bounds.minZ() + bounds.maxZ()) / 2);
      nearMiss = new SiteMemory.NearMiss(middle, reading.offPlane(), reason);
    }

    private boolean overlapsClaim(BlockPos candidate) {
      BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
      for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
          probe.set(candidate.getX() + x, candidate.getY(), candidate.getZ() + z);
          if (village.hasClaimed(probe)) {
            return true;
          }
        }
      }
      return false;
    }

    Search result(int ringRadius, int sweepRadius) {
      // One line that says what the whole search saw. Without it a search that
      // skips every candidate before scoring is indistinguishable from one where
      // every site was genuinely bad.
      Villagelife.LOGGER.debug(
          "Site search for '{}' around {} (ring {}, sweep {}) in {} ({} chunks loaded): "
              + "{} screened out on the heightmap, {} scanned, {} on claimed ground, {} unloaded",
          village.getName(), centre.toShortString(), ringRadius, sweepRadius,
          level.dimension().location(), level.getChunkSource().getLoadedChunksCount(),
          screened, scanned, claimed, unloaded);
      if (free != null) {
        return new Search(free, null, reach);
      }
      if (cheapest != null) {
        Villagelife.LOGGER.debug("No free site; taking {} at a cost of {} blocks moved",
            cheapest.toShortString(), cheapestCost);
        return new Search(cheapest, null, reach);
      }
      return new Search(null, nearMiss, reach);
    }
  }

}
