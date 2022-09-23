package com.quzzar.villagelife.entities.ai.goals;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.item.CrossbowItem;

public class PersonMeleeGoal extends MeleeAttackGoal {
    public final RealPerson guard;

    public PersonMeleeGoal(RealPerson guard, double speedIn, boolean useLongMemory) {
        super(guard, speedIn, useLongMemory);
        this.guard = guard;
    }

    @Override
    public boolean canUse() {
        return !(this.guard.getMainHandItem().getItem() instanceof CrossbowItem) && this.guard.getTarget() != null
                && !this.guard.isEating() && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return super.canContinueToUse() && this.guard.getTarget() != null
                && !(this.guard.getMainHandItem().getItem() instanceof CrossbowItem);
    }

    @Override
    public void tick() {
        LivingEntity target = guard.getTarget();
        if (target != null) {
            if (target.distanceTo(guard) <= 3.0D && !guard.isBlocking()) {
                guard.getMoveControl().strafe(-2.0F, 0.0F);
                guard.lookAt(target, 30.0F, 30.0F);
            }
            if (this.mob.getNavigation().getPath() != null && target.distanceTo(guard) <= 2.0D)
                guard.getNavigation().stop();
            super.tick();
        }
    }

    @Override
    protected double getAttackReachSqr(LivingEntity attackTarget) {
        return super.getAttackReachSqr(attackTarget) * 3.55D;
    }

    @Override
    protected void checkAndPerformAttack(LivingEntity enemy, double distToEnemySqr) {
        double d0 = this.getAttackReachSqr(enemy);
        if (distToEnemySqr <= d0 && isTimeToAttack()) {
            this.resetAttackCooldown();
            this.guard.stopUsingItem();
            //if (guard.shieldCoolDown == 0)
                //this.guard.shieldCoolDown = 8;
            this.guard.swing(InteractionHand.MAIN_HAND);
            this.guard.doHurtTarget(enemy);
        }
    }
}
