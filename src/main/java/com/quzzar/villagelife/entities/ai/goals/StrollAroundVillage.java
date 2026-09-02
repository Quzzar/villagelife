package com.quzzar.villagelife.entities.ai.goals;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

/**
 * Ambient wandering. Employed people drift between their job, bed, and the
 * village center; IDLE people loiter at the campfire, Stronghold-style — they
 * hang around the gathering point waiting for a job, and get pulled back when
 * they drift too far, instead of wandering off across the world.
 *
 * Locations are resolved per-stroll, not cached: this goal is constructed at
 * entity creation, before the person has a village, bed, or job, so anything
 * captured then is permanently stale (the old cached version was why villagers
 * ran off — every lookup fell through to "stroll anywhere", compounding).
 */
public class StrollAroundVillage extends RandomStrollGoal {

    /** Idle people beyond this distance from the campfire walk back to it. */
    private static final double CAMPFIRE_TETHER = 16.0D;

    private final RealPerson person;

    public StrollAroundVillage(RealPerson person, double speedModifier) {
        // RandomStrollGoal already declares the movement flag for us.
        super(person, speedModifier, 240, false);
        this.person = person;
    }

    /** A wanderer on the road walks it ({@link RoamGoal}); strolling is for people with somewhere to be. */
    @Override
    public boolean canUse() {
        return !person.isRoaming() && super.canUse();
    }

    @Override
    protected Vec3 getPosition() {
        if (person.getOccupation().isIdle()) {
            Vec3 loiter = getPositionNearCampfire();
            if (loiter != null) {
                return loiter;
            }
        }

        float f = this.mob.level().random.nextFloat();
        if (this.mob.level().random.nextFloat() < 0.3F) {
            return this.getPositionTowardsAnywhere();
        } else {
            Vec3 vec3;
            if (f < 0.7F) {
                vec3 = this.getPositionTowardsPoi();
                if (vec3 == null) {
                    vec3 = this.getPositionTowardsCenter();
                }
            } else {
                vec3 = this.getPositionTowardsCenter();
                if (vec3 == null) {
                    vec3 = this.getPositionTowardsPoi();
                }
            }

            return vec3 == null ? this.getPositionTowardsAnywhere() : vec3;
        }
    }

    /** Loiter near the gathering point; walk back first when too far away. */
    @Nullable
    private Vec3 getPositionNearCampfire() {
        Village village = person.getVillage();
        if (village == null) {
            return null;
        }
        BlockPos campfire = village.getGatheringPoint();
        if (campfire == null || campfire.equals(BlockPos.ZERO)) {
            return null;
        }
        Vec3 fireCenter = Vec3.atBottomCenterOf(campfire);
        if (!campfire.closerToCenterThan(this.mob.position(), CAMPFIRE_TETHER)) {
            Vec3 back = LandRandomPos.getPosTowards(this.mob, 10, 7, fireCenter);
            return back != null ? back : fireCenter;
        }
        // Near the fire: small shuffles around it, never standing in the flames.
        return LandRandomPos.getPosTowards(this.mob, 5, 3, fireCenter);
    }

    @Nullable
    private Vec3 getPositionTowardsAnywhere() {
        return LandRandomPos.getPos(this.mob, 10, 7);
    }

    @Nullable
    private Vec3 getPositionTowardsPoi() {
        BlockPos location;
        if (this.mob.level().isNight() || this.mob.level().isThundering()) {
            location = LocationManager.getBedLocation(person);
        } else {
            location = LocationManager.getJobLocation(person);
        }

        if (location.equals(BlockPos.ZERO)) {
            return null;
        }
        return LandRandomPos.getPosTowards(this.mob, 10, 7, Vec3.atBottomCenterOf(location));
    }

    @Nullable
    private Vec3 getPositionTowardsCenter() {
        BlockPos location = LocationManager.getVillageCenter(person);
        if (location.equals(BlockPos.ZERO)) {
            return null;
        } else {
            return LandRandomPos.getPosTowards(this.mob, 10, 7, Vec3.atBottomCenterOf(location));
        }
    }

}
