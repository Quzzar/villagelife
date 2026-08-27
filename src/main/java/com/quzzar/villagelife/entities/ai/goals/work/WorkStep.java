package com.quzzar.villagelife.entities.ai.goals.work;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.core.BlockPos;

/**
 * One thing a worker does: the <b>SELECT</b> and <b>ACT</b> halves of a work
 * loop (docs/worker-loops.md).
 *
 * Everything around these two methods - may I start, can I keep going, how do I
 * get there, what happens when I cannot, how do I stop cleanly - belongs to
 * {@link WorkLoopGoal} and is written exactly once. That division is the point.
 * Reading all thirteen hand-written work goals found nine byte-identical copies
 * of the same interrupt check, three goals that never ended because they had no
 * {@code canContinueToUse}, five that called {@code stop()} from inside
 * {@code tick()} where the engine ignores it, and two that never walked to
 * their own work at all. Not one of those bugs was in an act. They were all in
 * the surround, thirteen times over, drifting apart.
 *
 * So an implementation of this interface cannot make those mistakes: it does
 * not get a lifecycle to get wrong, and it is never asked to move anybody.
 */
public interface WorkStep {

  /**
   * Where the work is right now, or null when there is none.
   *
   * Called on a timer rather than every tick, so it may scan. It must be a
   * pure question: returning null is how a worker says "nothing to do", and it
   * is the ONLY way this loop ends on its own.
   */
  @Nullable
  BlockPos select(RealPerson person);

  /**
   * Do one slice of the work, called on the act cadence once the worker is in
   * reach of the target. Return false when this target is finished, which
   * releases it and sends the loop back to {@link #select}.
   *
   * Never navigates and never ends the goal - both belong to the loop.
   */
  boolean act(RealPerson person, BlockPos target);

  /** What the worker calls this, for the issue they log when they cannot reach it. */
  String describe();

  /** How close counts as arrived. Three blocks, which is arm's length plus slack. */
  default double reachSqr() {
    return 9.0D;
  }

  /** Ticks between {@link #act} calls once in reach. Half a second by default. */
  default int actEveryTicks() {
    return 10;
  }

  /** Ticks between {@link #select} scans while looking for work. */
  default int selectEveryTicks() {
    return 20;
  }

  /** How fast to walk to it. */
  default double speed() {
    return 0.5D;
  }

  /**
   * Whether this work carries on after dark. False for everything today; the
   * field exists because the job definitions are meant to carry it as tuning
   * rather than have each loop hard-code a night check of its own.
   */
  default boolean worksAtNight() {
    return false;
  }

}
