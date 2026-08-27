package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
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
        // This goal walks the villager somewhere, so it must compete for movement
        // rather than run alongside every other goal that does the same (#74).
        this.setFlags(EnumSet.of(Flag.MOVE));
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
            
            if(tickCount % 10 == 0) { // Every half second, as intended
        
                if (!person.swinging) {
                    person.swing(person.getUsedItemHand());
                }

                var project = person.getVillage().getCurrentProject();
                if (project.getProgress() == com.quzzar.villagelife.village.buildings.BuildProgress.PREPARING) {
                    // Clearing and levelling the ground is the builder's first
                    // phase, not a separate job (docs/site-selection.md).
                    if (!project.prepareStep(person.getVillage(), person)) {
                        person.getVillage().logEvent(
                                new com.quzzar.villagelife.village.bookkeeping.NoResourceBookkeepingEvent(
                                        net.minecraft.world.item.Items.DIRT, 1));
                        person.logIssue("We have no earth to level the ground for the new building.",
                                java.util.Optional.empty());
                    }
                    if (project.remainingPrepWork() == 0) {
                        project.startBuilding();
                    }
                } else {
                    project.updateBuilding();
                }
                
            }

        } else {
            person.getNavigation().moveTo(buildingPos.getX(), buildingPos.getY(), buildingPos.getZ(), 0.5D);
        }

    }

    protected boolean shouldInterrupt(){
        return this.person.getLastHurtByMob() != null
                || this.person.isFreezing()
                || this.person.isOnFire()
                || this.person.level().isNight()
                || this.person.isInterrupted();
    }

}
