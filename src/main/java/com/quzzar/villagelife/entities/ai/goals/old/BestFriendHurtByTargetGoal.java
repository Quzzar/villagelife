package com.quzzar.villagelife.entities.ai.goals.old;

/*
import java.util.EnumSet;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.animal.IronGolem;

public class BestFriendHurtByTargetGoal extends TargetGoal {
    private final RealPerson person;
    private LivingEntity attacker;
    private int timestamp;

    public BestFriendHurtByTargetGoal(RealPerson person) {
        super(person, false);
        this.person = person;
        this.setFlags(EnumSet.of(Goal.Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        LivingEntity livingentity = .getFollowLeader();
        // this.person.getRelationships()
        if (livingentity == null) {
            return false;
        } else {
            this.attacker = livingentity.getLastHurtByMob();
            int i = livingentity.getLastHurtByMobTimestamp();
            return i != this.timestamp && this.canAttack(this.attacker, TargetingConditions.DEFAULT);
        }
    }

    @Override
    protected boolean canAttack(@Nullable LivingEntity potentialTarget, TargetingConditions targetPredicate) {
        return super.canAttack(potentialTarget, targetPredicate) && !(potentialTarget instanceof IronGolem) && !(potentialTarget instanceof RealPerson);
    }

    @Override
    public void start() {
        this.mob.setTarget(this.attacker);
        LivingEntity livingentity = this.person.getFollowLeader();
        if (livingentity != null) {
            this.timestamp = livingentity.getLastHurtByMobTimestamp();
        }

        super.start();
    }
}

*/
