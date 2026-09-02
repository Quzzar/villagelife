package com.quzzar.villagelife.entities.ai.goals.work;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;

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
 * <p>Three logs and no more, the way the road's tools waive sticks: the
 * fundamentals the building recipes speak. The fire is remembered on the
 * person rather than here, so a reload or a recruitment mid-night still puts
 * it out ({@link RealPerson#breakCamp}).
 */
public final class CampStep implements BlockWorkStep {
  /** Logs a camp's fire costs. */
  public static final int CAMP_LOGS = 3;
  /** Beside the fire, squared. */
  private static final double REACH_SQR = 4.0D;

  private final CampfireRoast roast = new CampfireRoast();
  /** A spot chosen for the fire but not yet lit. */
  @Nullable
  private BlockPos pitching;

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
    if (!person.level().isNight() || PackLogistics.carriedIn(person, ItemTags.LOGS) < CAMP_LOGS
        || !(person.level() instanceof ServerLevel level)) {
      return null;
    }
    this.pitching = fireSpotBeside(level, person.blockPosition());
    return this.pitching;
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    if (!(person.level() instanceof ServerLevel level)) {
      return false;
    }
    BlockPos camp = person.getCamp();
    if (camp == null) {
      return pitch(person, level, target);
    }
    if (!level.isNight()) {
      // Dawn. The fire is put out and left behind; nothing of it goes back in
      // the pack, and the road takes over.
      this.roast.abandon(person, camp);
      person.breakCamp();
      Villagelife.LOGGER.info("[road] '{}' broke camp at {}", person.getFullName(), camp.toShortString());
      return false;
    }
    CampfireBlockEntity campfire = CampfireRoast.litFireAt(level, camp);
    if (campfire == null) {
      // Doused or taken in the night: nothing to sit by. Back to the road.
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

  /** The fire goes down where dusk found them, out of the pack. */
  private boolean pitch(RealPerson person, ServerLevel level, BlockPos spot) {
    if (!spot.equals(this.pitching) || !fireSpot(level, spot)
        || PackLogistics.carriedIn(person, ItemTags.LOGS) < CAMP_LOGS) {
      this.pitching = null;
      return false;
    }
    PackLogistics.spendIn(person, ItemTags.LOGS, CAMP_LOGS);
    level.setBlock(spot, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), 3);
    person.setCamp(spot);
    this.pitching = null;
    Villagelife.LOGGER.info("[road] '{}' made camp at {} from {} logs", person.getFullName(),
        spot.toShortString(), CAMP_LOGS);
    return false; // re-select: the fire itself
  }

  @Override
  public void released(RealPerson person, BlockPos target) {
    this.roast.abandon(person, person.getCamp());
    this.pitching = null;
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
  public double reachSqr(RealPerson person) {
    return REACH_SQR;
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

  /** A spot beside the feet a fire can stand on: the first free one round the compass, or null. */
  @Nullable
  private static BlockPos fireSpotBeside(ServerLevel level, BlockPos feet) {
    for (Direction side : Direction.Plane.HORIZONTAL) {
      BlockPos spot = feet.relative(side);
      if (fireSpot(level, spot)) {
        return spot;
      }
    }
    return null;
  }

  /** Air with a full-faced block under it, in a loaded chunk. */
  private static boolean fireSpot(ServerLevel level, BlockPos spot) {
    return level.hasChunkAt(spot) && level.getBlockState(spot).isAir()
        && level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), Direction.UP);
  }
}
