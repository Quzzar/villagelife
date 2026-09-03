package com.quzzar.villagelife.entities.ai.goals;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * A guard's loose watch: pick a building, walk a lap around it looking it over,
 * then move on to another, so a guard with nothing pressing is out making its
 * rounds rather than idling. Registered at the visiting priority and ahead of it
 * ({@link SeekConversationGoal}), so a guard on watch is not a chat magnet: it
 * patrols by default and only strikes up a conversation in the pauses between
 * rounds. Combat and panic outrank the watch, so a real threat pulls the guard
 * straight off it.
 *
 * <p>Deliberately loose (Aaron, 2026-09-02): not a fixed circuit and not a
 * quick-march. The guard traces a ring around a building's edge at a relaxed
 * pace, facing the building as it circles, rather than walking to its centre and
 * standing there (Aaron, 2026-09-03: the old watch parked on doorsteps). One
 * activation is one full lap; the unhurried pause falls between laps, before the
 * next building. A village always has buildings to round — a camp is founded
 * with three — so there is no campfire fallback. Guards do not sleep, so the
 * watch stands day and night; those pauses are where the night restock and the
 * odd tree still fit.
 */
public class GuardPatrolGoal extends Goal {

  /** A leisurely walk, below the guard's combat pace: this is a round, not a march. */
  private static final double SPEED = 0.45D;

  /** Close enough that a ring point counts as reached. */
  private static final double ARRIVED_SQR = 2.5D * 2.5D;

  /** How far outside a building's edge the guard traces its lap. */
  private static final double STANDOFF = 2.0D;

  /** A floor on the ring, for any building with a tiny radius. */
  private static final double MIN_RING = 3.0D;

  /** Points making up one lap: enough to read as circling, few enough to stay brisk. */
  private static final int LAP_POINTS = 6;

  /** The unhurried pause on the spot between laps, in ticks (about 3 to 8 seconds). */
  private static final int PAUSE_MIN = 60;
  private static final int PAUSE_MAX = 160;

  /** A ring point the guard cannot reach is skipped rather than ground against. */
  private static final int LEG_TIMEOUT_TICKS = 100;

  private final RealPerson guard;
  private final List<BlockPos> lap = new ArrayList<>();
  private BlockPos lookAt;
  private int legIndex;
  private int legTicks;
  private long resumeAt;

  public GuardPatrolGoal(RealPerson guard) {
    this.guard = guard;
    this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
  }

  @Override
  public boolean canUse() {
    if (guard.getVillage() == null || guard.level().getGameTime() < resumeAt) {
      return false;
    }
    planLap();
    return !lap.isEmpty();
  }

  @Override
  public boolean canContinueToUse() {
    return legIndex < lap.size();
  }

  @Override
  public void start() {
    legTicks = 0;
    walkTo(current());
  }

  @Override
  public void tick() {
    BlockPos target = current();
    if (target == null) {
      return;
    }
    legTicks++;
    // Face the building being circled, so the guard looks it over as it rounds
    // it rather than staring off along its own path.
    if (lookAt != null) {
      guard.getLookControl().setLookAt(lookAt.getX() + 0.5D, lookAt.getY() + 1.0D, lookAt.getZ() + 0.5D);
    }
    if (target.distSqr(guard.blockPosition()) <= ARRIVED_SQR || legTicks >= LEG_TIMEOUT_TICKS) {
      nextLeg();
    } else if (guard.getNavigation().isDone()) {
      // A path that finished short of a ring point (a doorway, a fence) is
      // nudged on once; the per-leg timeout skips one it truly cannot reach.
      walkTo(target);
    }
  }

  @Override
  public void stop() {
    // A pause on the spot before the next lap keeps the watch unhurried, and the
    // gap is where a chat, the night restock, or the odd tree take their turn.
    resumeAt = guard.level().getGameTime() + PAUSE_MIN + guard.getRandom().nextInt(PAUSE_MAX - PAUSE_MIN);
    lap.clear();
    legIndex = 0;
    guard.getNavigation().stop();
  }

  private BlockPos current() {
    return legIndex < lap.size() ? lap.get(legIndex) : null;
  }

  private void nextLeg() {
    legIndex++;
    legTicks = 0;
    BlockPos target = current();
    if (target != null) {
      walkTo(target);
    }
  }

  private void walkTo(BlockPos target) {
    if (target != null) {
      guard.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, SPEED);
    }
  }

  /**
   * Lay out one lap: a ring of waypoints around a random building's edge. Left
   * empty (which ends the goal) only in the moment before a founding village has
   * placed its buildings — a standing village always has some to round.
   */
  private void planLap() {
    lap.clear();
    legIndex = 0;
    lookAt = null;

    List<Building> buildings = new ArrayList<>(guard.getVillage().getBuildings());
    if (buildings.isEmpty()) {
      return;
    }
    Building building = buildings.get(guard.getRandom().nextInt(buildings.size()));
    lookAt = BlockPos.of(building.getCenterLocation());
    double ring = Math.max(MIN_RING, building.getRadius() + STANDOFF);

    // Trace the ring from a random angle and in a random direction, so the same
    // building is not walked the same way twice.
    double start = guard.getRandom().nextDouble() * Math.PI * 2.0D;
    int direction = guard.getRandom().nextBoolean() ? 1 : -1;
    double step = (Math.PI * 2.0D) / LAP_POINTS;
    for (int i = 0; i < LAP_POINTS; i++) {
      double angle = start + direction * i * step;
      int x = lookAt.getX() + (int) Math.round(Math.cos(angle) * ring);
      int z = lookAt.getZ() + (int) Math.round(Math.sin(angle) * ring);
      lap.add(new BlockPos(x, lookAt.getY(), z));
    }
  }
}
