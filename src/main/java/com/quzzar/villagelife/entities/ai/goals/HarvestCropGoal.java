package com.quzzar.villagelife.entities.ai.goals;

import com.google.common.collect.ImmutableMap;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StemGrownBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

public class HarvestCropGoal extends Goal {

  private static final int SEARCH_RADIUS = 2;

  private int tickCount = 0;

  private BlockPos cropPos = BlockPos.ZERO;

  protected RealPerson person;
  protected boolean useWorkLoc;
  protected BlockPos workLocation;

  public HarvestCropGoal(RealPerson person, boolean useWorkLoc) {
    this.person = person;
    this.useWorkLoc = useWorkLoc;

    this.workLocation = BlockPos.ZERO;
    if (useWorkLoc) {
      this.workLocation = LocationManager.getJobLocation(person);
    }
  }

  @Override
  public boolean canUse() {
    return !shouldInterrupt();
  }

  @Override
  public void start() {
    this.cropPos = BlockPos.ZERO;
  }

  @Override
  public void tick() {
    if (this.person.level.isClientSide) {
      return;
    }

    tickCount++;

    if (tickCount % 20 == 0) {// Every 1 second

      if (this.cropPos == BlockPos.ZERO) {
        this.cropPos = this.findNearestCrop(getRelativeLocation());
      }

      if (this.cropPos != BlockPos.ZERO) {

        if (!this.person.swinging) {
          this.person.swing(this.person.getUsedItemHand());
        }

        harvestCrop(this.cropPos);

        this.cropPos = BlockPos.ZERO;

      } else {
        stop();
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

  private void harvestCrop(BlockPos cropLoc) {

    BlockState blockState = this.person.level.getBlockState(cropLoc);

    // Harvest crop

    // For berry bush
    if (blockState.getBlock() instanceof SweetBerryBushBlock) {
      int amt = this.person.getRandom().nextInt(1, 4);
      ItemStack berries = new ItemStack(Items.SWEET_BERRIES, amt);
      this.person.level.playSound((Player) null, cropLoc, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1.0F, 0.8F + this.person.getRandom().nextFloat() * 0.4F);
      this.person.level.setBlock(cropLoc, blockState.setValue(SweetBerryBushBlock.AGE, Integer.valueOf(1)), 2);
      this.person.addItems(Arrays.asList(berries));
      return;
    }

    // For all other crops
    List<ItemStack> items = Block.getDrops(blockState, (ServerLevel) this.person.level, cropLoc,
        this.person.level.getBlockEntity(cropLoc), this.person, this.person.getMainHandItem());

    this.person.level.playSound((Player) null, cropLoc.getX(), cropLoc.getY(), cropLoc.getZ(),
        blockState.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F,
        this.person.getRandom().nextFloat() * 0.4F + 0.8F);

    this.person.level.removeBlock(cropLoc, false);

    this.person.addItems(items);

    // Replant crop (if grown crop, aka not StemGrownBlock - like melon and pumpkin)
    if(blockState.getBlock() instanceof CropBlock){
      // Setting to default state will set crops to first stage.
      this.person.level.setBlockAndUpdate(cropLoc, blockState.getBlock().defaultBlockState());
    }

  }

  private BlockPos findNearestCrop(BlockPos location) {

    BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();
    BlockPos cropLoc = BlockPos.ZERO;

    int i = 0;
    for (int j = -1 * SEARCH_RADIUS; j <= SEARCH_RADIUS; ++j) {
      for (int k = -1 * SEARCH_RADIUS; k <= SEARCH_RADIUS; ++k) {
        for (int l = -1 * SEARCH_RADIUS; l <= SEARCH_RADIUS; ++l) {
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

    if (block instanceof CropBlock) {
      return ((CropBlock) block).isMaxAge(blockstate);
    }

    if (block instanceof StemGrownBlock) {
      return true;
    }

    if (block instanceof SweetBerryBushBlock) {
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