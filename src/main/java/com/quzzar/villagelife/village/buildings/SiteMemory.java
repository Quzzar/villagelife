package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

/**
 * What the village has learned about its own room.
 *
 * Choosing what to build filtered on cost and never on space, so a village
 * hemmed in by cliffs could pick a building that fits nowhere, fail the site
 * search, go quiet for the planning backoff, and then be asked again - and
 * answer the same way, forever. The only evidence was one DEBUG line reading
 * "Failed to find a valid location", which is indistinguishable from a village
 * that simply had nothing it wanted to build.
 *
 * Checking properly would mean a site search per candidate, and a site search
 * is the single most expensive thing a village does. So the village remembers
 * instead of re-asking: a footprint that found no site rules out every
 * footprint at least as large in BOTH dimensions, because a strictly bigger
 * rectangle cannot fit where a smaller one would not. One failure teaches the
 * whole catalogue.
 *
 * The memory is deliberately short-lived and is dropped outright whenever the
 * village builds something, since clearing ground and raising walls is exactly
 * what changes the answer. It is a cache of the world, so it is never
 * persisted: a reloaded village re-learns on its first refusal.
 */
public final class SiteMemory {

  /**
   * Village seconds a refusal stands. Long enough to stop the pick-fail-repick
   * loop spinning, short enough that terrain cleared by other work is noticed.
   */
  private static final int REMEMBER_SECONDS = 900;

  /** Refusals are kept whole rather than merged; see {@link #noSiteFor}. */
  private static final int MAX_REFUSALS = 6;

  /** One footprint that found no site, normalised so short <= long, and its expiry. */
  private record Refusal(int shortSide, int longSide, int until) {

    boolean covers(int shorter, int longer) {
      return shorter >= shortSide && longer >= longSide;
    }
  }

  private final List<Refusal> refusals = new ArrayList<>();

  /**
   * Records that nothing this size could be placed.
   *
   * Refusals are kept separately rather than merged into one smallest-yet
   * footprint, because merging over-claims: knowing that 9x11 found no site and
   * that 7x20 found no site does NOT mean 7x11 has nowhere to go - it is
   * narrower than the first and shorter than the second, and may fit where
   * neither did. Each refusal only ever rules out footprints at least as large
   * as ITSELF in both dimensions.
   */
  public void noSiteFor(BoundingBox footprint, int villageTime) {
    int shorter = Math.min(footprint.getXSpan(), footprint.getZSpan());
    int longer = Math.max(footprint.getXSpan(), footprint.getZSpan());
    forget(villageTime);
    // A refusal already covered by a smaller one adds nothing.
    if (refusals.stream().anyMatch(r -> r.covers(shorter, longer))) {
      return;
    }
    // Anything this new refusal covers is now redundant.
    refusals.removeIf(r -> shorter <= r.shortSide() && longer <= r.longSide());
    if (refusals.size() >= MAX_REFUSALS) {
      refusals.remove(0);
    }
    refusals.add(new Refusal(shorter, longer, villageTime + REMEMBER_SECONDS));
    Villagelife.LOGGER.debug(
        "No site for a {}x{} footprint; ruling out anything at least {}x{} for {}s",
        footprint.getXSpan(), footprint.getZSpan(), shorter, longer, REMEMBER_SECONDS);
  }

  /** Whether a footprint this size is known not to fit. */
  public boolean ruledOut(@Nullable BoundingBox footprint, int villageTime) {
    if (footprint == null) {
      return false;
    }
    forget(villageTime);
    int shorter = Math.min(footprint.getXSpan(), footprint.getZSpan());
    int longer = Math.max(footprint.getXSpan(), footprint.getZSpan());
    return refusals.stream().anyMatch(r -> r.covers(shorter, longer));
  }

  /** Forgotten the moment the village's shape changes. */
  public void clear() {
    refusals.clear();
  }

  private void forget(int villageTime) {
    refusals.removeIf(r -> villageTime >= r.until());
  }

  /**
   * A definition's footprint, unrotated. Callers compare short and long sides
   * rather than x and z, so the rotation a building will eventually be given
   * does not change the answer.
   */
  @Nullable
  public static BoundingBox footprintOf(ServerLevelAccessor level, String definition) {
    var template = level.getLevel().getStructureManager().get(
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, definition));
    return template.map(t -> t.getBoundingBox(
        new StructurePlaceSettings().setRotation(Rotation.NONE), BlockPos.ZERO)).orElse(null);
  }

}
