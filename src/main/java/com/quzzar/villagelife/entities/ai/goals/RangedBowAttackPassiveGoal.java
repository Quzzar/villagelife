package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;

import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BowItem;

public class RangedBowAttackPassiveGoal<T extends RealPerson & RangedAttackMob> extends Goal {
    private final T entity;
    private final double moveSpeedAmp;
    private int attackCooldown;
    private final float maxAttackDistance;
    private int attackTime = -1;
    private int seeTime;
    private boolean strafingClockwise;
    private boolean strafingBackwards;
    private int strafingTime = -1;

    public RangedBowAttackPassiveGoal(T mob, double moveSpeedAmpIn, int attackCooldownIn, float maxAttackDistanceIn) {
        this.entity = mob;
        this.moveSpeedAmp = moveSpeedAmpIn;
        this.attackCooldown = attackCooldownIn;
        this.maxAttackDistance = maxAttackDistanceIn * maxAttackDistanceIn;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    public void setAttackCooldown(int attackCooldownIn) {
        this.attackCooldown = attackCooldownIn;
    }

    @Override
    public boolean canUse() {
        return this.entity.getTarget() != null && this.isBowInMainhand() && !this.entity.isEating() && !this.entity.isBlocking();
    }

    protected boolean isBowInMainhand() {
        return this.entity.getMainHandItem().getItem() instanceof BowItem;
    }

    @Override
    public boolean canContinueToUse() {
        return (this.canUse() || !this.entity.getNavigation().isDone()) && this.isBowInMainhand();
    }

    @Override
    public void start() {
        super.start();
        this.entity.setAggressive(true);
    }

    @Override
    public void stop() {
        super.stop();
        this.entity.setAggressive(false);
        this.seeTime = 0;
        this.attackTime = -1;
        this.entity.stopUsingItem();
    }

    @Override
    public void tick() {
        LivingEntity livingentity = this.entity.getTarget();
        if (livingentity != null) {
            double d0 = this.entity.distanceToSqr(livingentity.getX(), livingentity.getY(), livingentity.getZ());
            boolean flag = this.entity.getSensing().hasLineOfSight(livingentity);
            boolean flag1 = this.seeTime > 0;
            if (flag != flag1) {
                this.seeTime = 0;
            }

            if (flag) {
                ++this.seeTime;
            } else {
                --this.seeTime;
            }

            if (!(d0 > (double) this.maxAttackDistance) && this.seeTime >= 20) {
                this.entity.getNavigation().stop();
                ++this.strafingTime;
            } else {
                this.entity.getNavigation().moveTo(livingentity, this.moveSpeedAmp);
                this.strafingTime = -1;
            }

            if (this.strafingTime >= 20) {
                if ((double) this.entity.getRandom().nextFloat() < 0.3D) {
                    this.strafingClockwise = !this.strafingClockwise;
                }

                if ((double) this.entity.getRandom().nextFloat() < 0.3D) {
                    this.strafingBackwards = !this.strafingBackwards;
                }

                this.strafingTime = 0;
            }

            if (this.strafingTime > -1) {
                if (d0 > (double) (this.maxAttackDistance * 0.75F)) {
                    this.strafingBackwards = false;
                } else if (d0 < (double) (this.maxAttackDistance * 0.25F)) {
                    this.strafingBackwards = true;
                }
                if (entity.getPatrolPos() == null)
                    this.entity.getMoveControl().strafe(this.strafingBackwards ? -0.5F : 0.5F, this.strafingClockwise ? 0.5F : -0.5F);
                this.entity.lookAt(livingentity, 30.0F, 30.0F);
            }
            this.entity.lookAt(livingentity, 30.0F, 30.0F);
            this.entity.getLookControl().setLookAt(livingentity, 30.0F, 30.0F);

            if (this.entity.isUsingItem() && !this.entity.isBlocking()) {
                if (!flag && this.seeTime < -60) {
                    this.entity.stopUsingItem();
                } else if (flag) {
                    int i = this.entity.getTicksUsingItem();
                    if (i >= 20) {
                        this.entity.stopUsingItem();
                        this.entity.performRangedAttack(livingentity, BowItem.getPowerForTime(i));
                        this.attackTime = this.attackCooldown;
                    }
                }
            } else if (--this.attackTime <= 0 && this.seeTime >= -60 && !this.entity.isBlocking()) {
                this.entity.startUsingItem(Utils.getHandWith(entity, item -> item instanceof BowItem));
            }

        }
    }
}