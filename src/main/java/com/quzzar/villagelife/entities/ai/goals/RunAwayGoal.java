package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import java.util.List;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

public class RunAwayGoal extends Goal {

    protected static final double MAX_DISTANCE = 100;

    protected RealPerson person;
    protected BlockPos runToLocation;

    public RunAwayGoal(RealPerson person){
        this.person = person;
        this.runToLocation = BlockPos.ZERO;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if(person.getHealth() >= person.getMaxHealth() / 3){ return false; }

        setRunToLocation();
        return runToLocation != BlockPos.ZERO;
    }

    @Override
    public void start() {
        this.person.setTarget(null);
    }

    @Override
    public void tick() {
        if(!person.getNavigation().isInProgress()){
            person.getNavigation().moveTo(runToLocation.getX(), runToLocation.getY(), runToLocation.getZ(), 0.8D);
        }
    }

    protected void setRunToLocation(){
        List<LivingEntity> list = person.level.getEntitiesOfClass(LivingEntity.class, person.getBoundingBox().inflate(5.0D, 3.0D, 5.0D));
        if (!list.isEmpty()) {
            for (LivingEntity mob : list) {
                if (mob != null) {
                    if (mob.getLastHurtMob() instanceof RealPerson || mob instanceof Mob && ((Mob) mob).getTarget() instanceof RealPerson) {
                        if (person.level.random.nextFloat() < 0.7F) {
                            Vec3 vecLoc = DefaultRandomPos.getPosAway(person, 16, 7, mob.position());
                            if(vecLoc != null){
                                this.runToLocation = new BlockPos(vecLoc);
                            } else {
                                this.runToLocation = BlockPos.ZERO;
                            }
                        } else {
                            this.runToLocation = LocationManager.getBedLocation(person);
                        }
                        return;
                    }
                }
            }
        }
        this.runToLocation = BlockPos.ZERO;
    }

}
