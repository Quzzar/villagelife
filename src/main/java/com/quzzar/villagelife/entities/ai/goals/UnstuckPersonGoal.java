package com.quzzar.villagelife.entities.ai.goals;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Last-resort recovery: bring home a villager whose situation has genuinely
 * gone wrong, not one who merely looks wedged.
 *
 * This used to also fire on {@code getNavigation().isStuck()}, but a stalled
 * path is not distress. The hunter inside the hunter's lodge, head clipping the
 * trapdoor, stalls every path and was teleported to the campfire despite being
 * exactly where they belong: overlapping a block that deals no damage is
 * harmless and needs no rescue. The villagers who ARE in trouble are already
 * recovered elsewhere. Actual in-block suffocation (plus lava, drowning, the
 * void) teleports home on the damage event in CoreEvents.onLivingDamaged, and a
 * worker who repeatedly cannot walk to their work is brought home by
 * ApproachWatch. That is also why no isSleeping guard remains here: it existed
 * because a sleeper's leftover path read as "stuck", and the one sleep path
 * (SleepAtNightGoal) zeroes daysSinceSleep before lying down, so the branch
 * below cannot fire against a sleeper.
 */
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
        boolean hasBed = !LocationManager.getBedLocation(person).equals(BlockPos.ZERO);
        return hasBed && this.person.getDaysSinceSleep() > MAX_DAYS;
    }

    @Override
    public void start() {
        person.tpToHome();
    }

}
