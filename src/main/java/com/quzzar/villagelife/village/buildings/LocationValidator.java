package com.quzzar.villagelife.village.buildings;

import java.util.Random;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Finds a spot for a new building by scoring random candidates near the
 * village with {@link SitePreparation} and taking the first free one. The
 * cost gate is deliberately zero for now: sites that would need clearing or
 * levelling are scored (so the planner can see and later narrate them) but
 * not yet chosen, because nothing executes preparation work yet
 * (docs/site-selection.md). The resumable budgeted search and site cache from
 * that doc are later steps on the same seam.
 */
public class LocationValidator {

  public static final int SEARCH_RADIUS_PER_4_BUILDINGS = 20;
  public static final int SEARCH_INTERVAL = 2;

  /** Candidate positions tried per search; each is snapped to the real ground. */
  public static final int CANDIDATES = 24;

  /** Blocks of clear ground kept between the town centre and its neighbours. */
  public static final int MIN_STAND_OFF = 2;

  public static BlockPos findValidLocation(ServerLevelAccessor levelAccess, BlockPos centerPos, BoundingBox bounds, Village village, Random random) {

    // The village grows outwards as it builds, but the ring never collapses: a
    // village with nothing built yet still has to put its first building
    // somewhere. When this was a plain multiple of the building count, a young
    // village searched a radius of zero, every candidate landed on the town
    // centre's own footprint, and no site was ever scored at all.
    int searchRadius = SEARCH_RADIUS_PER_4_BUILDINGS * (1 + village.getBuildings().size() / 4);
    // Never propose the ground the town centre is standing on.
    int standOff = (int) village.getTownCenter().getRadius() + MIN_STAND_OFF;
    // A free site always wins; otherwise the cheapest ground to prepare does.
    BlockPos best = null;
    int bestCost = Integer.MAX_VALUE;
    int unloaded = 0;
    int claimed = 0;
    int priced = 0;

    for (int attempt = 0; attempt < CANDIDATES; attempt++) {

      int n = 1 + random.nextInt(Math.max(1, searchRadius));
      int rel_x = Math.max(random.nextInt(n), random.nextInt(n));
      int rel_z = Math.max(random.nextInt(n), random.nextInt(n));
      if (random.nextBoolean()) {
        rel_x *= -1;
      }
      if (random.nextBoolean()) {
        rel_z *= -1;
      }
      // Push anything that landed on the town centre out past it, along one
      // axis only, so buildings can still sit due north or east of the fire
      // instead of only on the diagonals.
      if (Math.abs(rel_x) < standOff && Math.abs(rel_z) < standOff) {
        rel_x += rel_x < 0 ? -standOff : standOff;
      }

      BlockPos column = centerPos.offset(rel_x, 0, rel_z);
      // Chunk presence, not isLoaded: isLoaded also fails a position outside
      // the world's build height, which a search column can be while the
      // ground beneath it is perfectly real and loaded.
      if (!levelAccess.getLevel().hasChunkAt(column)) {
        unloaded++;
        continue;
      }
      // Never start from ground the village has already built on. Without this
      // the heightmap hands back the ROOF of an existing building as the
      // surface, and every such candidate is scored as a site metres above the
      // ground it is supposed to sit on.
      if (village.hasClaimed(column)) {
        claimed++;
        continue;
      }
      // Build on the ground, not at a guessed elevation. Sites used to be
      // scored at the village centre's height plus a blind offset, which the
      // levelling budget then rejected for being metres above or below the
      // actual terrain: every candidate came back impossible.
      BlockPos candidate = levelAccess.getLevel()
          .getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, column);
      // The footprint extends from the origin, so a candidate whose far corner
      // sits on someone's roof is no better than one that starts there.
      if (village.hasClaimed(candidate.offset(bounds.maxX(), 0, bounds.maxZ()))
          || village.hasClaimed(candidate.offset(bounds.maxX(), 0, 0))
          || village.hasClaimed(candidate.offset(0, 0, bounds.maxZ()))) {
        claimed++;
        continue;
      }

      priced++;
      SitePreparation.SiteCost cost = SitePreparation.score(levelAccess, village, candidate, bounds);
      if (cost.isFree()) {
        return candidate;
      }
      if (!cost.impossible() && (best == null || cost.blocksMoved() < bestCost)) {
        // Worth having if nothing free turns up: the builder can prepare it.
        best = candidate;
        bestCost = cost.blocksMoved();
      }
      Villagelife.LOGGER.debug("Site at {} costs {}", candidate.toShortString(), cost.describe());
    }

    // One line that says what the whole search saw. Without it a search that
    // skips every candidate before scoring is indistinguishable from one where
    // every site was genuinely bad.
    Villagelife.LOGGER.debug("Site search for '{}' around {} within {}: {} scored, {} on claimed ground, {} unloaded",
        village.getName(), centerPos.toShortString(), searchRadius, priced, claimed, unloaded);

    if (best != null) {
      Villagelife.LOGGER.debug("No free site; taking {} at a cost of {} blocks moved",
          best.toShortString(), bestCost);
      return best;
    }
    return BlockPos.ZERO;

  }

  public static double getBuildingRadius(BoundingBox bounds){
    return Math.max(bounds.getXSpan()/2, bounds.getZSpan()/2);
  }

}
