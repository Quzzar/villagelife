package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Walks the person toward a village-directed travel target: an arriving
 * newcomer heading for the campfire, or an emigrant heading for the village
 * edge. The village sets and clears the target (and confirms completion) from
 * its own tick; this goal only provides the legs.
 */
public class VillageTravelGoal extends Goal {

    private static final double SPEED = 0.6D;

    private final RealPerson person;

    public VillageTravelGoal(RealPerson person) {
        this.person = person;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return person.getTravelTarget() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return person.getTravelTarget() != null;
    }

    @Override
    public void tick() {
        BlockPos target = person.getTravelTarget();
        if (target == null) {
            return;
        }
        if (person.getNavigation().isDone() || person.tickCount % 40 == 0) {
            person.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), SPEED);
        }
    }

    @Override
    public void stop() {
        person.getNavigation().stop();
    }
}
