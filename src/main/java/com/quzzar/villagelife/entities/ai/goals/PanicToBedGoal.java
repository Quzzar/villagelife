package com.quzzar.villagelife.entities.ai.goals;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.PanicGoal;

public class PanicToBedGoal extends PanicGoal {

    private BlockPos bedLoc;
    public PanicToBedGoal(RealPerson person, double speedModifier) {
        super(person, speedModifier);
        this.bedLoc = LocationManager.getBedLocation(person);
    }

    @Override
    protected boolean findRandomPosition() {
        if(!bedLoc.equals(BlockPos.ZERO)){
            this.posX = bedLoc.getX();
            this.posY = bedLoc.getY();
            this.posZ = bedLoc.getZ();
            return true;
        } else {
            return super.findRandomPosition();
        }
    }
}
