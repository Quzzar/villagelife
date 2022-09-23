package com.quzzar.villagelife.entities.ai.goals.old;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.npc.AbstractVillager;

public class FollowLeaderHurtTargetGoal extends TargetGoal {
    private final RealPerson guard;
    private LivingEntity attacker;
    private int timestamp;

    public FollowLeaderHurtTargetGoal(RealPerson theEntityTameableIn) {
        super(theEntityTameableIn, false);
        this.guard = theEntityTameableIn;
        this.setFlags(EnumSet.of(Goal.Flag.TARGET));
    }

    public boolean canUse() {
        LivingEntity livingentity = this.guard.getFollowLeader();
        if (livingentity == null) {
            return false;
        } else {
            this.attacker = livingentity.getLastHurtMob();
            int i = livingentity.getLastHurtMobTimestamp();
            return i != this.timestamp && this.canAttack(this.attacker, TargetingConditions.DEFAULT);
        }
    }

    @Override
    protected boolean canAttack(@Nullable LivingEntity potentialTarget, TargetingConditions targetPredicate) {
        return super.canAttack(potentialTarget, targetPredicate) && !(potentialTarget instanceof AbstractVillager) && !(potentialTarget instanceof RealPerson);
    }

    @Override
    public void start() {
        this.mob.setTarget(this.attacker);
        LivingEntity livingentity = this.guard.getFollowLeader();
        if (livingentity != null) {
            this.timestamp = livingentity.getLastHurtMobTimestamp();
        }
        super.start();
    }
}
