package com.quzzar.kithkyn.entities.ai.goals;

import java.util.EnumSet;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

public class ReturnBackToVillageGoal extends Goal {

    protected static final double MAX_DISTANCE = 100;

    protected RealPerson person;
    protected BlockPos location;

    public ReturnBackToVillageGoal(RealPerson person){
        // This goal walks the villager somewhere, so it must compete for movement
        // rather than run alongside every other goal that does the same (#74).
        this.setFlags(EnumSet.of(Flag.MOVE));
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
