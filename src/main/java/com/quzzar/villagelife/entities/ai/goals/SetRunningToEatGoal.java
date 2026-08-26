package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.entity.ai.goal.Goal;

public class SetRunningToEatGoal extends Goal {
    protected final RealPerson guard;

    public SetRunningToEatGoal(RealPerson guard, double speedIn) {
        super();
        this.guard = guard;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return !guard.isRunningToEat() && guard.getHealth() < guard.getMaxHealth() / 3 && PersonEatFoodGoal.isConsumable(guard.getOffhandItem()) && !guard.isEating() && guard.getTarget() != null;
    }

    @Override
    public void start() {
        this.guard.setTarget(null);
        if (!guard.isRunningToEat())
            this.guard.setRunningToEat(true);

    }
}
