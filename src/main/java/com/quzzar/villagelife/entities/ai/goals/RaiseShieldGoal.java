package com.quzzar.villagelife.entities.ai.goals;

import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.InteractionHand;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

public class RaiseShieldGoal extends Goal {

    public final RealPerson guard;

    public RaiseShieldGoal(RealPerson guard) {
        this.guard = guard;
    }

    @Override
    public boolean canUse() {
        return !CrossbowItem.isCharged(guard.getMainHandItem()) && (guard.getOffhandItem().getItem().canPerformAction(guard.getOffhandItem(), net.minecraftforge.common.ToolActions.SHIELD_BLOCK) && raiseShield() && guard.shieldCoolDown == 0
                && !guard.getOffhandItem().getItem().equals(ForgeRegistries.ITEMS.getValue(new ResourceLocation("bigbrain:buckler"))));
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public void start() {
        if (guard.getOffhandItem().getItem().canPerformAction(guard.getOffhandItem(), net.minecraftforge.common.ToolActions.SHIELD_BLOCK))
            guard.startUsingItem(InteractionHand.OFF_HAND);
    }

    @Override
    public void stop() {
        guard.stopUsingItem();
    }

    protected boolean raiseShield() {
        LivingEntity target = guard.getTarget();
        if (target != null && guard.shieldCoolDown == 0) {
            boolean ranged = guard.getMainHandItem().getItem() instanceof CrossbowItem || guard.getMainHandItem().getItem() instanceof BowItem;
            if (guard.distanceTo(target) <= 4.0D || target instanceof Creeper || target instanceof RangedAttackMob && target.distanceTo(guard) >= 5.0D && !ranged || target instanceof Ravager) {
                return true;
            } else {
                return false;
            }
        }
        return false;
    }
}
