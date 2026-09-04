package com.quzzar.villagelife.entities.ai.goals;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.PanicGoal;

public class PanicToBedGoal extends PanicGoal {

    private final RealPerson person;

    public PanicToBedGoal(RealPerson person, double speedModifier) {
        super(person, speedModifier);
        this.person = person;
    }

    @Override
    public boolean canUse() {
        // A villager who picked a fight does not flee the first blow of it; the
        // quarrel runs its minute. Anything else that hurts them still sends
        // them to bed.
        if (person.isQuarrelling()) {
            return false;
        }
        return super.canUse();
    }

    @Override
    protected boolean findRandomPosition() {
        // Read the bed location FRESH each panic, never cached at construction: a
        // villager handed a bed by reconcileBeds after its goals were built keeps a
        // stale ZERO otherwise and flees nowhere. Same trap SleepAtNightGoal hit
        // (1d4523f).
        BlockPos bedLoc = LocationManager.getNightRestLocation(person);
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
