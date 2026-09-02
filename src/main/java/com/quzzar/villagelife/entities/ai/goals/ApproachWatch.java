package com.quzzar.villagelife.entities.ai.goals;

import java.util.Optional;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Whether walking is actually getting a villager anywhere, and what to do when
 * it is not ([#75](https://github.com/Quzzar/villagelife/issues/75)).
 *
 * A villager who cannot reach their work is not reported as stuck: the
 * navigator only calls a path stalled if it found one, and a path that was
 * never found cannot stall. So a worker at the bottom of their own village's
 * mine stands still forever, holding a job nobody else will take — which was
 * invisible for as long as goals ran side by side and ambient strolling
 * occasionally jostled them somewhere else.
 *
 * Every work goal that walks somewhere needs the same three answers, which is
 * why they live here rather than in each goal: notice that no progress is being
 * made, let go of the work so everything else gets a turn, and after enough
 * failures accept that the villager is somewhere they cannot walk out of and
 * bring them home.
 */
public final class ApproachWatch {

  /** Ticks spent walking without ever getting closer before giving up. */
  private static final int STALL_TICKS = 200;

  /** How long a worker who could not reach the work leaves it alone. */
  private static final int STAND_DOWN_TICKS = 600;

  /** Give-ups in a row before the villager is stranded rather than unlucky. */
  private static final int STRANDED_AFTER = 3;

  /**
   * Squared per-tick move that counts as still walking. At the work speed a
   * villager covers about a sixth of a block a tick, so this sits well above a
   * standing villager's jitter and well below a stride: a body pressed to a
   * spot reads zero, a body on the move reads far more.
   */
  private static final double MOVING_SQR = 0.0025D;

  private final RealPerson person;
  private final String work;

  private double closestApproachSqr = Double.MAX_VALUE;
  private int ticksWithoutProgress;
  private int standDownUntil;
  private int consecutiveGiveUps;

  /** Where they stood last tick, to tell a body still walking from a stuck one. */
  private Vec3 lastPos;

  /**
   * @param work what this worker is failing to reach, in their own words, for
   *             the issue they will write in their log
   */
  public ApproachWatch(RealPerson person, String work) {
    this.person = person;
    this.work = work;
  }

  /** True while this worker has given up on something they could not walk to. */
  public boolean standingDown() {
    return person.tickCount < standDownUntil;
  }

  /** Call when a fresh attempt begins: a new target deserves a clean slate. */
  public void begin() {
    closestApproachSqr = Double.MAX_VALUE;
    ticksWithoutProgress = 0;
    lastPos = null;
  }

  /** Call on arrival: getting there clears the record of failed attempts. */
  public void arrived() {
    consecutiveGiveUps = 0;
    begin();
  }

  /**
   * Call once per tick while walking. Returns true when the trip should be
   * abandoned, in which case the caller must drop its target: the point is to
   * release the movement flag, and a goal that keeps walking has not.
   */
  public boolean giveUp(BlockPos target) {
    // Two ways to be making headway, and either clears the stall. Getting
    // nearer the target is the plain one. But straight-line distance is the
    // wrong test for a route that detours: a miner climbing out of the shaft to
    // fetch cobblestone walks UP to the mouth, away from the storehouse, before
    // heading over (the ramp waypoints, village/buildings/MineShaft), and
    // measured on distance to the storehouse that read as no progress at all
    // and stood the trip down a third of the way out. So a body simply still on
    // the move is not stuck either; only one pressed to a spot, going nowhere,
    // is, and that is what the tick clock now counts.
    double distanceSqr = person.blockPosition().distSqr(target);
    Vec3 now = person.position();
    boolean closer = distanceSqr < closestApproachSqr - 0.5D;
    boolean moving = lastPos != null && now.distanceToSqr(lastPos) > MOVING_SQR;
    lastPos = now;
    if (closer || moving) {
      if (closer) {
        closestApproachSqr = distanceSqr;
      }
      ticksWithoutProgress = 0;
      return false;
    }
    if (++ticksWithoutProgress < STALL_TICKS) {
      return false;
    }

    person.logIssue("I cannot get to " + work + ".", Optional.empty());
    Villagelife.LOGGER.debug("{} cannot reach {} at {} and is standing down",
        person.getFullName(), work, target.toShortString());
    standDownUntil = person.tickCount + STAND_DOWN_TICKS;
    ticksWithoutProgress = 0;

    if (++consecutiveGiveUps >= STRANDED_AFTER) {
      // Three in a row is not bad luck: this villager is somewhere they cannot
      // walk out of, usually the bottom of the village's own mine. Recover them
      // the way a lost villager is already recovered, rather than leaving a
      // village with work nobody can do.
      Villagelife.LOGGER.info("{} was stranded and has been brought back to the village center",
          person.getFullName());
      person.tpToHome();
      consecutiveGiveUps = 0;
    }
    return true;
  }

}
