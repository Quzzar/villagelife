package com.quzzar.villagelife.entities.ai.goals;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.buildings.BuildProgress;
import com.quzzar.villagelife.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;

public class WorkOnMakingPathsGoal extends Goal {

  protected final double PERCENT_DECREASE = 0.4;

  protected final List<Block> REPLACEABLE_BLOCKS = Arrays.asList(Blocks.GRASS_BLOCK, Blocks.MYCELIUM, Blocks.STONE, Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.CLAY, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL);

  private int tickCount = 0;

  protected RealPerson person;

  protected boolean isConstructing;
  protected Building buildingA;
  protected Building buildingB;

  protected BlockPos centerA;
  protected BlockPos centerB;

  public WorkOnMakingPathsGoal(RealPerson person) {
    this.person = person;
    this.isConstructing = false;
    this.buildingA = null;
    this.buildingB = null;
  }

  @Override
  public boolean canUse() {
    if (person.getVillage() == null) {
      return false;
    }
    if (shouldInterrupt()) {
      return false;
    }
    return true;
  }

  @Override
  public boolean canContinueToUse() {
    return !shouldInterrupt();
  }

  @Override
  public void start() {
    this.isConstructing = false;

    Collection<Building> buildings = person.getVillage().getBuildings();

    this.buildingA = buildings.stream()
        .skip((int) (buildings.size() * Math.random()))
        .findFirst().get();
    this.centerA = BlockPos.of(buildingA.getCenterLocation());

    this.buildingB = buildings.stream()
        .skip((int) (buildings.size() * Math.random()))
        .findFirst().get();
    this.centerB = BlockPos.of(buildingB.getCenterLocation());

    if (this.buildingA == this.buildingB) {
      this.stop();
    }
  }

  @Override
  public void stop() {
    this.isConstructing = false;
    this.buildingA = null;
    this.buildingB = null;
  }

  @Override
  public void tick() {
    tickCount++;
    if(this.buildingA == null || this.buildingB == null) { return; }

    if(isConstructing){

      // Go to point B while building. Once there, stop.
      if (centerB.distSqr(person.blockPosition()) <= Math.pow(buildingB.getRadius(), 2)*PERCENT_DECREASE) {
        
        stop();

      } else {

        person.getNavigation().moveTo(centerB.getX(), centerB.getY(), centerB.getZ(), 0.3D);

        if (tickCount % 10 == 0) {// Every 1/2 second

          BlockPos groundPos;
          switch((int)(Math.random()*5)) {
            case 0: groundPos = person.getOnPos(); break;
            case 1: groundPos = person.getOnPos().relative(Direction.EAST); break;
            case 2: groundPos = person.getOnPos().relative(Direction.NORTH); break;
            case 3: groundPos = person.getOnPos().relative(Direction.SOUTH); break;
            case 4: groundPos = person.getOnPos().relative(Direction.WEST); break;
            default: groundPos = person.getOnPos(); break;
          }

          if(REPLACEABLE_BLOCKS.contains(person.level.getBlockState(groundPos).getBlock())){

            // If is under crop or sapling, don't replace.
            Block aboveBlock = person.level.getBlockState(groundPos.above()).getBlock();
            if(aboveBlock instanceof SaplingBlock || aboveBlock instanceof CropBlock){
              return;
            }

            if (!person.swinging) {
              person.swing(person.getUsedItemHand());
            }
      
            person.level.setBlock(groundPos, Blocks.DIRT_PATH.defaultBlockState(), 2);

            person.level.playSound((Player) null, groundPos.getX(), groundPos.getY(), groundPos.getZ(), SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1.0F, this.person.getRandom().nextFloat() * 0.4F + 0.8F);

          }
    
        }

      }

    } else {

      // Go to point A. Once there, starting building.
      if (centerA.distSqr(person.blockPosition()) <= Math.pow(buildingA.getRadius(), 2)*PERCENT_DECREASE) {
        this.isConstructing = true;
      } else {
        person.getNavigation().moveTo(centerA.getX(), centerA.getY(), centerA.getZ(), 0.5D);
      }

    }

  }

  protected boolean shouldInterrupt() {
    return this.person.getLastHurtByMob() != null
        || this.person.isFreezing()
        || this.person.isOnFire()
        || this.person.getLevel().isNight()
        || this.person.isInterrupted();
  }

}
