package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.buildings.BuildProgress;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

public class WorkOnBuildingGoal extends Goal {
    
    protected final double PERCENT_INCREASE = 1.1;

    /** Ticks spent walking without ever getting closer before giving up on the site. */
    private static final int STALL_TICKS = 200;

    /** How long a builder who could not reach the site leaves it alone. */
    private static final int STAND_DOWN_TICKS = 600;

    /** Give-ups in a row before the builder is stranded rather than unlucky. */
    private static final int STRANDED_AFTER = 3;

    private int tickCount = 0;

    /** The closest the builder has come to the site on this attempt. */
    private double closestApproachSqr = Double.MAX_VALUE;
    private int ticksWithoutProgress = 0;
    private int standDownUntil = 0;
    private int consecutiveGiveUps = 0;

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
        if(shouldInterrupt() || standingDown()) { return false; }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return person.getVillage().getCurrentProject() != null && person.getVillage().getCurrentProject().getProgress() != BuildProgress.COMPLETE && !shouldInterrupt() && !standingDown();
    }

    /**
     * True while the builder has given up on a site they could not walk to.
     * Holding the movement flag forever would leave them standing where they
     * stopped: a builder who cannot reach the work has to let go of it so
     * everything else they could be doing gets a turn.
     */
    private boolean standingDown() {
        return person.tickCount < standDownUntil;
    }

    @Override
    public void start() {
        this.buildingPos = BlockPos.of(person.getVillage().getCurrentProject().getBuilding().getCenterLocation());
        this.closestApproachSqr = Double.MAX_VALUE;
        this.ticksWithoutProgress = 0;
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

            // Arriving clears the record of failed attempts.
            consecutiveGiveUps = 0;

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
            trackApproach();
        }

    }

    /**
     * Watches whether walking is actually getting the builder anywhere. A
     * villager in a hole they cannot climb, or on the wrong side of water, is
     * never reported as stuck by the navigator, because a path that was never
     * found cannot stall: they simply stand still with a job they will never
     * start.
     */
    private void trackApproach() {
        double distanceSqr = buildingPos.distSqr(person.blockPosition());
        if (distanceSqr < closestApproachSqr - 0.5D) {
            closestApproachSqr = distanceSqr;
            ticksWithoutProgress = 0;
            return;
        }
        if (++ticksWithoutProgress < STALL_TICKS) {
            return;
        }
        person.logIssue("I cannot get to where the new building is going.", java.util.Optional.empty());
        Villagelife.LOGGER.debug("{} cannot reach the build site at {} and is standing down",
                person.getFullName(), buildingPos.toShortString());
        standDownUntil = person.tickCount + STAND_DOWN_TICKS;
        ticksWithoutProgress = 0;

        if (++consecutiveGiveUps >= STRANDED_AFTER) {
            // Three in a row is not bad luck: this builder is somewhere they
            // cannot walk out of, usually the bottom of the village's own mine.
            // Recover them the same way a lost villager is already recovered,
            // rather than leaving a village with a job nobody can do.
            Villagelife.LOGGER.info("{} was stranded and has been brought back to the village center",
                    person.getFullName());
            person.tpToHome();
            consecutiveGiveUps = 0;
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
