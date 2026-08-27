package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

public class SleepAtNightGoal extends Goal {

  private BlockPos bedLoc;
  private BlockPos jobLoc;
  private RealPerson person;

  public SleepAtNightGoal(RealPerson person) {
    // This goal walks the villager to their bed, so it competes for movement rather
    // than running alongside the goals that also move them (#74).
    this.setFlags(EnumSet.of(Flag.MOVE));
    this.person = person;
    this.bedLoc = LocationManager.getBedLocation(person);
    this.jobLoc = LocationManager.getJobLocation(person);
  }

  @Override
  public boolean canUse() {
    return person.level().isNight() && (!bedLoc.equals(BlockPos.ZERO) || !jobLoc.equals(BlockPos.ZERO));
  }

  @Override
  public boolean canContinueToUse() {
    return person.level().isNight();
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
    if (bedLoc.equals(BlockPos.ZERO)) {
      return;
    }

    if (!person.isSleeping()) {

      if (bedLoc.distSqr(person.blockPosition()) <= 4.0D) {
        person.setDaysSinceSleep(0);
        person.startSleeping(bedLoc);
      } else if (!person.getNavigation().isInProgress()) {
        person.getNavigation().moveTo(bedLoc.getX(), bedLoc.getY(), bedLoc.getZ(), 0.5D);
      }

    }

    if (person.level().isDay()) {
      if (!person.isSleeping()) {
        person.setDaysSinceSleep(person.getDaysSinceSleep() + 1);
      }
      this.stop();
    }

  }
}
