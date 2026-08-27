package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class TillSoilGoal extends Goal {

  public static final Map<Block, Block> TILLABLES = Maps.newHashMap(ImmutableMap.of(
      Blocks.GRASS_BLOCK, Blocks.FARMLAND,
      Blocks.DIRT_PATH, Blocks.FARMLAND,
      Blocks.DIRT, Blocks.FARMLAND,
      Blocks.COARSE_DIRT, Blocks.DIRT,
      Blocks.ROOTED_DIRT, Blocks.DIRT));

  public static final Map<Item, Block> PLANTABLES = Maps.newHashMap(ImmutableMap.of(
      Items.WHEAT_SEEDS, Blocks.WHEAT,
      Items.BEETROOT_SEEDS, Blocks.BEETROOTS,
      Items.PUMPKIN_SEEDS, Blocks.PUMPKIN_STEM,
      Items.MELON_SEEDS, Blocks.MELON_STEM,
      Items.CARROT, Blocks.CARROTS,
      Items.POTATO, Blocks.POTATOES,
      Items.SWEET_BERRIES, Blocks.SWEET_BERRY_BUSH));

  private static final int SEARCH_RADIUS = 2;

  /** Close enough to put a hand on it: three blocks. */
  private static final double REACH_SQR = 9.0D;

  private static final double SPEED = 0.5D;

  /** Ticks between looking for work, so a fruitless search is not run every tick. */
  private static final int SEARCH_INTERVAL = 20;

  private int nextSearchTick = 0;

  private int tickCount = 0;

  private BlockPos soilPos = BlockPos.ZERO;

  protected RealPerson person;
  protected boolean useWorkLoc;
  protected BlockPos workLocation;

  /** Whether walking is getting anywhere, and what to do when it is not (#75). */
  private final ApproachWatch approach;

  public TillSoilGoal(RealPerson person, boolean useWorkLoc) {
    this.approach = new ApproachWatch(person, "the field");
    // This goal walks the villager somewhere, so it must compete for movement
    // rather than run alongside every other goal that does the same (#74).
    this.setFlags(EnumSet.of(Flag.MOVE));
    this.person = person;
    this.useWorkLoc = useWorkLoc;

    this.workLocation = BlockPos.ZERO;
    if (useWorkLoc) {
      this.workLocation = LocationManager.getJobLocation(person);
    }
  }

  @Override
  public boolean canUse() {
    if (shouldInterrupt() || this.approach.standingDown()) {
      return false;
    }
    // Only take movement when there is ground worth breaking. This goal used
    // to answer yes always and never end, so a farmer held the movement flag
    // for life and stood exactly where they were put.
    if (this.person.tickCount < this.nextSearchTick) {
      return false;
    }
    this.nextSearchTick = this.person.tickCount + SEARCH_INTERVAL;
    if (findPlantableItem() == null) {
      return false;
    }
    this.soilPos = this.findUntilledSoil(getRelativeLocation());
    return this.soilPos != BlockPos.ZERO;
  }

  @Override
  public boolean canContinueToUse() {
    return this.soilPos != BlockPos.ZERO && !shouldInterrupt() && !this.approach.standingDown();
  }

  @Override
  public void start() {
    this.approach.begin();
    this.tickCount = 0;
  }

  @Override
  public void stop() {
    this.soilPos = BlockPos.ZERO;
  }

  @Override
  public void tick() {
    if (this.person.level().isClientSide) {
      return;
    }
    
    tickCount++;

    if (this.soilPos == BlockPos.ZERO) {
      return; // canUse finds the work; without one the goal has already ended
    }

    // The farmer goes to the ground they are breaking. Before this they tilled
    // and sowed whatever lay within five blocks of their STATION from wherever
    // they happened to be standing, which is a field worked by someone who was
    // never in it.
    if (this.person.blockPosition().distSqr(this.soilPos) > REACH_SQR) {
      if (this.approach.giveUp(this.soilPos)) {
        this.soilPos = BlockPos.ZERO;
        return;
      }
      this.person.getNavigation().moveTo(
          this.soilPos.getX() + 0.5D, this.soilPos.getY(), this.soilPos.getZ() + 0.5D, SPEED);
      return;
    }

    this.approach.arrived();
    this.person.getLookControl().setLookAt(
        this.soilPos.getX(), this.soilPos.getY(), this.soilPos.getZ(), 30.0F, 30.0F);

    if (tickCount % 20 == 0) {// Every 1 second
      Item plantableItem = findPlantableItem();
      if (plantableItem == null) {
        stop(); return;
      }
      if (!this.person.swinging) {
        this.person.swing(this.person.getUsedItemHand());
      }
      tillSoil(this.soilPos, plantableItem);
      this.soilPos = BlockPos.ZERO;
    }

  }

  private void tillSoil(BlockPos soilPos, Item plantableItem) {

    // Till soil
    this.person.level().playSound((Player) null, soilPos.getX(), soilPos.getY(), soilPos.getZ(),
        SoundEvents.HOE_TILL, SoundSource.PLAYERS, 1.0F,
        this.person.getRandom().nextFloat() * 0.4F + 0.8F);

    Block block = this.person.level().getBlockState(soilPos).getBlock();
    if(TILLABLES.get(block) != null){
      this.person.level().setBlockAndUpdate(soilPos, TILLABLES.get(block).defaultBlockState());
    }

    // Plant seeds
    if(plantableItem != null && PLANTABLES.get(plantableItem) != null){
      if(this.person.removeItem(plantableItem, 1).getCount() == 1){
        this.person.level().setBlockAndUpdate(soilPos.above(), PLANTABLES.get(plantableItem).defaultBlockState());
      }
    }

  }

  @Nullable
  private Item findPlantableItem(){
    ArrayList<Item> items = new ArrayList<>(PLANTABLES.keySet());
    Collections.shuffle(items);
    for(Item item : items){
      if(this.person.hasItem(item)){
        return item;
      }
    }
    return null;
  }

  private BlockPos getRelativeLocation() {
    if (useWorkLoc) {
      return this.workLocation;
    } else {
      return net.minecraft.core.BlockPos.containing(this.person.getEyePosition()).below();
    }
  }

  private BlockPos findUntilledSoil(BlockPos location) {

    for (int j = -1 * SEARCH_RADIUS; j <= SEARCH_RADIUS; ++j) {
      for (int l = -1 * SEARCH_RADIUS; l <= SEARCH_RADIUS; ++l) {
        BlockPos testLoc = location.offset(j, -1, l);
        if (this.validUntilledSoil(testLoc)) {
          return testLoc;
        }
      }
    }

    return BlockPos.ZERO;

  }

  private boolean validUntilledSoil(BlockPos pos) {

    // If doesn't have air above it, it isn't valid
    if(!this.person.level().getBlockState(pos.above()).isAir()){
      return false;
    }

    Block block = this.person.level().getBlockState(pos).getBlock();

    // If it's tilled soil but also doesn't have anything planted on top of it?
    if(block == Blocks.FARMLAND){
      return true;
    }

    return TILLABLES.keySet().contains(block);
  }

  protected boolean shouldInterrupt() {
    return this.person.getLastHurtByMob() != null
        || this.person.isFreezing()
        || this.person.isOnFire()
        || this.person.level().isNight()
        || this.person.isInterrupted();
  }
}