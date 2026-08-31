package com.quzzar.villagelife.entities.ai.goals;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

public class UnstuckPersonGoal extends Goal {

    protected static final int MAX_DAYS = 3;

    protected RealPerson person;

    public UnstuckPersonGoal(RealPerson person){
        this.person = person;
    }

    @Override
    public boolean canUse() {
        // Read the bed location FRESH, never cached at construction: a villager is
        // given its bed after its goals are built, and an idle villager handed one
        // by reconcileBeds without a following reloadState keeps a stale ZERO
        // forever, so the daysSinceSleep recovery never fires for them. Same trap
        // SleepAtNightGoal hit (1d4523f).
        // A sleeping villager is deliberately immobile (Person.isImmobile), so its
        // idle navigation reads as "stuck". Never treat a sleeper as stuck, or the
        // teleport-home fires against a villager that is only resting.
        if (person.isSleeping()) {
            return false;
        }
        boolean hasBed = !LocationManager.getBedLocation(person).equals(BlockPos.ZERO);
        return (hasBed && this.person.getDaysSinceSleep() > MAX_DAYS) || person.getNavigation().isStuck();
    }

    @Override
    public void start() {
        person.tpToHome();
    }

}
