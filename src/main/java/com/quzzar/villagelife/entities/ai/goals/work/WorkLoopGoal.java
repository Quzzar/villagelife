package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.EnumSet;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.ai.goals.ApproachWatch;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * The shape every work loop has: find work, walk to it, do it, stop cleanly.
 *
 * This is the <b>TRAVEL</b> phase plus the whole lifecycle, written once for
 * every job (docs/worker-loops.md). A {@link WorkStep} supplies only SELECT and
 * ACT. The split is deliberate and is the entire reason this class exists -
 * reading the hand-written work goals it replaces found every worker bug living
 * here rather than in any act, so this is the code worth having exactly one
 * copy of.
 *
 * What it guarantees, each of which was a real defect somewhere:
 *
 * <ul>
 * <li><b>It always ends.</b> {@code canContinueToUse} is final and answers
 * honestly. Three of the hand-written goals never overrode it, so it fell back
 * to {@code canUse} and the goal held its movement flag for the villager's
 * whole life - a farmer stood exactly where they were put, unable even to be
 * strolled away by something of lower priority.</li>
 * <li><b>It always walks.</b> Two of the hand-written goals never navigated at
 * all: they worked on whatever happened to be near the STATION from wherever
 * the villager was standing, so the job only ever ran by coincidence.</li>
 * <li><b>Stopping is not something a step can get wrong.</b> Five goals called
 * {@code stop()} from inside {@code tick()}, which the engine ignores - one of
 * them did not even override {@code stop()}, so the call reached an empty
 * method. A step ends a target by returning false and ends the loop by
 * selecting nothing.</li>
 * <li><b>It gives up.</b> Every loop watches whether walking is getting
 * anywhere, because the navigator never reports a path it failed to find as
 * stuck. Only six of thirteen had this.</li>
 * <li><b>It competes for movement.</b> Always {@link Flag#MOVE}, so two jobs
 * cannot both steer one villager.</li>
 * </ul>
 */
public class WorkLoopGoal<T> extends Goal {

  private final RealPerson person;
  private final WorkStep<T> step;
  private final ApproachWatch approach;

  private T target;
  private int nextSelectTick;
  private int ticksInReach;

  public WorkLoopGoal(RealPerson person, WorkStep<T> step) {
    // Every work loop walks somewhere, so every work loop competes for
    // movement rather than running alongside everything else that does.
    this.setFlags(EnumSet.of(Flag.MOVE));
    this.person = person;
    this.step = step;
    this.approach = new ApproachWatch(person, step.describe());
  }

  @Override
  public final boolean canUse() {
    if (interrupted() || this.approach.standingDown()) {
      return false;
    }
    // Looking for work is rate-limited: a fruitless scan every tick is the
    // most expensive way to do nothing.
    if (this.person.tickCount < this.nextSelectTick) {
      return false;
    }
    this.nextSelectTick = this.person.tickCount + this.step.selectEveryTicks();
    this.target = this.step.select(this.person);
    return this.target != null;
  }

  @Override
  public final boolean canContinueToUse() {
    return this.target != null && !interrupted() && !this.approach.standingDown();
  }

  @Override
  public final void start() {
    this.ticksInReach = 0;
    this.approach.begin();
    if (this.target != null) {
      this.step.acquired(this.person, this.target);
    }
  }

  @Override
  public final void stop() {
    release();
    this.ticksInReach = 0;
    this.person.getNavigation().stop();
  }

  /** The one place a target is let go, so a step always hears about it. */
  private void release() {
    if (this.target != null) {
      this.step.released(this.person, this.target);
      this.target = null;
    }
  }

  @Override
  public final void tick() {
    if (this.target == null) {
      return; // canContinueToUse ends us on the next pass
    }

    // Asked fresh each tick: a target that walks away has to be followed.
    BlockPos where = this.step.positionOf(this.target);
    this.ticksInReach++;

    if (this.person.blockPosition().distSqr(where) > this.step.reachSqr(this.person)) {
      // A worker who cannot reach their work holds the job forever otherwise:
      // the navigator only calls a path stalled if it found one, and a path it
      // never found cannot stall.
      if (this.approach.giveUp(where)) {
        release();
        return;
      }
      this.person.getNavigation().moveTo(
          where.getX() + 0.5D, where.getY(), where.getZ() + 0.5D, this.step.speed());
      // Most work happens on arrival. Laying a path happens on the way.
      if (!this.step.actWhileTravelling()) {
        return;
      }
    } else {
      this.approach.arrived();
      this.person.getNavigation().stop();
      this.person.getLookControl().setLookAt(
          where.getX(), where.getY(), where.getZ(), 30.0F, 30.0F);
    }

    if (this.ticksInReach % this.step.actEveryTicks() != 0) {
      return;
    }
    if (!this.person.swinging) {
      this.person.swing(this.person.getUsedItemHand());
    }
    if (!this.step.act(this.person, this.target)) {
      release(); // done with this one; canUse picks the next
    }
  }

  /**
   * The one copy of a check that was pasted into nine goals verbatim. Night is
   * part of it because no job works after dark yet, and a step that eventually
   * does says so rather than writing its own version of this.
   */
  private boolean interrupted() {
    return this.person.getLastHurtByMob() != null
        || this.person.isFreezing()
        || this.person.isOnFire()
        || this.person.isInterrupted()
        || (!this.step.worksAtNight() && this.person.level().isNight());
  }

}
