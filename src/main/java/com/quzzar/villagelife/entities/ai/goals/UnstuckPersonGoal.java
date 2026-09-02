package com.quzzar.villagelife.entities.ai.goals;

import com.quzzar.villagelife.entities.RealPerson;

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
 * below cannot fire against a sleeper. A job that never sleeps by design
 * (Occupation.sleepsAtNight() false) zeroes it nightly in
 * NightWatchRestockGoal instead, so a bedded guard on watch does not read as
 * three days wedged.
 *
 * <p>Nights are counted in RealPerson.aiStep at daybreak for every villager,
 * whatever goal held them, and after three unslept nights the villager is set
 * down at their bed, or beside the campfire when they have none
 * (RealPerson.tpToRest). Before this the count lived in the sleep goal and the
 * recovery required a bed, so a bedless villager, or one a stronger goal kept
 * from the sleep goal all night, was never counted and never recovered.
 */
public class UnstuckPersonGoal extends Goal {

    protected static final int MAX_DAYS = 3;

    protected RealPerson person;

    public UnstuckPersonGoal(RealPerson person){
        this.person = person;
    }

    @Override
    public boolean canUse() {
        // No bed gate any more: a villager with no bed is the one most likely to
        // be somewhere wrong, and they are set down at the campfire instead. A
        // villager with no village is on the road, which is where they belong.
        return this.person.getVillage() != null && this.person.getDaysSinceSleep() > MAX_DAYS;
    }

    @Override
    public void start() {
        int nights = person.getDaysSinceSleep();
        person.tpToRest("had not slept in " + nights + " nights");
        // The count starts over, so a villager who still cannot rest where they
        // were set down is brought back again after another three nights rather
        // than never.
        person.setDaysSinceSleep(0);
    }

}
