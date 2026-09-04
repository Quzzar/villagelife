package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.entity.ai.goal.Goal;

/** Keeps a spouse or dependent child at the roaming household leader's heel. */
public class FollowFamilyGoal extends Goal {

  private static final double SPEED = 0.66D;
  private static final double KEEP_WITHIN = 3.0D;
  private static final double FALL_BEHIND = 5.0D;
  private static final int REPATH_EVERY = 10;

  private final RealPerson person;
  private RealPerson leader;
  private int ticks;

  public FollowFamilyGoal(RealPerson person) {
    this.person = person;
    this.setFlags(EnumSet.of(Goal.Flag.MOVE));
  }

  @Override
  public boolean canUse() {
    if (!person.followsFamilyOnTheRoad()) {
      return false;
    }
    RealPerson loadedLeader = person.loadedRoamingFamilyLeader();
    if (loadedLeader == null || person.distanceToSqr(loadedLeader) < FALL_BEHIND * FALL_BEHIND) {
      return false;
    }
    this.leader = loadedLeader;
    return true;
  }

  @Override
  public boolean canContinueToUse() {
    return person.followsFamilyOnTheRoad() && leader != null && leader.isAlive()
        && person.distanceToSqr(leader) > KEEP_WITHIN * KEEP_WITHIN;
  }

  @Override
  public void start() {
    ticks = 0;
    person.getNavigation().moveTo(leader, SPEED);
  }

  @Override
  public void stop() {
    this.leader = null;
    person.getNavigation().stop();
  }

  @Override
  public void tick() {
    if (leader == null) {
      return;
    }
    person.getLookControl().setLookAt(leader, 30.0F, 30.0F);
    if (++ticks % REPATH_EVERY == 0 || person.getNavigation().isDone()) {
      person.getNavigation().moveTo(leader, SPEED);
    }
  }
}
