package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Felling a log at the lumberjack's stand: a BREAK step, which destroys a block
 * and takes what it drops.
 *
 * <b>This fixes an item duplication.</b> The goal it replaces never removed the
 * log. It accumulated break progress for a hundred ticks, showed the cracking
 * overlay, collected {@code Block.getDrops} into the worker's pack - and left
 * the block standing, because {@code destroyBlockProgress} only draws cracks.
 * The goal then ended on its own break timer, {@code canUse} found the same log
 * still there, and it started again. One log, chopped forever, printing timber
 * into the village stores for as long as the lumberjack lived. It is why no
 * village ever ran short of wood.
 *
 * Removing the log also revives code that was already written and could never
 * run: the replant branch fires only when the stand is air over dirt, which it
 * never became. So the intended loop - fell it, plant a sapling, wait for it to
 * grow back - is what happens now, and wood is finite.
 */
public final class ChopStep implements WorkStep {

  /** Ticks of chopping a log takes. */
  private static final int CHOP_TICKS = 100;

  /** Chance a felled log yields its stripped form instead. */
  private static final float STRIP_CHANCE = 0.3F;

  /** Chance per scan of planting a sapling on an empty stand. */
  private static final float REPLANT_CHANCE = 0.01F;

  private BlockPos chopping;
  private int chopTime;
  private int lastProgress = -1;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    BlockPos stand = LocationManager.getJobLocation(person);
    if (stand == BlockPos.ZERO) {
      return null;
    }
    BlockState state = person.level().getBlockState(stand);

    // An empty stand over dirt is a felled tree waiting to be replanted. This
    // branch existed before and could never fire, because nothing ever cleared
    // the stand.
    if (state.isAir() && person.level().getBlockState(stand.below()).is(BlockTags.DIRT)) {
      if (person.getRandom().nextFloat() < REPLANT_CHANCE) {
        person.level().setBlock(stand, Blocks.OAK_SAPLING.defaultBlockState(), 2);
      }
      return null;
    }
    return state.is(BlockTags.LOGS_THAT_BURN) ? stand : null;
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    if (!target.equals(this.chopping)) {
      this.chopping = target;
      this.chopTime = 0;
      this.lastProgress = -1;
    }

    BlockState state = person.level().getBlockState(target);
    if (!state.is(BlockTags.LOGS_THAT_BURN)) {
      return false; // somebody else got it
    }

    this.chopTime++;
    int progress = (int) ((float) this.chopTime / CHOP_TICKS * 10.0F);
    if (progress != this.lastProgress) {
      person.level().destroyBlockProgress(person.getId(), target, progress);
      this.lastProgress = progress;
    }
    if (this.chopTime < CHOP_TICKS) {
      return true;
    }

    fell(person, target, state);
    return false;
  }

  @Override
  public void released(RealPerson person, BlockPos target) {
    person.level().destroyBlockProgress(person.getId(), target, -1);
    this.chopping = null;
    this.chopTime = 0;
    this.lastProgress = -1;
  }

  @Override
  public String describe() {
    return "the trees";
  }

  /** Chopping is timed in ticks, so the act runs on every one of them. */
  @Override
  public int actEveryTicks() {
    return 1;
  }

  @Override
  public double reachSqr(RealPerson person) {
    return 6.0D; // as the goal this replaces used
  }

  private void fell(RealPerson person, BlockPos target, BlockState state) {
    if (person.level().isClientSide) {
      return;
    }
    List<ItemStack> drops = Block.getDrops(state, (ServerLevel) person.level(), target,
        person.level().getBlockEntity(target), person, person.getMainHandItem());

    // A strippable log sometimes comes off the stump already stripped.
    BlockState stripped = AxeItem.getAxeStrippingState(state);
    if (stripped != null && person.getRandom().nextFloat() < STRIP_CHANCE) {
      for (int i = 0; i < drops.size(); i++) {
        if (drops.get(i).getItem() == state.getBlock().asItem()) {
          drops.set(i, new ItemStack(stripped.getBlock().asItem(), drops.get(i).getCount()));
        }
      }
    }

    person.level().playSound((Player) null, target.getX(), target.getY(), target.getZ(),
        state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F,
        person.getRandom().nextFloat() * 0.4F + 0.8F);

    // The log actually comes down. Straight into the pack; DepositHaulGoal
    // walks it to the workplace container once the pack is worth a trip.
    person.level().removeBlock(target, false);
    person.addItems(drops);
  }

}
