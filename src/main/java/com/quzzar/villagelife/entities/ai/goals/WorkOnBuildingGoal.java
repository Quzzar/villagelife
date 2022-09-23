package com.quzzar.villagelife.entities.ai.goals;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.buildings.BuildProgress;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

public class WorkOnBuildingGoal extends Goal {
    
    protected final double PERCENT_INCREASE = 1.1;

    private int tickCount = 0;

    protected RealPerson person;
    protected BlockPos buildingPos;

    public WorkOnBuildingGoal(RealPerson person) {
        this.person = person;
        this.buildingPos = null;
    }

    @Override
    public boolean canUse() {
        if(person.getVillage() == null) { return false; }
        if(person.getVillage().getCurrentProject() == null) { return false; }
        if(shouldInterrupt()) { return false; }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return person.getVillage().getCurrentProject() != null && person.getVillage().getCurrentProject().getProgress() != BuildProgress.COMPLETE && !shouldInterrupt();
    }

    @Override
    public void start() {
        this.buildingPos = BlockPos.of(person.getVillage().getCurrentProject().getBuilding().getCenterLocation());
        person.getVillage().getCurrentProject().startBuilding();
    }

    @Override
    public void stop() {
        if(person.getVillage().getCurrentProject() != null){
            person.getVillage().getCurrentProject().stopBuilding();
        }
        this.buildingPos = null;
    }

    @Override
    public void tick() {
        tickCount++;

        if(buildingPos.distSqr(person.blockPosition()) <= Math.pow(person.getVillage().getCurrentProject().getBuilding().getRadius(), 2)*PERCENT_INCREASE){
            
            if(tickCount % 1 == 0) {// Every 1/2 second, TODO, switch back to % 10
        
                if (!person.swinging) {
                    person.swing(person.getUsedItemHand());
                }

                person.getVillage().getCurrentProject().updateBuilding();
                
            }

        } else {
            person.getNavigation().moveTo(buildingPos.getX(), buildingPos.getY(), buildingPos.getZ(), 0.5D);
        }

    }

    protected boolean shouldInterrupt(){
        return this.person.getLastHurtByMob() != null
                || this.person.isFreezing()
                || this.person.isOnFire()
                || this.person.getLevel().isNight()
                || this.person.isInterrupted();
    }

}
