package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.entity.ai.goal.Goal;

/**
 * The following half of a married couple on the road. When a wanderer leaves a
 * village with their spouse (docs/marriage.md, the family unit), one of the two
 * is the lead and walks the day's heading ({@link RoamGoal}); this keeps the
 * OTHER at the lead's heel so the pair never drifts apart. It shadows the lead a
 * touch faster than the lead walks, yields once within arm's reach so the
 * follower can forage or camp beside their partner, and falls silent the moment
 * the spouse is gone from the loaded world, handing the road back to RoamGoal so
 * a widowed or separated wanderer walks on alone
 * ({@link RealPerson#followsSpouseOnTheRoad}).
 *
 * <p>Sits at the walk's own priority, below fleeing, so a monster still scatters
 * a following spouse; it and RoamGoal never both apply to one villager.
 */
public class FollowSpouseGoal extends Goal {

    /** A touch faster than the lead's roam pace, so the follower closes the gap. */
    private static final double SPEED = 0.66D;

    /** Fall in this close and yield; drift past the far mark and start closing again. */
    private static final double KEEP_WITHIN = 3.0D;
    private static final double FALL_BEHIND = 5.0D;

    /** Goal ticks between re-issuing the path to a moving spouse. */
    private static final int REPATH_EVERY = 10;

    private final RealPerson person;
    private RealPerson spouse;
    private int ticks;

    public FollowSpouseGoal(RealPerson person) {
        this.person = person;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!person.followsSpouseOnTheRoad()) {
            return false;
        }
        RealPerson lead = person.loadedRoamingSpouse();
        // Only close the gap once they have fallen behind; while at heel this
        // yields, so the follower forages and camps beside their partner.
        if (lead == null || person.distanceToSqr(lead) < FALL_BEHIND * FALL_BEHIND) {
            return false;
        }
        this.spouse = lead;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return person.followsSpouseOnTheRoad() && spouse != null && spouse.isAlive()
                && person.distanceToSqr(spouse) > KEEP_WITHIN * KEEP_WITHIN;
    }

    @Override
    public void start() {
        ticks = 0;
        person.getNavigation().moveTo(spouse, SPEED);
    }

    @Override
    public void stop() {
        this.spouse = null;
        person.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (spouse == null) {
            return;
        }
        person.getLookControl().setLookAt(spouse, 30.0F, 30.0F);
        if (++ticks % REPATH_EVERY == 0 || person.getNavigation().isDone()) {
            person.getNavigation().moveTo(spouse, SPEED);
        }
    }
}
