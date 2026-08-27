package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
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
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

public class HarvestCropGoal extends Goal {

  private static final int SEARCH_RADIUS = 2;

  /** Close enough to put a hand on it: three blocks. */
  private static final double REACH_SQR = 9.0D;

  private static final double SPEED = 0.5D;

  private int tickCount = 0;

  private BlockPos cropPos = BlockPos.ZERO;

  protected RealPerson person;
  protected boolean useWorkLoc;
  protected BlockPos workLocation;

  /** Whether walking is getting anywhere, and what to do when it is not (#75). */
  private final ApproachWatch approach;

  public HarvestCropGoal(RealPerson person, boolean useWorkLoc) {
    // This goal walks the villager somewhere, so it must compete for movement
    // rather than run alongside every other goal that does the same (#74).
    this.setFlags(EnumSet.of(Flag.MOVE));
    this.person = person;
    this.useWorkLoc = useWorkLoc;
    this.approach = new ApproachWatch(person, "the field");

    this.workLocation = BlockPos.ZERO;
    if (useWorkLoc) {
      this.workLocation = LocationManager.getJobLocation(person);
    }
  }

  @Override
  public boolean canUse() {
    return !shouldInterrupt() && !this.approach.standingDown();
  }

  @Override
  public void start() {
    this.cropPos = BlockPos.ZERO;
  }

  @Override
  public void tick() {
    if (this.person.level().isClientSide) {
      return;
    }

    tickCount++;

    if (this.cropPos == BlockPos.ZERO) {
      if (tickCount % 20 != 0) { // Looking for work is a once-a-second job
        return;
      }
      this.cropPos = this.findNearestCrop(getRelativeLocation());
      if (this.cropPos == BlockPos.ZERO) {
        stop();
        return;
      }
      this.approach.begin();
    }

    // The farmer goes to the crop. Before this they harvested whatever was
    // within five blocks of their STATION from wherever they happened to be
    // standing, so a field was worked by a villager who had never been in it —
    // and only when ambient wandering left them close enough to their post for
    // the search to find anything at all.
    if (this.person.blockPosition().distSqr(this.cropPos) > REACH_SQR) {
      if (this.approach.giveUp(this.cropPos)) {
        this.cropPos = BlockPos.ZERO;
        return;
      }
      this.person.getNavigation().moveTo(
          this.cropPos.getX() + 0.5D, this.cropPos.getY(), this.cropPos.getZ() + 0.5D, SPEED);
      return;
    }

    this.approach.arrived();
    this.person.getLookControl().setLookAt(
        this.cropPos.getX(), this.cropPos.getY(), this.cropPos.getZ(), 30.0F, 30.0F);

    if (tickCount % 20 == 0) {// Every 1 second
      if (!this.person.swinging) {
        this.person.swing(this.person.getUsedItemHand());
      }
      harvestCrop(this.cropPos);
      this.cropPos = BlockPos.ZERO;
    }

  }

  private BlockPos getRelativeLocation() {
    if (useWorkLoc) {
      return this.workLocation;
    } else {
      return net.minecraft.core.BlockPos.containing(this.person.getEyePosition());
    }
  }

  private void harvestCrop(BlockPos cropLoc) {

    BlockState blockState = this.person.level().getBlockState(cropLoc);

    // Harvest crop

    // For berry bush
    if (blockState.getBlock() instanceof SweetBerryBushBlock) {
      int amt = this.person.getRandom().nextInt(1, 4);
      ItemStack berries = new ItemStack(Items.SWEET_BERRIES, amt);
      this.person.level().playSound((Player) null, cropLoc, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1.0F, 0.8F + this.person.getRandom().nextFloat() * 0.4F);
      this.person.level().setBlock(cropLoc, blockState.setValue(SweetBerryBushBlock.AGE, Integer.valueOf(1)), 2);
      this.person.addItems(Arrays.asList(berries));
      return;
    }

    // For all other crops
    List<ItemStack> items = Block.getDrops(blockState, (ServerLevel) this.person.level(), cropLoc,
        this.person.level().getBlockEntity(cropLoc), this.person, this.person.getMainHandItem());

    this.person.level().playSound((Player) null, cropLoc.getX(), cropLoc.getY(), cropLoc.getZ(),
        blockState.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F,
        this.person.getRandom().nextFloat() * 0.4F + 0.8F);

    this.person.level().removeBlock(cropLoc, false);

    this.person.addItems(items);

    // Replant crop (if grown crop, aka not StemGrownBlock - like melon and pumpkin)
    if(blockState.getBlock() instanceof CropBlock){
      // Setting to default state will set crops to first stage.
      this.person.level().setBlockAndUpdate(cropLoc, blockState.getBlock().defaultBlockState());
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
            if (this.person.level().random.nextInt(i) == 0) {
              cropLoc = blockpos$mutableblockpos.immutable();
            }
          }
        }
      }
    }

    return cropLoc;

  }

  private boolean validCrop(BlockPos pos) {
    BlockState blockstate = this.person.level().getBlockState(pos);
    Block block = blockstate.getBlock();

    if (block instanceof CropBlock) {
      return ((CropBlock) block).isMaxAge(blockstate);
    }

    if (block == net.minecraft.world.level.block.Blocks.MELON || block == net.minecraft.world.level.block.Blocks.PUMPKIN) {
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
        || this.person.level().isNight()
        || this.person.isInterrupted();
  }
}