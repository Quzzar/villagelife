package com.quzzar.villagelife.entities.ai.goals;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

public class UnstuckPersonGoal extends Goal {

    protected static final int MAX_DAYS = 3;

    private BlockPos bedLoc;
    protected RealPerson person;

    public UnstuckPersonGoal(RealPerson person){
        this.person = person;
        this.bedLoc = LocationManager.getBedLocation(person);
    }

    @Override
    public boolean canUse() {
        return (this.bedLoc != BlockPos.ZERO && this.person.getDaysSinceSleep() > MAX_DAYS) || person.getNavigation().isStuck();
    }

    @Override
    public void start() {
        person.tpToHome();
    }

}
