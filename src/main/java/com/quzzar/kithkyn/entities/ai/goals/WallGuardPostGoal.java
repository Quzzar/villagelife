package com.quzzar.kithkyn.entities.ai.goals;

import java.util.EnumSet;

import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.village.buildings.WallPost;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/** Keeps a wall defender at their exact gate or tower station between fights. */
public final class WallGuardPostGoal extends Goal {

  private static final double SPEED = 0.6D;
  private static final double ARRIVED_DISTANCE_SQR = 1.5D * 1.5D;

  private final RealPerson guard;

  public WallGuardPostGoal(RealPerson guard) {
    this.guard = guard;
    this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
  }

  @Override
  public boolean canUse() {
    return guard.getWallPost() != null;
  }

  @Override
  public boolean canContinueToUse() {
    return guard.getWallPost() != null;
  }

  @Override
  public void tick() {
    WallPost post = guard.getWallPost();
    if (post == null) {
      return;
    }
    BlockPos station = post.position();
    guard.getLookControl().setLookAt(
        post.lookAt().getX() + 0.5D,
        post.lookAt().getY() + 0.5D,
        post.lookAt().getZ() + 0.5D);
    if (guard.distanceToSqr(station.getX() + 0.5D, station.getY(), station.getZ() + 0.5D)
        <= ARRIVED_DISTANCE_SQR) {
      guard.getNavigation().stop();
      return;
    }
    if (guard.getNavigation().isDone() || guard.tickCount % 40 == 0) {
      guard.getNavigation().moveTo(
          station.getX() + 0.5D, station.getY(), station.getZ() + 0.5D, SPEED);
    }
  }

  @Override
  public void stop() {
    guard.getNavigation().stop();
  }
}
