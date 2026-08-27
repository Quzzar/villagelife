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

  public static final int[] SEARCH_HEIGHTS = new int[]{0, 1, -1, 2, -2, 3, -3, 4, -4};

  public static BlockPos findValidLocation(ServerLevelAccessor levelAccess, BlockPos centerPos, BoundingBox bounds, Village village, Random random) {

    int searchRadius = SEARCH_RADIUS_PER_4_BUILDINGS * (int) Math.ceil(village.getBuildings().size() / 4.0D);
    // A free site always wins; otherwise the cheapest ground to prepare does.
    BlockPos best = null;
    int bestCost = Integer.MAX_VALUE;

    for (int rel_y : SEARCH_HEIGHTS) {
      for (int n = 1; n <= searchRadius; n += SEARCH_INTERVAL) {

        int rel_x = (int) (Math.max(random.nextInt(n), random.nextInt(n)) + village.getTownCenter().getRadius());
        int rel_z = (int) (Math.max(random.nextInt(n), random.nextInt(n)) + village.getTownCenter().getRadius());

        if (random.nextBoolean()) {
          rel_x *= -1;
        }
        if (random.nextBoolean()) {
          rel_z *= -1;
        }

        BlockPos candidate = centerPos.offset(rel_x, rel_y, rel_z);
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
    }

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
