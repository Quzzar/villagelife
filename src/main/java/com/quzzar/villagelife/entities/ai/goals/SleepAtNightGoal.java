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

public class SleepAtNightGoal extends Goal {

  private final RealPerson person;

  public SleepAtNightGoal(RealPerson person) {
    // This goal walks the villager to their bed, so it competes for movement rather
    // than running alongside the goals that also move them (#74).
    this.setFlags(EnumSet.of(Flag.MOVE));
    this.person = person;
  }

  @Override
  public boolean canUse() {
    // Bed location is read FRESH every check, never cached at construction. A
    // villager is given its bed after its goals are built (the normal founding
    // order: arrive -> claim job/bed -> reloadState), so a cached location was
    // ZERO for life and the villager stood at the campfire all night without ever
    // resting. Requiring a real bed also means a bedless villager does not engage
    // this goal at all -- it used to activate on the job location, hold the MOVE
    // flag through canContinueToUse, and then do nothing in tick, freezing them.
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
    person.goToBed(0.5D);
  }

  @Override
  public void stop() {
    person.stopSleeping();
  }

  @Override
  public void tick() {
    BlockPos bedLoc = LocationManager.getBedLocation(person);
    if (bedLoc.equals(BlockPos.ZERO)) {
      return;
    }
    // Sleep in the HEAD half of the bed, not the foot the def points at, or the
    // villager lies half off the block with its feet hanging into the air.
    BlockPos sleepAt = bedHead(bedLoc);

    if (!person.isSleeping()) {

      if (sleepAt.distSqr(person.blockPosition()) <= 4.0D) {
        person.setDaysSinceSleep(0);
        person.startSleeping(sleepAt);
      } else if (!person.getNavigation().isInProgress()) {
        person.getNavigation().moveTo(sleepAt.getX(), sleepAt.getY(), sleepAt.getZ(), 0.5D);
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
