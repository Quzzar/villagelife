package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import java.util.List;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

public class RunToEatGoal extends RandomStrollGoal {
    private final RealPerson guard;
    private int walkTimer;
    private boolean startedRunning;

    public RunToEatGoal(RealPerson guard) {
        super(guard, 0.8D);
        this.guard = guard;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.TARGET, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.guard.isRunningToEat() && this.getPosition() != null;
    }

    @Override
    public void start() {
        super.start();
        if (this.walkTimer <= 0 && !startedRunning) {
            this.walkTimer = 10;
            startedRunning = true;
        }
    }

    @Override
    public void tick() {
        if (--walkTimer <= 0 && guard.isRunningToEat()) {
            this.guard.setRunningToEat(false);
            this.guard.setEating(true);
            startedRunning = false;
            this.guard.getNavigation().stop();
        }
        List<LivingEntity> list = this.guard.level.getEntitiesOfClass(LivingEntity.class, this.guard.getBoundingBox().inflate(5.0D, 3.0D, 5.0D));
        if (!list.isEmpty()) {
            for (LivingEntity mob : list) {
                if (mob != null) {
                    if (mob.getLastHurtMob() instanceof RealPerson || mob instanceof Mob && ((Mob) mob).getTarget() instanceof RealPerson) {
                        if (walkTimer < 10)
                            this.walkTimer += 3;
                    }
                }
            }
        }
    }

    @Override
    protected Vec3 getPosition() {
        List<LivingEntity> list = this.guard.level.getEntitiesOfClass(LivingEntity.class, this.guard.getBoundingBox().inflate(5.0D, 3.0D, 5.0D));
        if (!list.isEmpty()) {
            for (LivingEntity mob : list) {
                if (mob != null) {
                    if (mob.getLastHurtMob() instanceof RealPerson || mob instanceof Mob && ((Mob) mob).getTarget() instanceof RealPerson) {
                        if (guard.level.random.nextFloat() < 0.6F) {
                            return DefaultRandomPos.getPosAway(guard, 16, 7, mob.position());
                        } else {
                            BlockPos jobPos = LocationManager.getJobLocation(guard);
                            if(jobPos != BlockPos.ZERO){
                                return Vec3.atBottomCenterOf(jobPos);
                            } else {
                                return super.getPosition();
                            }
                        }
                    }
                }
            }
        }
        return super.getPosition();
    }

    @Override
    public boolean canContinueToUse() {
        return super.canContinueToUse() && this.walkTimer > 0 && this.guard.isRunningToEat() && !guard.isEating() && startedRunning;
    }
}
