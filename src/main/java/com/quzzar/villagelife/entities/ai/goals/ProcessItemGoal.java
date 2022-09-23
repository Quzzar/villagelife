package com.quzzar.villagelife.entities.ai.goals;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class ProcessItemGoal extends Goal {

  private int tickCount = 0;

  protected RealPerson person;
  protected ItemStack inputStack;
  protected ItemStack outputStack;
  protected int processingItem;

  private SoundEvent sound;
  private BlockPos jobLoc;

  // Process item in batches of amount in inputStack.
  public ProcessItemGoal(RealPerson person, ItemStack inputStack, ItemStack outputStack, int processingItem, SoundEvent sound) {
    this.person = person;
    this.inputStack = inputStack;
    this.outputStack = outputStack;
    this.processingItem = processingItem;
    this.sound = sound;

    this.jobLoc = LocationManager.getJobLocation(person);
    if (this.jobLoc == BlockPos.ZERO) {
      this.jobLoc = null;
    }
  }

  @Override
  public boolean canUse() {
    if (person.getVillage() == null) {
      return false;
    }
    return !shouldInterrupt() && this.person.getVillage().hasItemStackInVillage(inputStack);
  }

  @Override
  public void start() {
    this.person.setPose(Pose.CROUCHING);
  }

  @Override
  public void stop() {
    this.person.setPose(Pose.STANDING);
    this.person.setInterrupted(false);
  }

  @Override
  public void tick() {
    tickCount++;

    person.getNavigation().stop();
    person.setInterrupted(true);

    if (tickCount % 20 == 0) {// Every 1 second

      if (!person.swinging) {
        person.swing(person.getUsedItemHand());
      }

      person.level.playSound((Player) null, person.getEyePosition().x, person.getEyePosition().y, person.getEyePosition().z, sound, SoundSource.PLAYERS, 0.5F, this.person.getRandom().nextFloat() * 0.4F + 0.6F);

    }

    if (tickCount % (20*processingItem) == 0) {// Every X seconds
      
      if(!this.person.getVillage().hasItemStackInVillage(inputStack)){
        stop(); return;
      }

      ItemStack gatheredStack = this.person.getVillage().gatherItemStackFromVillage(inputStack);
      if(gatheredStack.getCount() < inputStack.getCount()){
        // Didn't pay full cost
      }

      boolean addedAll = this.person.getVillage().placeItemStackIntoVillage(outputStack, this.person, this.jobLoc);
      if(!addedAll){
        stop(); return;
      }

    }

  }

  protected boolean shouldInterrupt() {
    return this.person.getLastHurtByMob() != null
        || this.person.isFreezing()
        || this.person.isOnFire()
        || this.person.getLevel().isNight();
  }

}
