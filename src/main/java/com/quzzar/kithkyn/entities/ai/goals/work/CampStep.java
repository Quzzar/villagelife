package com.quzzar.kithkyn.entities.ai.goals.work;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.entities.RealPerson;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;

/**
 * A roaming wanderer's night: a fire of their own out of three logs from the
 * pack, set where dusk finds them, whatever they carry raw roasted on it, and
 * the night sat out beside it. At dawn the fire is put out and left where it
 * stood, nothing of it back in the pack, and the road goes on
 * (docs/population-and-labor.md, "The road"; Aaron, 2026-09-02). A wanderer
 * with fewer than three logs has no camp and walks the night through as
 * before. The tending is the same {@link CampfireRoast} the idle camper uses
 * at the village fire; only the fire is theirs, and only the pack feeds it.
 *
 * <p>The camp is remembered on the person rather than here
 * ({@link RealPerson#getCamp}), so a reload or a recruitment mid-night still
 * puts it out. select returns the person's own position while there is no
 * camp, so the loop is trivially in reach and the pitching decision is made in
 * one place, in {@link #act}, rather than split across a scan and a strike.
 */
public final class CampStep implements BlockWorkStep {
  /** Logs a camp's fire costs. */
  public static final int CAMP_LOGS = 3;
  /** How far around the feet a level spot for the fire is looked for. */
  private static final int SPOT_RADIUS = 2;

  private final CampfireRoast roast = new CampfireRoast();

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    if (!person.isRoamingWanderer()) {
      return null;
    }
    BlockPos camp = person.getCamp();
    if (camp != null) {
      return camp; // tended by night, broken by day
    }
    if (!person.level().isNight() || PackLogistics.carriedIn(person, ItemTags.LOGS) < CAMP_LOGS) {
      return null;
    }
    // The pitch decision itself is made in act; here the target is simply where
    // the wanderer stands, which is trivially in reach.
    return person.blockPosition();
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    if (!(person.level() instanceof ServerLevel level)) {
      return false;
    }
    BlockPos camp = person.getCamp();
    if (camp == null) {
      return pitch(person, level);
    }
    if (!level.isNight()) {
      // Dawn. The fire is put out and left behind; nothing of it goes back in
      // the pack, and the road takes over.
      this.roast.abandon(person, camp);
      person.breakCamp();
      Kithkyn.LOGGER.info("[road] '{}' broke camp at {}", person.getFullName(), camp.toShortString());
      return false;
    }
    CampfireBlockEntity campfire = CampfireRoast.litFireAt(level, camp);
    if (campfire == null) {
      // Doused or taken in the night: nothing to sit by. Back to the road.
      Kithkyn.LOGGER.debug("[road] '{}' found no lit fire at their camp {} ({})", person.getFullName(),
          camp.toShortString(), level.getBlockState(camp));
      this.roast.abandon(person, null);
      person.setCamp(null);
      return false;
    }
    if (this.roast.tending()) {
      if (this.roast.done(person)) {
        this.roast.finish(person, campfire, camp);
      }
      return true;
    }
    // Nothing raw, or a full fire, is simply the night beside it.
    this.roast.start(person, campfire);
    return true;
  }

  /**
   * Set the fire down on a level spot at or beside the feet, spend the logs,
   * and remember it as the camp. Nothing happens when no level spot is in
   * reach (a wanderer on a slope or at the water's edge walks on and tries
   * again) or the logs ran out between scan and strike.
   */
  private boolean pitch(RealPerson person, ServerLevel level) {
    if (PackLogistics.carriedIn(person, ItemTags.LOGS) < CAMP_LOGS) {
      return false;
    }
    BlockPos spot = fireSpotNear(level, person.blockPosition());
    if (spot == null) {
      Kithkyn.LOGGER.debug("[road] '{}' wants to camp but finds no level ground at {}",
          person.getFullName(), person.blockPosition().toShortString());
      return false;
    }
    PackLogistics.spendIn(person, ItemTags.LOGS, CAMP_LOGS);
    level.setBlock(spot, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), 3);
    person.setCamp(spot);
    Kithkyn.LOGGER.info("[road] '{}' made camp at {} from {} logs", person.getFullName(),
        spot.toShortString(), CAMP_LOGS);
    return false; // re-select: the fire itself
  }

  @Override
  public void released(RealPerson person, BlockPos target) {
    this.roast.abandon(person, person.getCamp());
  }

  @Override
  public String describe() {
    return "my camp";
  }

  @Override
  public String activity() {
    return this.roast.tending() ? "cooking at my own fire" : "sitting by my own fire";
  }

  @Override
  public int actEveryTicks() {
    return 20;
  }

  @Override
  public int selectEveryTicks() {
    return 40;
  }

  /** The camp is the night's whole business. */
  @Override
  public boolean worksAtNight() {
    return true;
  }

  /**
   * A spot beside the feet a fire can stand on: air (or replaceable growth like
   * grass) with a full-faced block under it, in a loaded chunk. The ring around
   * the feet is tried first, widening out to {@link #SPOT_RADIUS}, so the fire
   * sits next to the wanderer rather than under them; the feet themselves are
   * the last resort, which on ordinary ground always works, so only genuinely
   * broken ground (a slope, a shore) comes up empty.
   */
  @Nullable
  private static BlockPos fireSpotNear(ServerLevel level, BlockPos feet) {
    for (int r = 1; r <= SPOT_RADIUS; r++) {
      for (int dx = -r; dx <= r; dx++) {
        for (int dz = -r; dz <= r; dz++) {
          if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
            continue; // only the new ring at this radius
          }
          BlockPos spot = feet.offset(dx, 0, dz);
          if (fireSpot(level, spot)) {
            return spot;
          }
        }
      }
    }
    return fireSpot(level, feet) ? feet : null;
  }

  /** Room for a fire here: a replaceable block with a sturdy top under it, in a loaded chunk. */
  private static boolean fireSpot(ServerLevel level, BlockPos spot) {
    if (!level.hasChunkAt(spot)) {
      return false;
    }
    var state = level.getBlockState(spot);
    // Air or replaceable growth (grass, ferns, flowers), but not a fluid: a
    // campfire set in water waterlogs and goes out.
    boolean clear = (state.isAir() || state.canBeReplaced()) && state.getFluidState().isEmpty();
    return clear
        && level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), Direction.UP);
  }
}
