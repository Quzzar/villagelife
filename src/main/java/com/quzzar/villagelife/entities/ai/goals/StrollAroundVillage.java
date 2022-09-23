package com.quzzar.villagelife.entities.ai.goals;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

public class StrollAroundVillage extends RandomStrollGoal {

    private BlockPos bedLoc;
    private BlockPos jobLoc;
    private BlockPos centerLoc;

    public StrollAroundVillage(RealPerson person, double speedModifier) {
        super(person, speedModifier, 240, false);
        this.bedLoc = LocationManager.getBedLocation(person);
        this.jobLoc = LocationManager.getJobLocation(person);
        this.centerLoc = LocationManager.getVillageCenter(person);
    }

    @Override
    protected Vec3 getPosition() {
        float f = this.mob.level.random.nextFloat();
        if (this.mob.level.random.nextFloat() < 0.3F) {
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

    @Nullable
    private Vec3 getPositionTowardsAnywhere() {
        return LandRandomPos.getPos(this.mob, 10, 7);
    }

    @Nullable
    private Vec3 getPositionTowardsPoi() {
        BlockPos location;
        if (this.mob.level.isNight() || this.mob.level.isThundering()) {
            location = this.bedLoc;
        } else {
            location = this.jobLoc;
        }

        if (location.equals(BlockPos.ZERO)) {
            return null;
        }
        return LandRandomPos.getPosTowards(this.mob, 10, 7, Vec3.atBottomCenterOf(location));
    }

    @Nullable
    private Vec3 getPositionTowardsCenter() {
        BlockPos location = this.centerLoc;
        if (location.equals(BlockPos.ZERO)) {
            return null;
        } else {
            return LandRandomPos.getPosTowards(this.mob, 10, 7, Vec3.atBottomCenterOf(location));
        }
    }

}
