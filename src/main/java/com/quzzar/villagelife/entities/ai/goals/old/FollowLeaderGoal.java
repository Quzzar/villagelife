package com.quzzar.villagelife.entities.ai.goals.old;

import java.util.EnumSet;
import java.util.List;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

public class FollowLeaderGoal extends Goal {
    public final RealPerson guard;

    public FollowLeaderGoal(RealPerson mob) {
        guard = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public void start() {
        super.start();
        if (guard.getFollowLeader() != null) {
            guard.getNavigation().moveTo(guard.getFollowLeader(), 0.5D);
        }
    }

    @Override
    public void tick() {
        if (guard.getFollowLeader() != null) {
            guard.getNavigation().moveTo(guard.getFollowLeader(), 0.5D);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return guard.isFollowing() && this.canUse();
    }

    @Override
    public boolean canUse() {
        List<Player> list = this.guard.level.getEntitiesOfClass(Player.class,
                this.guard.getBoundingBox().inflate(10.0D));
        if (!list.isEmpty()) {
            for (LivingEntity mob : list) {
                Player player = (Player) mob;
                if (!player.isInvisible() /* && and something else ? */) {
                    guard.setFollowLeaderUUID(player.getUUID());
                    return guard.isFollowing();
                }
            }
        }
        return false;
    }

    @Override
    public void stop() {
        this.guard.getNavigation().stop();
        /*
        if (guard.getFollowLeader() != null ) {
            guard.setFollowLeaderUUID(null);
            guard.setFollowing(false);
        }
        */
    }
}
