package com.quzzar.villagelife.entities.ai.goals;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * A guard's loose watch: walk from one part of the village to another and look
 * about, so a guard with nothing pressing is out making its rounds rather than
 * idling. Registered at the visiting priority and ahead of it
 * ({@link SeekConversationGoal}), so a guard on watch is not a chat magnet: it
 * patrols by default and only strikes up a conversation in the pauses between
 * legs. Combat and panic outrank the watch, so a real threat pulls the guard
 * straight off it.
 *
 * <p>Deliberately loose (Aaron, 2026-09-02): not a fixed circuit and not a
 * quick-march. A leg is a relaxed walk to a random building or the campfire,
 * then a pause on the spot before the next, so the watch has the unhurried feel
 * of someone doing their rounds. Guards do not sleep, so the watch stands day
 * and night; the pauses are where the night restock and the odd tree still fit.
 */
public class GuardPatrolGoal extends Goal {

  /** A leisurely walk, below the guard's combat pace: this is a round, not a march. */
  private static final double SPEED = 0.45D;

  /** Close enough that a leg counts as walked. */
  private static final double ARRIVED_SQR = 3.0D * 3.0D;

  /** The unhurried pause on the spot between legs, in ticks (about 3 to 8 seconds). */
  private static final int PAUSE_MIN = 60;
  private static final int PAUSE_MAX = 160;

  /** A leg is given up rather than ground against ground the guard cannot reach. */
  private static final int LEG_TIMEOUT_TICKS = 200;

  private final RealPerson guard;
  private BlockPos target;
  private long resumeAt;
  private int legTicks;

  public GuardPatrolGoal(RealPerson guard) {
    this.guard = guard;
    this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
  }

  @Override
  public boolean canUse() {
    if (guard.getVillage() == null || guard.level().getGameTime() < resumeAt) {
      return false;
    }
    this.target = pickPoint();
    return this.target != null && this.target.distSqr(guard.blockPosition()) > ARRIVED_SQR;
  }

  @Override
  public boolean canContinueToUse() {
    return target != null
        && legTicks < LEG_TIMEOUT_TICKS
        && target.distSqr(guard.blockPosition()) > ARRIVED_SQR
        && !guard.getNavigation().isDone();
  }

  @Override
  public void start() {
    legTicks = 0;
    walkToTarget();
  }

  @Override
  public void tick() {
    legTicks++;
    if (target == null) {
      return;
    }
    guard.getLookControl().setLookAt(target.getX() + 0.5D, target.getY() + 1.0D, target.getZ() + 0.5D);
    // A path that finished short of the target (a doorway, a fence) is nudged on
    // once; the leg timeout ends a leg that truly cannot arrive.
    if (guard.getNavigation().isDone() && target.distSqr(guard.blockPosition()) > ARRIVED_SQR) {
      walkToTarget();
    }
  }

  @Override
  public void stop() {
    // A pause on the spot before the next leg keeps the watch unhurried, and the
    // gap is where a chat, the night restock, or the odd tree take their turn.
    resumeAt = guard.level().getGameTime() + PAUSE_MIN + guard.getRandom().nextInt(PAUSE_MAX - PAUSE_MIN);
    target = null;
    guard.getNavigation().stop();
  }

  private void walkToTarget() {
    guard.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, SPEED);
  }

  /** A random building to walk to, or the campfire when the village has none yet; null when neither. */
  private BlockPos pickPoint() {
    Village village = guard.getVillage();
    List<BlockPos> spots = new ArrayList<>();
    for (Building building : village.getBuildings()) {
      spots.add(BlockPos.of(building.getCenterLocation()));
    }
    BlockPos fire = village.getGatheringPoint();
    if (fire != null && !fire.equals(BlockPos.ZERO)) {
      spots.add(fire);
    }
    return spots.isEmpty() ? null : spots.get(guard.getRandom().nextInt(spots.size()));
  }
}
