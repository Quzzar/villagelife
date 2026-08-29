package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

public class SleepAtNightGoal extends Goal {

  /**
   * How close (squared) a bedless villager comes to the campfire before lying
   * down. Sized so a handful of homeless villagers settle in a loose ring around
   * the fire rather than stacking on the single gathering block. A bed, by
   * contrast, is exact: the villager walks right up to it.
   */
  private static final double CAMPFIRE_REST_RANGE_SQR = 9.0D;

  private final RealPerson person;

  public SleepAtNightGoal(RealPerson person) {
    // This goal walks the villager to their rest spot, so it competes for
    // movement rather than running alongside the goals that also move them (#74).
    this.setFlags(EnumSet.of(Flag.MOVE));
    this.person = person;
  }

  @Override
  public boolean canUse() {
    // The rest target is read FRESH every check, never cached at construction: a
    // villager is given its bed after its goals are built, so a cached location
    // was ZERO for life and the villager stood at the campfire all night without
    // ever resting (1d4523f). A bedless villager still engages this goal, but
    // rests at the campfire instead of a bed (see restTarget): the reservoir is
    // population over beds by up to the idle cap (population-and-labor.md), so
    // someone is routinely homeless, and "sleep like anyone else" is the design.
    // The old freeze this used to cause is gone because tick() actually walks
    // them to the fire and lies them down rather than holding the MOVE flag and
    // doing nothing.
    return person.level().isNight() && !restTarget().equals(BlockPos.ZERO);
  }

  @Override
  public boolean canContinueToUse() {
    return person.level().isNight() && !restTarget().equals(BlockPos.ZERO);
  }

  @Override
  public void start() {
    if (hasBed()) {
      // A bed also gets the villager to stow their pack on the way there.
      person.goToBed(0.5D);
    } else {
      // Bedless: head for the fire to doze. Not goToBed, whose target is the bed
      // or, failing that, the job station: a homeless villager rests at the
      // campfire like any other idle camper, not at a post they may still hold.
      BlockPos fire = campfireRest();
      if (!fire.equals(BlockPos.ZERO)) {
        person.getNavigation().moveTo(fire.getX(), fire.getY(), fire.getZ(), 0.5D);
      }
    }
  }

  @Override
  public void stop() {
    person.stopSleeping();
  }

  @Override
  public void tick() {
    BlockPos bed = LocationManager.getBedLocation(person);
    boolean hasBed = !bed.equals(BlockPos.ZERO);

    // A bed points at its foot; sleep in the HEAD half or the villager lies half
    // off the block with its feet in the air. Bedless villagers doze beside the
    // fire instead.
    BlockPos target = hasBed ? bedHead(bed) : campfireRest();
    if (target.equals(BlockPos.ZERO)) {
      return;
    }

    if (!person.isSleeping()) {
      // A bed is a single square the villager walks right up to and lies in. The
      // campfire is one shared spot, so a bedless villager lies down a few blocks
      // out at its OWN position, spreading the homeless around the fire instead
      // of stacking them all on the one gathering block.
      double rangeSqr = hasBed ? 4.0D : CAMPFIRE_REST_RANGE_SQR;
      BlockPos sleepAt = hasBed ? target : person.blockPosition();

      if (target.distSqr(person.blockPosition()) <= rangeSqr) {
        person.setDaysSinceSleep(0);
        person.startSleeping(sleepAt);
      } else if (!person.getNavigation().isInProgress()) {
        person.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 0.5D);
      }
    }

    if (person.level().isDay()) {
      if (!person.isSleeping()) {
        person.setDaysSinceSleep(person.getDaysSinceSleep() + 1);
      }
      this.stop();
    }
  }

  /** Whether this villager has a real, reachable bed right now. */
  private boolean hasBed() {
    return !LocationManager.getBedLocation(person).equals(BlockPos.ZERO);
  }

  /**
   * The night's rest spot: the head of this villager's own bed if they have one,
   * otherwise a standing spot beside the village campfire. Returns ZERO only when
   * the villager has neither a bed nor a village to gather in, and the goal then
   * does not engage at all.
   */
  private BlockPos restTarget() {
    BlockPos bed = LocationManager.getBedLocation(person);
    return bed.equals(BlockPos.ZERO) ? campfireRest() : bedHead(bed);
  }

  /** A standing spot beside the village campfire, or ZERO if there is no village. */
  private BlockPos campfireRest() {
    Village village = person.getVillage();
    return village == null ? BlockPos.ZERO : village.getGatheringPoint();
  }

  /**
   * The head (pillow) half of a bed, given either of its blocks. A bed is two
   * blocks and its FACING points foot->head, so the head of a foot block is one
   * step along the facing; a head block, or anything that is not a bed, is
   * returned unchanged.
   */
  private BlockPos bedHead(BlockPos bedPos) {
    BlockState state = person.level().getBlockState(bedPos);
    if (state.getBlock() instanceof BedBlock
        && state.getValue(BedBlock.PART) == BedPart.FOOT) {
      return bedPos.relative(state.getValue(HorizontalDirectionalBlock.FACING));
    }
    return bedPos;
  }
}
