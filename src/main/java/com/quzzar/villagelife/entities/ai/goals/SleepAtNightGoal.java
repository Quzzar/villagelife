package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

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
