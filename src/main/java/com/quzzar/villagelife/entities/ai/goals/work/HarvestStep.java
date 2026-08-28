package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.ai.goals.ShortageWatch;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Taking a ripe crop: a BREAK step that replants what it takes.
 *
 * Berry bushes are picked rather than broken - the bush stays and its age is
 * wound back - and a crop block is replanted at stage one, so a farmer does not
 * strip their own field to bare earth. Melons and pumpkins are neither: they
 * regrow from the stem that produced them, so they simply come away.
 */
public final class HarvestStep implements BlockWorkStep {

  private static final int SEARCH_RADIUS = 2;

  private final boolean useStation;
  private final ShortageWatch dry = new ShortageWatch();

  public HarvestStep(boolean useStation) {
    this.useStation = useStation;
  }

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    BlockPos ripe = findRipe(person, origin(person));
    if (ripe != null) {
      this.dry.foundWork();
      return ripe;
    }
    // A field with nothing ripe is usually just crops still growing; only a
    // sustained empty stretch is reported, once, as a genuine food shortage.
    this.dry.wentDry(person, Items.WHEAT, 1, "There is nothing ripe in the field to harvest yet.");
    return null;
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    BlockState state = person.level().getBlockState(target);

    if (state.getBlock() instanceof SweetBerryBushBlock) {
      int picked = person.getRandom().nextInt(1, 4);
      person.level().playSound((Player) null, target, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES,
          SoundSource.BLOCKS, 1.0F, 0.8F + person.getRandom().nextFloat() * 0.4F);
      person.level().setBlock(target, state.setValue(SweetBerryBushBlock.AGE, Integer.valueOf(1)), 2);
      person.addItems(Arrays.asList(new ItemStack(Items.SWEET_BERRIES, picked)));
      return false;
    }

    List<ItemStack> drops = Block.getDrops(state, (ServerLevel) person.level(), target,
        person.level().getBlockEntity(target), person, person.getMainHandItem());
    person.level().playSound((Player) null, target.getX(), target.getY(), target.getZ(),
        state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F,
        person.getRandom().nextFloat() * 0.4F + 0.8F);
    person.level().removeBlock(target, false);
    person.addItems(drops);

    // Put it back at stage one so the field keeps producing. Stem-grown things
    // (melon, pumpkin) are deliberately not replanted: their stem is still there.
    if (state.getBlock() instanceof CropBlock) {
      person.level().setBlockAndUpdate(target, state.getBlock().defaultBlockState());
    }
    return false;
  }

  @Override
  public String describe() {
    return "the field";
  }

  @Override
  public int actEveryTicks() {
    return 20;
  }

  /** Resolved per scan, not captured once before the job was even assigned. */
  private BlockPos origin(RealPerson person) {
    if (!this.useStation) {
      return BlockPos.containing(person.getEyePosition());
    }
    BlockPos station = LocationManager.getJobLocation(person);
    return station == BlockPos.ZERO ? BlockPos.containing(person.getEyePosition()) : station;
  }

  /** Reservoir-samples one ripe crop, so a farmer does not always work the same corner. */
  @Nullable
  private BlockPos findRipe(RealPerson person, BlockPos around) {
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    BlockPos found = null;
    int seen = 0;
    for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; ++x) {
      for (int y = -SEARCH_RADIUS; y <= SEARCH_RADIUS; ++y) {
        for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; ++z) {
          cursor.setWithOffset(around, x, y, z);
          if (!ripe(person, cursor)) {
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

  private boolean ripe(RealPerson person, BlockPos pos) {
    BlockState state = person.level().getBlockState(pos);
    Block block = state.getBlock();
    if (block instanceof CropBlock crop) {
      return crop.isMaxAge(state);
    }
    return block == Blocks.MELON || block == Blocks.PUMPKIN || block instanceof SweetBerryBushBlock;
  }

}
