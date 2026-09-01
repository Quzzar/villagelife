package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Breaking ground and sowing it: a PLACE step, which puts a block down and pays
 * for it out of what the worker is carrying.
 *
 * The search radius stays at two, as it was. It is small - a five-by-five patch
 * around the station - but tilling REPLACES blocks, so widening it would have a
 * farmer turning paths and other people's ground into farmland. That is a
 * tuning decision for the job definitions to carry, not something to change
 * quietly inside a port.
 */
public final class TillStep implements BlockWorkStep {

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

  private final boolean useStation;

  public TillStep(boolean useStation) {
    this.useStation = useStation;
  }

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    // Nothing to sow means nothing to break ground for.
    if (seedInHand(person) == null) {
      return null;
    }
    return findUntilled(person, origin(person));
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    Item seed = seedInHand(person);
    if (seed == null) {
      return false; // ran out between selecting and arriving
    }
    person.level().playSound((Player) null, target.getX(), target.getY(), target.getZ(),
        SoundEvents.HOE_TILL, SoundSource.PLAYERS, 1.0F,
        person.getRandom().nextFloat() * 0.4F + 0.8F);

    Block tilled = TILLABLES.get(person.level().getBlockState(target).getBlock());
    if (tilled != null) {
      person.level().setBlockAndUpdate(target, tilled.defaultBlockState());
    }
    Block crop = PLANTABLES.get(seed);
    if (crop != null && person.removeItem(seed, 1).getCount() == 1) {
      person.level().setBlockAndUpdate(target.above(), crop.defaultBlockState());
    }
    return false; // one patch per visit
  }

  @Override
  public String describe() {
    return "the field";
  }

  @Override
  public String activity() {
    return "tilling the field";
  }

  @Override
  public int actEveryTicks() {
    return 20;
  }

  /** Resolved per scan, not captured once before the job was even assigned. */
  private BlockPos origin(RealPerson person) {
    if (!this.useStation) {
      return BlockPos.containing(person.getEyePosition()).below();
    }
    BlockPos station = LocationManager.getJobLocation(person);
    return station == BlockPos.ZERO ? BlockPos.containing(person.getEyePosition()).below() : station;
  }

  @Nullable
  private Item seedInHand(RealPerson person) {
    ArrayList<Item> seeds = new ArrayList<>(PLANTABLES.keySet());
    Collections.shuffle(seeds);
    for (Item seed : seeds) {
      if (person.hasItem(seed)) {
        return seed;
      }
    }
    return null;
  }

  @Nullable
  private BlockPos findUntilled(RealPerson person, BlockPos around) {
    for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; ++x) {
      for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; ++z) {
        BlockPos candidate = around.offset(x, -1, z);
        if (workable(person, candidate)) {
          return candidate;
        }
      }
    }
    return null;
  }

  private boolean workable(RealPerson person, BlockPos pos) {
    if (!person.level().getBlockState(pos.above()).isAir()) {
      return false; // something is already growing here
    }
    Block block = person.level().getBlockState(pos).getBlock();
    return block == Blocks.FARMLAND || TILLABLES.containsKey(block);
  }

}
