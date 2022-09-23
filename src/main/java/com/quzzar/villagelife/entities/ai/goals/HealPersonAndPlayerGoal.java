package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import java.util.List;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Occupation;

import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class HealPersonAndPlayerGoal extends Goal {
    private int tickCount = 0;
    private final Mob healer;
    private LivingEntity personOrPlayer;
    private boolean isChargingThrow = false;
    private int rangedThrowTime = 0;
    private final int minThrowTime;
    private final int maxThrowTime;
    private final float maxThrowDistance;

    public HealPersonAndPlayerGoal(Mob healer, int minThrowTime, int maxThrowTime, float maxThrowDistance) {
        this.healer = healer;
        this.minThrowTime = minThrowTime;
        this.maxThrowTime = maxThrowTime;
        this.maxThrowDistance = maxThrowDistance;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (((RealPerson) this.healer).getOccupation() != Occupation.CLERIC || this.healer.isSleeping()) {
            return false;
        }
        List<LivingEntity> list = this.healer.level.getEntitiesOfClass(LivingEntity.class, this.healer.getBoundingBox().inflate(10.0D, 3.0D, 10.0D));
        if (!list.isEmpty()) {
            for (LivingEntity mob : list) {
                if (mob != null && !mob.hasEffect(MobEffects.REGENERATION)) {
                    if ((mob instanceof Person && mob.isAlive() && mob.getHealth() < mob.getMaxHealth() && mob != healer)
                            || mob instanceof Player && !((Player) mob).getAbilities().instabuild && mob.getHealth() < mob.getMaxHealth()) {
                        
                        this.personOrPlayer = mob; //TODO, make player need to be friend
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse() && personOrPlayer != null && personOrPlayer.getHealth() < personOrPlayer.getMaxHealth();
    }

    @Override
    public void stop() {
        this.personOrPlayer = null;
        this.healer.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        this.rangedThrowTime = 0;
        this.isChargingThrow = false;
        if (this.healer.getPose() == Pose.CROUCHING) {
            this.healer.setPose(Pose.STANDING);
        }
    }

    @Override
    public void tick() {
        if (personOrPlayer == null) { return; }
        tickCount++;

        this.healer.getLookControl().setLookAt(personOrPlayer, 30.0F, 30.0F);
        if (this.personOrPlayer.distanceTo(this.healer) >= this.maxThrowDistance*0.75) {
            this.healer.getNavigation().moveTo(this.personOrPlayer, 0.5D);
            if(this.personOrPlayer instanceof Person){
                ((Person)this.personOrPlayer).getNavigation().moveTo(this.healer, 0.6D);
            }
        } else {

            if(tickCount % 20 == 0) {// Every 1 second

                if(!this.isChargingThrow){ // Start charging
                    if (!this.healer.getSensing().hasLineOfSight(this.personOrPlayer)) {
                        this.healer.getNavigation().moveTo(this.personOrPlayer, 0.5D);
                        if(this.personOrPlayer instanceof Person){
                            ((Person)this.personOrPlayer).getNavigation().moveTo(this.healer, 0.6D);
                        }
                        return;
                    }

                    this.isChargingThrow = true;
                    if (this.healer.getPose() == Pose.STANDING) {
                        this.healer.setPose(Pose.CROUCHING);
                    }

                    Villagelife.LOGGER.debug(this.personOrPlayer.distanceTo(this.healer)+" / "+this.maxThrowDistance);
                    this.rangedThrowTime = (int) Mth.lerp(this.personOrPlayer.distanceTo(this.healer) / this.maxThrowDistance, this.minThrowTime, this.maxThrowTime);
                    Villagelife.LOGGER.debug(""+this.rangedThrowTime);

                } else { // Charging throw...

                    if(this.rangedThrowTime <= 0){
                        if (!this.healer.getSensing().hasLineOfSight(this.personOrPlayer)) {
                            stop();
                            return;
                        }
    
                        this.throwPotion(this.personOrPlayer);
                        this.isChargingThrow = false;
                        if (this.healer.getPose() == Pose.CROUCHING) {
                            this.healer.setPose(Pose.STANDING);
                        }
    
                    } else {
                        this.rangedThrowTime--;
                    }

                }

            }


        }
    }

    public void throwPotion(LivingEntity target) {
        Vec3 vec3d = target.getDeltaMovement();
        double d0 = target.getX() + vec3d.x - healer.getX();
        double d1 = target.getEyeY() - (double) 1.1F - healer.getY();
        double d2 = target.getZ() + vec3d.z - healer.getZ();
        float f = Mth.sqrt((float) (d0 * d0 + d2 * d2));
        Potion potion = Potions.REGENERATION;
        if (target.getHealth() <= 4.0F) {
            potion = Potions.HEALING;
        } else {
            potion = Potions.REGENERATION;
        }
        ThrownPotion potionentity = new ThrownPotion(healer.level, healer);
        potionentity.setItem(PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), potion));
        potionentity.setXRot(-20.0F);
        potionentity.shoot(d0, d1 + (double) (f * 0.2F), d2, 0.75F, 8.0F);
        healer.level.playSound((Player) null, healer.getX(), healer.getY(), healer.getZ(), SoundEvents.SPLASH_POTION_THROW, healer.getSoundSource(), 1.0F, 0.8F + healer.getRandom().nextFloat() * 0.4F);
        healer.level.addFreshEntity(potionentity);
    }
}
