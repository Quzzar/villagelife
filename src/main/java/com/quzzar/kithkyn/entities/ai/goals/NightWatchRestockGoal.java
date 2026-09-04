package com.quzzar.kithkyn.entities.ai.goals;

import com.quzzar.kithkyn.entities.RealPerson;

import net.minecraft.world.entity.ai.goal.Goal;

/**
 * The sleepless job's half of bedtime. A job that stands watch through the
 * night (Occupation.sleepsAtNight() false) never runs SleepAtNightGoal, but
 * bedtime is when the village hands out gear, rations and upgrades, so the
 * watch runs the same stow-and-restock where they stand instead of at a bed.
 *
 * <p>No movement flag and no navigation: the restock is instantaneous, and the
 * whole point is that the guard stays on patrol. Cadence matches the sleeper
 * path exactly - restockForNightWatch shares goToBed's 100-tick cooldown, so
 * this refires through the night the same way goToBed does for sleepers.
 */
public class NightWatchRestockGoal extends Goal {

  private final RealPerson person;

  public NightWatchRestockGoal(RealPerson person) {
    this.person = person;
  }

  @Override
  public boolean canUse() {
    return person.level().isNight() && person.callToBedCoolDown <= 0;
  }

  @Override
  public boolean canContinueToUse() {
    // One-shot: the restock happens in start; the cooldown gates the refire.
    return false;
  }

  @Override
  public void start() {
    // The watch is this job's rest. Zeroed so the daysSinceSleep last-resort
    // recovery (UnstuckPersonGoal) cannot read a count carried over from
    // before the job - an unhoused sleepless stretch, say - as a villager
    // wedged for days, and teleport a working guard home every night.
    person.noteSlept();
    person.restockForNightWatch();
  }
}
