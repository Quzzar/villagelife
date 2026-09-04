package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Finds a spot for a new building near the village (docs/site-selection.md).
 *
 * The search first tries frontage slots beside buildings already standing. Each
 * slot aligns an edge across a one-block lane, which lets repeated local choices
 * grow into rows, streets and courtyards without a global grid. If terrain or
 * other buildings rule those slots out, a nearest-first sweep finds ordinary
 * open ground as a fallback.
 *
 * Every candidate is tried in each requested rotation, and the orientation that
 * fits the slot is the one kept: a long building can turn to fit a gap that its
 * other facing could not. A snug gap is held between footprints so lanes stay
 * walkable and the cluster reads as planned rather than piled. Existing worn
 * paths are never built over, and a legal site beside one is preferred. Equal
 * fits are broken randomly, which keeps local order from becoming a rigid grid.
 *
 * Ground heights for the whole search square are read once from the chunk
 * heightmaps ({@link HeightGrid}), so screening a candidate is arithmetic, and
 * the volume scan ({@link SitePreparation#score}) is spent only on ground the
 * heightmap and the claim grid could not rule out. Relational fit, path access,
 * preparation cost, and distance rank legal candidates in that order. The
 * fallback sweep remains limited to a short band around the first usable ground,
 * so a village does not reach far beyond a preparable nearby site.
 */
public class LocationValidator {

  public static final int SEARCH_RADIUS_PER_4_BUILDINGS = 20;

  /** Blocks of clear ground kept between the town centre and its neighbours. */
  public static final int MIN_STAND_OFF = 2;

  /**
   * Grid stride of the sweep. Kept fine so the ring can pack in snugly rather
   * than settling on a coarse lattice; a footprint is wider than this, so a slot
   * with room to spare cannot fall between two grid points.
   */
  public static final int SWEEP_STRIDE = 2;

  /**
   * Clear blocks held between a new footprint and everything the village has
   * already claimed. One block is a walkable lane and reads as deliberate
   * spacing; zero would let buildings share a wall.
   */
  public static final int MIN_GAP = 1;

  /** How far past the village's own ring the sweep looks before it gives up. */
  public static final int SWEEP_BEYOND = 32;

  /**
   * How much further out than the nearest usable ground the sweep keeps reading,
   * so cost can still choose between neighbours without reaching for cheap ground
   * far away.
   */
  public static final int SWEEP_BAND = 8;

  /** No village looks further than this for a site, however large it grows. */
  public static final int MAX_SEARCH_RADIUS = 96;

  /** Synchronous volume scans spent on relational slots before the fallback sweep. */
  public static final int MAX_PLANNED_CANDIDATES = 160;

  /**
   * What a search found: a site and the orientation that fit it, or failing that
   * the nearest thing to a site, and how far out (in blocks from the fire) it
   * actually managed to read ground.
   */
  public record Search(@Nullable BlockPos site, Rotation rotation, @Nullable SiteMemory.NearMiss nearMiss, int reach) {

    public boolean found() {
      return site != null;
    }
  }

  /**
   * Finds the nearest slot around {@code centerPos} that the building fits in one
   * of {@code rotations}. The template is weighed in every listed orientation and
   * the fitting one is returned with the site, so the caller can raise it facing
   * whichever way slots in. Pass a single rotation to search one fixed facing.
   */
  public static Search findValidLocation(ServerLevelAccessor levelAccess, BlockPos centerPos,
      StructureTemplate template, List<Rotation> rotations, Village village, Random random) {
    ServerLevel level = levelAccess.getLevel();

    // The village grows outwards as it builds, but the ring never collapses: a
    // village with nothing built yet still has to put its first building
    // somewhere. When this was a plain multiple of the building count, a young
    // village searched a radius of zero and no site was ever scored at all.
    int ringRadius = Math.min(MAX_SEARCH_RADIUS,
        SEARCH_RADIUS_PER_4_BUILDINGS * (1 + village.getBuildings().size() / 4));
    int sweepRadius = Math.min(MAX_SEARCH_RADIUS, ringRadius + SWEEP_BEYOND);
    // Never propose the ground the town centre is standing on.
    int standOff = (int) village.getTownCenter().getRadius() + MIN_STAND_OFF;

    // Each orientation's footprint, read once. The rotated box pivots around the
    // origin, so its min corner can go negative, and the seat and claim maths use
    // those offsets directly; the grid is read wide enough for the largest facing.
    EnumMap<Rotation, BoundingBox> boundsByRotation = new EnumMap<>(Rotation.class);
    int maxSpan = 0;
    for (Rotation rotation : rotations) {
      BoundingBox rotated = template.getBoundingBox(new StructurePlaceSettings().setRotation(rotation), BlockPos.ZERO);
      boundsByRotation.put(rotation, rotated);
      maxSpan = Math.max(maxSpan, Math.max(rotated.getXSpan(), rotated.getZSpan()));
    }
    HeightGrid grid = new HeightGrid(level, centerPos, sweepRadius + maxSpan);
    Hunt hunt = new Hunt(level, village, boundsByRotation, grid, centerPos, random);

    // City form comes from relationships, not a radial lot lottery. Try the
    // exact one-lane frontage slots around every completed building before the
    // general sweep. They are cheap to enumerate: at most twelve alignments per
    // anchor and orientation, independent of the search area's size.
    for (PlannedCandidate candidate : plannedCandidates(level, village, boundsByRotation,
        centerPos, sweepRadius)) {
      hunt.consider(candidate.origin().x() - centerPos.getX(),
          candidate.origin().z() - centerPos.getZ(), candidate.rotation(), true);
    }
    if (hunt.hasFit()) {
      return hunt.result(ringRadius, sweepRadius);
    }

    // The sweep, nearest first, on a grid with a random phase so a tight village
    // does not fill in along a fixed lattice.
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
    // Nearest first, and the first ground that will do settles a band. The sweep
    // reads only SWEEP_BAND blocks further, then ranks the nearby choices by
    // relationship, path access, cost, and distance. Taking the cheapest in the
    // whole reach put Wildflower Downs' lumberjack 90 blocks from its fire, 78
    // blocks of work there against 211 within 50; a village that sprawls has a
    // wall ring it cannot afford.
    int settledAt = -1;
    for (int[] offset : offsets) {
      double distance = Math.sqrt((double) offset[0] * offset[0] + (double) offset[1] * offset[1]);
      if (settledAt >= 0 && distance > settledAt + SWEEP_BAND) {
        break;
      }
      hunt.consider(offset[0], offset[1]);
      if (settledAt < 0 && hunt.hasFit()) {
        settledAt = (int) Math.ceil(distance);
      }
    }
    return hunt.result(ringRadius, sweepRadius);
  }

  public static double getBuildingRadius(BoundingBox bounds){
    return Math.max(bounds.getXSpan()/2, bounds.getZSpan()/2);
  }

  private record PlannedCandidate(TownLayout.Origin origin, Rotation rotation,
      TownLayout.Relationship relationship, int distanceSqr) {
  }

  /** Exact edge-aligned growth slots around the village's completed fabric. */
  private static List<PlannedCandidate> plannedCandidates(ServerLevelAccessor level, Village village,
      EnumMap<Rotation, BoundingBox> boundsByRotation, BlockPos centre, int reach) {
    List<TownLayout.Footprint> anchors = new ArrayList<>();
    for (Building building : village.getBuildings()) {
      BoundingBox local = BuildingUpgrade.footprintOf(level, building);
      if (local == null) {
        continue;
      }
      BlockPos origin = BlockPos.of(building.getOriginLocation());
      anchors.add(new TownLayout.Footprint(
          origin.getX() + local.minX(), origin.getZ() + local.minZ(),
          origin.getX() + local.maxX(), origin.getZ() + local.maxZ()));
    }

    Set<PlannedCandidate> candidates = new LinkedHashSet<>();
    int reachSqr = reach * reach;
    for (var entry : boundsByRotation.entrySet()) {
      TownLayout.Footprint local = footprint(entry.getValue());
      for (TownLayout.Footprint anchor : anchors) {
        for (TownLayout.Origin origin : TownLayout.frontageOrigins(anchor, local, MIN_GAP)) {
          TownLayout.Footprint placed = local.moved(origin);
          int centerX = Math.floorDiv(placed.minX() + placed.maxX(), 2);
          int centerZ = Math.floorDiv(placed.minZ() + placed.maxZ(), 2);
          int relX = centerX - centre.getX();
          int relZ = centerZ - centre.getZ();
          int distanceSqr = relX * relX + relZ * relZ;
          if (distanceSqr <= reachSqr && claimFree(village, placed, MIN_GAP)) {
            TownLayout.Relationship relationship = TownLayout.relationship(placed, MIN_GAP,
                (x, z) -> village.hasClaimed(new BlockPos(x, 0, z)));
            candidates.add(new PlannedCandidate(origin, entry.getKey(), relationship, distanceSqr));
          }
        }
      }
    }
    return candidates.stream()
        .sorted(Comparator
            .comparingInt((PlannedCandidate candidate) -> candidate.relationship().adjacentSides()).reversed()
            .thenComparing(Comparator
                .comparingInt((PlannedCandidate candidate) -> candidate.relationship().frontage()).reversed())
            .thenComparingInt(PlannedCandidate::distanceSqr))
        .limit(MAX_PLANNED_CANDIDATES)
        .toList();
  }

  private static TownLayout.Footprint footprint(BoundingBox bounds) {
    return new TownLayout.Footprint(bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
  }

  private static boolean claimFree(Village village, TownLayout.Footprint footprint, int padding) {
    for (int x = footprint.minX() - padding; x <= footprint.maxX() + padding; x++) {
      for (int z = footprint.minZ() - padding; z <= footprint.maxZ() + padding; z++) {
        if (village.hasClaimed(new BlockPos(x, 0, z))) {
          return false;
        }
      }
    }
    return true;
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
      if (steepColumns > SiteTerrainPolicy.allowedOutlierColumns(columns)) {
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
     * column happened to be a dip in an otherwise level plain.
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

  /** One orientation weighed at one candidate, including its urban relationship. */
  private record Fit(Rotation rotation, BlockPos site, SitePreparation.SiteCost cost,
      TownLayout.Relationship relationship, int pathFrontage, int distanceSqr, boolean planned) {}

  /**
   * One search's running state: the candidates considered so far and what they
   * came to. The summary line and the near-miss cover everything the search
   * looked at, across every orientation it tried.
   */
  private static final class Hunt {

    private final ServerLevel level;
    private final Village village;
    private final EnumMap<Rotation, BoundingBox> boundsByRotation;
    private final HeightGrid grid;
    private final BlockPos centre;
    private final Random random;

    @Nullable
    private Fit best;
    @Nullable
    private SiteMemory.NearMiss nearMiss;
    private int reach;
    private int screened;
    private int scanned;
    private int claimed;
    private int paths;
    private int unloaded;

    Hunt(ServerLevel level, Village village, EnumMap<Rotation, BoundingBox> boundsByRotation, HeightGrid grid,
        BlockPos centre, Random random) {
      this.level = level;
      this.village = village;
      this.boundsByRotation = boundsByRotation;
      this.grid = grid;
      this.centre = centre;
      this.random = random;
    }

    /**
     * Weighs one candidate origin in every orientation. The caller reads a short
     * distance band after the first viable fit, so nearby layouts can be compared
     * rather than whichever grid point happened to be visited first winning.
     */
    void consider(int relX, int relZ) {
      for (Rotation rotation : boundsByRotation.keySet()) {
        consider(relX, relZ, rotation, false);
      }
    }

    /** Weighs one exact origin and orientation. */
    void consider(int relX, int relZ, Rotation rotation, boolean planned) {
      int x = centre.getX() + relX;
      int z = centre.getZ() + relZ;
      BoundingBox bounds = boundsByRotation.get(rotation);
      Reading reading = grid.read(x, z, bounds);
      if (reading == null) {
        unloaded++;
        return;
      }
      reach = Math.max(reach, (int) Math.round(Math.sqrt((double) relX * relX + (double) relZ * relZ)));
      // The footprint, grown by the gap, may not touch ground the village has
      // already built on. Without this the heightmap hands back the roof of an
      // existing building as the surface. The gap is the shared lane.
      if (overlapsClaim(x, z, bounds, MIN_GAP)) {
        claimed++;
        return;
      }
      // Worn routes are persistent public space. New frontage may line them,
      // but a building never erases one and forces the village to rediscover it.
      if (coversPath(x, z, bounds)) {
        paths++;
        return;
      }
      BlockPos candidate = new BlockPos(x, reading.plane(), z);
      String screenedOut = reading.screenedOut();
      if (screenedOut != null) {
        screened++;
        noteNearMiss(candidate, bounds, reading, screenedOut);
        return;
      }
      scanned++;
      SitePreparation.SiteCost cost = SitePreparation.score(level, village, candidate, bounds);
      Kithkyn.LOGGER.debug("Site at {} facing {} costs {}", candidate.toShortString(), rotation, cost.describe());
      if (cost.impossible()) {
        if (cost.earthwork()) {
          noteNearMiss(candidate, bounds, reading,
              reading.steepColumns() > 0 ? reading.steepSentence() : cost.reason());
        }
        return;
      }

      TownLayout.Footprint placed = footprint(bounds).moved(new TownLayout.Origin(x, z));
      TownLayout.Relationship relationship = TownLayout.relationship(placed, MIN_GAP,
          (claimX, claimZ) -> village.hasClaimed(new BlockPos(claimX, 0, claimZ)));
      int centerX = Math.floorDiv(placed.minX() + placed.maxX(), 2);
      int centerZ = Math.floorDiv(placed.minZ() + placed.maxZ(), 2);
      int centerRelX = centerX - centre.getX();
      int centerRelZ = centerZ - centre.getZ();
      Fit fit = new Fit(rotation, candidate, cost, relationship, pathFrontage(placed),
          centerRelX * centerRelX + centerRelZ * centerRelZ, planned);
      if (betterThan(fit, best)) {
        best = fit;
      }
    }

    boolean hasFit() {
      return best != null;
    }

    /**
     * Planned fabric wins first, then corners/rows, then an existing path edge,
     * preparation cost and compactness. An exact tie is random so repeated local
     * rules do not produce identical villages.
     */
    private boolean betterThan(Fit candidate, @Nullable Fit incumbent) {
      if (incumbent == null) {
        return true;
      }
      if (candidate.planned() != incumbent.planned()) {
        return candidate.planned();
      }
      if (candidate.relationship().adjacentSides() != incumbent.relationship().adjacentSides()) {
        return candidate.relationship().adjacentSides() > incumbent.relationship().adjacentSides();
      }
      if (candidate.pathFrontage() != incumbent.pathFrontage()) {
        return candidate.pathFrontage() > incumbent.pathFrontage();
      }
      if (candidate.relationship().frontage() != incumbent.relationship().frontage()) {
        return candidate.relationship().frontage() > incumbent.relationship().frontage();
      }
      if (candidate.cost().blocksMoved() != incumbent.cost().blocksMoved()) {
        return candidate.cost().blocksMoved() < incumbent.cost().blocksMoved();
      }
      if (candidate.distanceSqr() != incumbent.distanceSqr()) {
        return candidate.distanceSqr() < incumbent.distanceSqr();
      }
      return random.nextBoolean();
    }

    private boolean coversPath(int originX, int originZ, BoundingBox bounds) {
      for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
          if (isPath(originX + x, originZ + z)) {
            return true;
          }
        }
      }
      return false;
    }

    /** Existing path cells immediately outside the footprint. */
    private int pathFrontage(TownLayout.Footprint placed) {
      int count = 0;
      for (int x = placed.minX(); x <= placed.maxX(); x++) {
        if (isPath(x, placed.minZ() - 1)) {
          count++;
        }
        if (isPath(x, placed.maxZ() + 1)) {
          count++;
        }
      }
      for (int z = placed.minZ(); z <= placed.maxZ(); z++) {
        if (isPath(placed.minX() - 1, z)) {
          count++;
        }
        if (isPath(placed.maxX() + 1, z)) {
          count++;
        }
      }
      return count;
    }

    private boolean isPath(int x, int z) {
      int ground = grid.ground(x, z);
      return ground != HeightGrid.NO_GROUND
          && level.getBlockState(new BlockPos(x, ground - 1, z)).is(Blocks.DIRT_PATH);
    }

    /** Keeps the refused ground with the least earth standing off level. */
    private void noteNearMiss(BlockPos candidate, BoundingBox bounds, Reading reading, String reason) {
      if (nearMiss != null && reading.offPlane() >= nearMiss.blocksOffPlane()) {
        return;
      }
      // The heightmap reads the village's own ground as flat, so a footprint half
      // on the plat and half up a bank ranked as the nearest thing to a site
      // (seen live at Brindlemark: "6 blocks south-west of the fire"). Claimed
      // ground is never a near miss; it is not the village's to level.
      if (overlapsClaim(candidate.getX(), candidate.getZ(), bounds, 0)) {
        return;
      }
      BlockPos middle = candidate.offset((bounds.minX() + bounds.maxX()) / 2, 0, (bounds.minZ() + bounds.maxZ()) / 2);
      nearMiss = new SiteMemory.NearMiss(middle, reading.offPlane(), reason);
    }

    /** Whether the footprint at (originX, originZ), grown by {@code pad}, touches any claimed column. */
    private boolean overlapsClaim(int originX, int originZ, BoundingBox bounds, int pad) {
      BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
      for (int x = bounds.minX() - pad; x <= bounds.maxX() + pad; x++) {
        for (int z = bounds.minZ() - pad; z <= bounds.maxZ() + pad; z++) {
          probe.set(originX + x, 0, originZ + z);
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
      Kithkyn.LOGGER.debug(
          "Site search for '{}' around {} (ring {}, sweep {}) in {} ({} chunks loaded): "
              + "{} screened out on the heightmap, {} scanned, {} on claimed ground, "
              + "{} over worn paths, {} unloaded",
          village.getName(), centre.toShortString(), ringRadius, sweepRadius,
          level.dimension().location(), level.getChunkSource().getLoadedChunksCount(),
          screened, scanned, claimed, paths, unloaded);
      if (best != null) {
        Kithkyn.LOGGER.debug(
            "Taking {} facing {}: {} claimed frontage on {} sides, {} path frontage, {} blocks moved{}",
            best.site().toShortString(), best.rotation(), best.relationship().frontage(),
            best.relationship().adjacentSides(), best.pathFrontage(), best.cost().blocksMoved(),
            best.planned() ? ", planned slot" : "");
        return new Search(best.site(), best.rotation(), null, reach);
      }
      return new Search(null, Rotation.NONE, nearMiss, reach);
    }
  }

}
