package com.quzzar.villagelife.entities.ai.goals;

import com.google.common.collect.ImmutableMap;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;

public class UseBonemealGoal extends Goal {

  private static final int SEARCH_RADIUS = 2;

  private int tickCount = 0;

  private BlockPos cropPos = BlockPos.ZERO;

  protected RealPerson person;
  protected boolean useWorkLoc;
  protected BlockPos workLocation;

  public UseBonemealGoal(RealPerson person, boolean useWorkLoc) {
    this.person = person;
    this.useWorkLoc = useWorkLoc;

    this.workLocation = BlockPos.ZERO;
    if (useWorkLoc) {
      this.workLocation = LocationManager.getJobLocation(person);
    }
  }

  @Override
  public boolean canUse() {
    return this.person.hasItem(Items.BONE_MEAL) && !shouldInterrupt();
  }

  @Override
  public boolean canContinueToUse() {
    return !shouldInterrupt();
  }

  @Override
  public void start() {
    this.cropPos = BlockPos.ZERO;
  }

  @Override
  public void tick() {
    tickCount++;

    if (tickCount % 20 == 0) {// Every 1 second

      if (!this.person.hasItem(Items.BONE_MEAL)) {
        stop();
      } else {

        if(this.cropPos == BlockPos.ZERO){
          this.cropPos = this.findNearestCrop(getRelativeLocation());
        }

        if(this.cropPos != BlockPos.ZERO){

          if (!this.person.swinging) {
            this.person.swing(this.person.getUsedItemHand());
          }

          bonemealCrop(this.cropPos);

        } else {
          stop();
        }

      }

    }

  }

  private BlockPos getRelativeLocation() {
    if (useWorkLoc) {
      return this.workLocation;
    } else {
      return this.person.eyeBlockPosition();
    }
  }

  private void bonemealCrop(BlockPos cropLoc) {

    ItemStack item = this.person.removeItem(Items.BONE_MEAL, 1);

    if (item.getCount() == 1 && BoneMealItem.growCrop(item, this.person.level, cropLoc)) {
      this.person.level.levelEvent(1505, cropLoc, 0);
      this.cropPos = BlockPos.ZERO;
    }

  }

  private BlockPos findNearestCrop(BlockPos location) {

    BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();
    BlockPos cropLoc = BlockPos.ZERO;

    int i = 0;
    for (int j = -1*SEARCH_RADIUS; j <= SEARCH_RADIUS; ++j) {
      for (int k = -1*SEARCH_RADIUS; k <= SEARCH_RADIUS; ++k) {
        for (int l = -1*SEARCH_RADIUS; l <= SEARCH_RADIUS; ++l) {
          blockpos$mutableblockpos.setWithOffset(location, j, k, l);
          if (this.validCrop(blockpos$mutableblockpos.immutable())) {
            ++i;
            if (this.person.level.random.nextInt(i) == 0) {
              cropLoc = blockpos$mutableblockpos.immutable();
            }
          }
        }
      }
    }

    return cropLoc;

  }

  private boolean validCrop(BlockPos pos) {
    BlockState blockstate = this.person.level.getBlockState(pos);
    Block block = blockstate.getBlock();

    if(block instanceof CropBlock){
      return !((CropBlock) block).isMaxAge(blockstate);
    }

    if(block instanceof SaplingBlock){
      return true;
    }

    return false;
  }

  protected boolean shouldInterrupt() {
    return this.person.getLastHurtByMob() != null
        || this.person.isFreezing()
        || this.person.isOnFire()
        || this.person.getLevel().isNight()
        || this.person.isInterrupted();
  }
}