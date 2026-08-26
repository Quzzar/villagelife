package com.quzzar.villagelife.entities.ai.goals;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

public class ReturnBackToVillageGoal extends Goal {

    protected static final double MAX_DISTANCE = 100;

    protected RealPerson person;
    protected BlockPos location;

    public ReturnBackToVillageGoal(RealPerson person){
        this.person = person;
        this.location = LocationManager.getJobLocation(person);
    }

    @Override
    public boolean canUse() {
        return this.location != BlockPos.ZERO && isTooFarAway();
    }

    @Override
    public boolean canContinueToUse() {
        return isTooFarAway();
    }

    @Override
    public void tick() {
        if(!person.getNavigation().isInProgress()){
            person.getNavigation().moveTo(location.getX(), location.getY(), location.getZ(), 0.5D);
        }
    }

    protected boolean isTooFarAway(){
        return location.distSqr(person.blockPosition()) > MAX_DISTANCE;
    }

}
