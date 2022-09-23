package com.quzzar.villagelife.entities.ai.goals;

import java.util.List;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.InteractionHand;

public class PersonEatFoodGoal extends Goal {
    public final RealPerson guard;

    public PersonEatFoodGoal(RealPerson guard) {
        this.guard = guard;
    }

    @Override
    public boolean canUse() {
        return (!guard.isRunningToEat()
                    && guard.getHealth() < guard.getMaxHealth() / 3
                    && PersonEatFoodGoal.isConsumable(guard.getOffhandItem())
                    && guard.isEating())
                || (guard.getHealth() < guard.getMaxHealth() / 3
                    && PersonEatFoodGoal.isConsumable(guard.getOffhandItem())
                    && guard.getTarget() == null
                    && !guard.isAggressive());
    }

    public static boolean isConsumable(ItemStack stack) {
        return (stack.getUseAnimation() == UseAnim.EAT
                && stack.getCount() > 0)
            || (stack.getUseAnimation() == UseAnim.DRINK
                && !(stack.getItem() instanceof SplashPotionItem)
                && stack.getCount() > 0);
    }
    
    @Override
    public boolean canContinueToUse() {
        List<LivingEntity> list = this.guard.level.getEntitiesOfClass(LivingEntity.class, this.guard.getBoundingBox().inflate(5.0D, 3.0D, 5.0D));
        if (!list.isEmpty()) {
            for (LivingEntity mob : list) {
                if (mob != null) {
                    if (mob instanceof Mob && ((Mob) mob).getTarget() instanceof RealPerson) {
                        return false;
                    }
                }
            }
        }
        return guard.getHealth() < (guard.getMaxHealth() / 3) + 2 && guard.isEating();
    }

    @Override
    public void start() {
        if (guard.getTarget() == null)
            guard.setEating(true);
        guard.startUsingItem(InteractionHand.OFF_HAND);
    }

    @Override
    public void stop() {
        guard.setEating(false);
        guard.stopUsingItem();
    }
}
