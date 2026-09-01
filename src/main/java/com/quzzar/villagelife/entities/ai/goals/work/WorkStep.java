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
public interface WorkStep<T> {

  /**
   * Where the work is right now, or null when there is none.
   *
   * Called on a timer rather than every tick, so it may scan. It must be a
   * pure question: returning null is how a worker says "nothing to do", and it
   * is the ONLY way this loop ends on its own.
   */
  @Nullable
  T select(RealPerson person);

  /**
   * Do one slice of the work, called on the act cadence once the worker is in
   * reach of the target. Return false when this target is finished, which
   * releases it and sends the loop back to {@link #select}.
   *
   * Never navigates and never ends the goal - both belong to the loop.
   */
  boolean act(RealPerson person, T target);

  /**
   * Where a target is, asked every tick rather than once, because some targets
   * move: a blacksmith walks to a villager whose armour needs mending, and that
   * villager does not stand still to be found.
   */
  BlockPos positionOf(T target);

  /** What the worker calls this, for the issue they log when they cannot reach it. */
  String describe();

  /**
   * Called once when the loop takes a target on, before the worker sets off.
   * Somewhere to tell a subsystem that work has started on it.
   */
  default void acquired(RealPerson person, T target) {
  }

  /**
   * Called whenever the loop lets a target go - finished, abandoned, or the
   * whole goal stopping. Somewhere to drop per-target state and undo anything
   * shown to players, such as the block-cracking overlay, which otherwise
   * stays on a block the worker walked away from.
   */
  default void released(RealPerson person, T target) {
  }

  /**
   * How close counts as arrived. Three blocks by default, which is arm's length
   * plus slack. Takes the worker because some reaches are not fixed: a builder
   * counts as being at a building site once inside its radius, and that radius
   * is a property of whatever they are currently raising.
   */
  default double reachSqr(RealPerson person) {
    return 9.0D;
  }

  /**
   * Whether the worker is close enough to act: within {@link #reachSqr} of the
   * target's position, by default. A step whose work is not where it walks to
   * answers for itself: a chop walks to the ground beside a trunk and asks
   * whether the log above is within the axe's reach, which no single distance
   * to a position can say.
   */
  default boolean inReach(RealPerson person, T target) {
    return person.blockPosition().distSqr(positionOf(target)) <= reachSqr(person);
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
   * Whether the act runs while still walking rather than on arrival.
   *
   * Almost all work happens at a target. Laying a path does not: the builder
   * wears the route by walking it, so the act belongs to the journey and the
   * destination is only where it ends. May vary with the step's own state - a
   * path-layer walks to one end quietly and lays on the way to the other.
   */
  default boolean actWhileTravelling() {
    return false;
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
