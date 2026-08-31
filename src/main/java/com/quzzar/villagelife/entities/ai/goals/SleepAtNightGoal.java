package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * Walks a villager to their own bed at night and lies them in it.
 *
 * <p>Bed-ONLY by decision (Aaron, 2026-08-31): a villager with no bed does not
 * sleep. The bedless used to doze in a loose ring around the campfire, but the
 * rest spot was the gathering point itself, so villagers lay down in and
 * against the lit fire and the lie-down glitched endlessly (a842efc fought one
 * such loop). Now they simply stay up by the fire until the village houses
 * them. Employment already depends on housing (the bed gate in JobClaiming),
 * so the night crowd standing at the fire is exactly the unhoused idle, and a
 * villager sleeping badly is a villager whose village needs to build.
 */
public class SleepAtNightGoal extends Goal {

  private final RealPerson person;

  public SleepAtNightGoal(RealPerson person) {
    // This goal walks the villager to their bed, so it competes for movement
    // rather than running alongside the goals that also move them (#74).
    this.setFlags(EnumSet.of(Flag.MOVE));
    this.person = person;
  }

  @Override
  public boolean canUse() {
    // The bed is read FRESH every check, never cached at construction: a
    // villager is given its bed after its goals are built, so a cached location
    // was ZERO for life and the villager stood at the campfire all night
    // without ever resting (1d4523f).
    return person.level().isNight()
        && !LocationManager.getBedLocation(person).equals(BlockPos.ZERO);
  }

  @Override
  public boolean canContinueToUse() {
    return person.level().isNight()
        && !LocationManager.getBedLocation(person).equals(BlockPos.ZERO);
  }

  @Override
  public void start() {
    // goToBed also gets the villager to stow their pack on the way there.
    person.goToBed(0.5D);
  }

  @Override
  public void stop() {
    person.stopSleeping();
  }

  @Override
  public void tick() {
    BlockPos bed = LocationManager.getBedLocation(person);
    if (bed.equals(BlockPos.ZERO)) {
      return; // the bed vanished mid-night; canContinueToUse ends the goal
    }
    // A bed points at its foot; sleep in the HEAD half or the villager lies
    // half off the block with its feet in the air.
    BlockPos target = bedHead(bed);

    if (!person.isSleeping()) {
      if (target.distSqr(person.blockPosition()) <= 4.0D) {
        // Stop navigating before lying down: a sleeping villager is immobile
        // (Person.isImmobile), so a leftover path would grind in place against
        // the bed all night.
        person.getNavigation().stop();
        person.setDaysSinceSleep(0);
        person.startSleeping(target);
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
