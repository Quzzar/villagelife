package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.Arrays;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Clearing the wild growth around the farm: a BREAK step, and the first link in
 * the farmer's idle chain - clear brush, compost it, shelve the bone meal,
 * feed it back to the crops. Each link is its own step, so any of them also
 * stands alone.
 *
 * A farmer with nothing ripe and nothing to sow used to just wander. Now they
 * tidy: long grass, ferns and flowers within the farm's reach come out of the
 * ground and into the pack, bound for the composter.
 *
 * The worker pockets the plant itself, as shears would take it, rather than
 * rolling its loot table - grass usually drops nothing and only sometimes a
 * seed, which would starve the composter this step exists to feed.
 */
public final class ClearBrushStep implements BlockWorkStep {

  /** How far from the station the brush is the farmer's business. */
  private static final int WORK_RADIUS = 12;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    if (person.isInventoryFull()) {
      return null; // nowhere to put it; the haul or the composter goes first
    }
    BlockPos station = LocationManager.getJobLocation(person);
    if (station.equals(BlockPos.ZERO)) {
      return null;
    }
    return findBrush(person, station);
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    BlockState state = person.level().getBlockState(target);
    if (!brush(state)) {
      return false; // something beat us to it
    }
    Block block = state.getBlock();
    person.level().playSound((Player) null, target.getX(), target.getY(), target.getZ(),
        state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F,
        person.getRandom().nextFloat() * 0.4F + 0.8F);
    // Upper half first, quietly: dropping the lower half from under it would
    // pop the top with its own loot and litter seeds behind a tidying pass.
    BlockPos above = target.above();
    if (block instanceof DoublePlantBlock && person.level().getBlockState(above).is(block)) {
      person.level().removeBlock(above, false);
    }
    person.level().removeBlock(target, false);
    person.addItems(Arrays.asList(new ItemStack(block.asItem())));
    return false; // one plant per approach; select finds the next
  }

  @Override
  public String describe() {
    return "the brush that wants clearing";
  }

  /** An idle tidy-up scans wider than the field work, so it looks less often. */
  @Override
  public int selectEveryTicks() {
    return 40;
  }

  /**
   * Wild growth worth pulling: grass and ferns (single or double height) and
   * flowers, but only kinds the composter will take, so what is gathered can
   * always become bone meal. Crops, saplings and dead bushes are none of ours.
   */
  static boolean brush(BlockState state) {
    Block block = state.getBlock();
    if (block instanceof DoublePlantBlock) {
      // Only the rooted half is a target; taking it takes the whole plant.
      if (state.getValue(DoublePlantBlock.HALF) != DoubleBlockHalf.LOWER) {
        return false;
      }
    } else if (!(block instanceof TallGrassBlock || block instanceof FlowerBlock)) {
      return false;
    }
    return compostable(new ItemStack(block.asItem()));
  }

  /** Whether the pack is carrying anything this chain treats as brush. */
  static boolean carryingBrush(RealPerson person) {
    for (int slot = 0; slot < person.personMainInv.getContainerSize(); slot++) {
      if (brushItem(person.personMainInv.getItem(slot))) {
        return true;
      }
    }
    return false;
  }

  /**
   * A pack item this chain treats as brush: a plant {@link #brush} would have
   * pulled. Deliberately NOT any-compostable - the farmer's wheat and seeds
   * compost too, and composting the harvest is not tidying.
   */
  static boolean brushItem(ItemStack stack) {
    if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem planted)) {
      return false;
    }
    Block block = planted.getBlock();
    return (block instanceof TallGrassBlock || block instanceof DoublePlantBlock
        || block instanceof FlowerBlock) && compostable(stack);
  }

  /** Reads the compost chance data map, so modded plants join in for free. */
  private static boolean compostable(ItemStack stack) {
    return ComposterBlock.getValue(stack) > 0.0F;
  }

  /** Reservoir-samples one brush block, so clearing spreads rather than mows a line. */
  @Nullable
  private BlockPos findBrush(RealPerson person, BlockPos around) {
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    BlockPos found = null;
    int seen = 0;
    for (int x = -WORK_RADIUS; x <= WORK_RADIUS; ++x) {
      for (int y = -4; y <= 4; ++y) {
        for (int z = -WORK_RADIUS; z <= WORK_RADIUS; ++z) {
          cursor.setWithOffset(around, x, y, z);
          if (!brush(person.level().getBlockState(cursor))) {
            continue;
          }
          if (person.level().random.nextInt(++seen) == 0) {
            found = cursor.immutable();
          }
        }
      }
    }
    return found;
  }

}
