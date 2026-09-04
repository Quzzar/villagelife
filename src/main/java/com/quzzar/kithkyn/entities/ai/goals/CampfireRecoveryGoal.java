package com.quzzar.kithkyn.entities.ai.goals;

import java.util.EnumSet;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.entities.ai.HealthRecoveryPolicy;
import com.quzzar.kithkyn.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Lets a badly hurt resident with no meal warm themselves at the village
 * center's lit campfire. This is a personal need like eating, not a job: every
 * resident may do it, regardless of age or occupation.
 *
 * <p>The fire is deliberately local. A worker does not cross the whole village
 * for free healing, but someone within thirty blocks may walk to arm's reach,
 * interact once, and receive ten seconds of Regeneration I. The per-person
 * one-minute cooldown survives goal rebuilds and saves.
 */
public final class CampfireRecoveryGoal extends Goal {

  private static final double REACH_SQR = 3.0D * 3.0D;
  private static final double SPEED = 0.6D;
  private static final int SELECT_INTERVAL_TICKS = 20;
  private static final int NAVIGATION_REFRESH_TICKS = 10;

  private final RealPerson person;
  private final ApproachWatch approach;

  @Nullable
  private BlockPos campfire;
  private int nextSelectTick;
  private int nextNavigationTick;
  private boolean complete;

  public CampfireRecoveryGoal(RealPerson person) {
    this.person = person;
    this.approach = new ApproachWatch(person, "the campfire to recover");
    this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
  }

  @Override
  public boolean canUse() {
    if (!mayRecover() || this.approach.standingDown()
        || this.person.tickCount < this.nextSelectTick) {
      return false;
    }
    this.nextSelectTick = this.person.tickCount + SELECT_INTERVAL_TICKS;

    Village village = this.person.getVillage();
    if (village == null || !village.hasResident(this.person.getUUID())) {
      return false;
    }
    BlockPos fire = village.getCampfirePosition();
    if (fire == null
        || !fire.closerToCenterThan(
            this.person.position(), HealthRecoveryPolicy.CAMPFIRE_SEARCH_RANGE)
        || !this.person.level().hasChunkAt(fire)) {
      return false;
    }
    BlockPos litFire = village.getCampfire();
    if (litFire == null || !litFire.equals(fire)) {
      return false;
    }
    this.campfire = litFire;
    return true;
  }

  @Override
  public boolean canContinueToUse() {
    if (this.complete || this.campfire == null || !mayRecover()) {
      return false;
    }
    Village village = this.person.getVillage();
    return village != null && this.person.level().hasChunkAt(this.campfire)
        && this.campfire.equals(village.getCampfire());
  }

  @Override
  public boolean requiresUpdateEveryTick() {
    return true;
  }

  @Override
  public void start() {
    this.complete = false;
    this.nextNavigationTick = this.person.tickCount;
    this.approach.begin();
  }

  @Override
  public void stop() {
    this.person.getNavigation().stop();
    this.campfire = null;
    this.complete = false;
  }

  @Override
  public void tick() {
    if (this.campfire == null) {
      this.complete = true;
      return;
    }
    if (this.person.blockPosition().distSqr(this.campfire) <= REACH_SQR) {
      recover();
      return;
    }
    if (this.approach.giveUp(this.campfire)) {
      this.complete = true;
      return;
    }
    if (this.person.tickCount >= this.nextNavigationTick) {
      this.nextNavigationTick = this.person.tickCount + NAVIGATION_REFRESH_TICKS;
      this.person.getNavigation().moveTo(
          this.campfire.getX() + 0.5D,
          this.campfire.getY(),
          this.campfire.getZ() + 0.5D,
          SPEED);
    }
  }

  /** Perform the visible interaction and begin the persisted cooldown. */
  private void recover() {
    this.approach.arrived();
    this.person.getNavigation().stop();
    this.person.getLookControl().setLookAt(
        this.campfire.getX() + 0.5D,
        this.campfire.getY() + 0.5D,
        this.campfire.getZ() + 0.5D,
        30.0F,
        30.0F);
    this.person.swing(InteractionHand.MAIN_HAND);
    this.person.addEffect(new MobEffectInstance(
        MobEffects.REGENERATION,
        HealthRecoveryPolicy.CAMPFIRE_REGENERATION_TICKS,
        0));
    this.person.startCampfireRecoveryCooldown();
    this.person.level().playSound(
        null,
        this.campfire,
        SoundEvents.FIRECHARGE_USE,
        SoundSource.BLOCKS,
        0.5F,
        1.0F);
    Kithkyn.LOGGER.debug("{} warmed at the village campfire to recover", this.person.getFullName());
    this.complete = true;
  }

  /** Whether personal state permits starting or finishing a recovery visit. */
  private boolean mayRecover() {
    return this.person.shouldSeekCampfireRecovery()
        && !this.person.hasEffect(MobEffects.REGENERATION)
        && this.person.getTravelTarget() == null
        && this.person.getTarget() == null
        && !this.person.isAggressive()
        && !this.person.isSleeping()
        && !this.person.isImmobile()
        && !this.person.isInterrupted();
  }
}
