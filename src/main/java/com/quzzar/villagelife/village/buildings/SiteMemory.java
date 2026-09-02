package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Utils;
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
 * A refusal also keeps the nearest thing to a site the search saw, so the
 * knowledge is not only "no" but "no, and here is where it came closest": the
 * planner's briefing and every villager's chat carry it ({@link #describe}),
 * which is how a builder can tell a player that the slope east of the fire is
 * the problem, and the player can decide to level it.
 *
 * The memory is deliberately short-lived and is dropped outright whenever the
 * village builds something, since clearing ground and raising walls is exactly
 * what changes the answer. It is a cache of the world, so it is never
 * persisted: a reloaded village re-learns on its first refusal.
 */
public final class SiteMemory {

  /**
   * Village seconds a refusal stands. Long enough to stop the pick-fail-repick
   * loop spinning, short enough that terrain cleared by other work, or levelled
   * by a player, is noticed.
   */
  private static final int REMEMBER_SECONDS = 900;

  /** Refusals are kept whole rather than merged; see {@link #noSiteFor}. */
  private static final int MAX_REFUSALS = 6;

  /**
   * The nearest thing to a site a refused search saw: ground that only more
   * digging than the village allows would turn into one. {@code centre} is the
   * middle of where the footprint would have stood, {@code blocksOffPlane} how
   * far its columns stand from level in total, and {@code reason} the fact
   * that ruled it out, in words a briefing can carry.
   */
  public record NearMiss(BlockPos centre, int blocksOffPlane, String reason) {
  }

  /**
   * One footprint that found no site, normalised so short <= long, its expiry,
   * how far from the fire the search managed to read ground, and the nearest
   * thing to a site it saw.
   */
  private record Refusal(int shortSide, int longSide, int until, int reach, @Nullable NearMiss nearMiss) {

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
  public void noSiteFor(BoundingBox footprint, int villageTime, int reach, @Nullable NearMiss nearMiss) {
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
    refusals.add(new Refusal(shorter, longer, villageTime + REMEMBER_SECONDS, reach, nearMiss));
    Villagelife.LOGGER.debug(
        "No site for a {}x{} footprint within {} blocks; ruling out anything at least {}x{} for {}s",
        footprint.getXSpan(), footprint.getZSpan(), reach, shorter, longer, REMEMBER_SECONDS);
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

  /**
   * The village's room as a fact for a briefing, or null when nothing has been
   * refused lately: which footprints found no ground and how far the village
   * looked, the nearest thing to a site it saw and why that was not one, and
   * the rule that keeps it from digging further. Stated, not judged: whether
   * to build smaller, wait, or level the slope by hand is the reader's call.
   */
  @Nullable
  public String describe(BlockPos fire, int villageTime) {
    forget(villageTime);
    if (refusals.isEmpty()) {
      return null;
    }
    StringBuilder out = new StringBuilder("Nothing ");
    out.append(String.join(" or ", refusals.stream().map(r -> r.shortSide() + " by " + r.longSide()).toList()))
        .append(" or larger has found ground");
    int reach = refusals.stream().mapToInt(Refusal::reach).max().orElse(0);
    if (reach > 0) {
      out.append(" within about ").append(reach).append(" blocks of the fire");
    }
    out.append(" lately.");
    refusals.stream()
        .filter(r -> r.nearMiss() != null)
        .min(Comparator.comparingInt(r -> r.nearMiss().blocksOffPlane()))
        .ifPresent(closest -> {
          NearMiss miss = closest.nearMiss();
          int dx = miss.centre().getX() - fire.getX();
          int dz = miss.centre().getZ() - fire.getZ();
          int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
          out.append(" The nearest thing to a site was ").append(distance).append(" blocks ")
              .append(Utils.compassPoint(Math.atan2(dz, dx))).append(" of the fire: ")
              .append(miss.reason()).append('.');
        });
    out.append(" Villagers level ground only lightly and never reshape a hill.");
    return out.toString();
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
