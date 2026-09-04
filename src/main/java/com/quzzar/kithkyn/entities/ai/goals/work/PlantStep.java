package com.quzzar.kithkyn.entities.ai.goals.work;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MangrovePropaguleBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Replanting the lumberjack's stand: a PLACE step, which puts a sapling from
 * the pack on the stump of the tree just felled.
 *
 * The stand is the station itself, the block the lodge's authored sapling
 * stands in, over the dirt the lodge carries for it; the first tree grows from
 * that sapling on Minecraft's own schedule. Once a tree is down that block is
 * bare, and this step sets a sapling there of whatever kind the pack holds:
 * one the felled canopy dropped, one picked up in the woods, or one the
 * bedtime restock drew from the stores. Any sapling will do, so a plains lodge
 * whose first oak came down grows a spruce if that is what the lumberjack has;
 * wood is wood. The tree then grows on Minecraft's own schedule, fed by
 * {@link BonemealStep} while there is bone meal in the pack, and
 * {@link ChopStep} takes it when it is a tree again. The planted sapling is
 * deliberately nobody's (docs/block-ownership.md), so that fell is an ordinary
 * one.
 *
 * This replaces a branch of the chop that rolled one chance in a hundred each
 * scan of conjuring an oak sapling from nothing. It never fired, since the
 * station was four blocks from the tree, and it would have been the one thing
 * a lumberjack did that was not physical.
 */
public final class PlantStep implements BlockWorkStep {

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    if (!(person.level() instanceof ServerLevel level)) {
      return null;
    }
    BlockPos post = LocationManager.getJobLocation(person);
    if (post == BlockPos.ZERO || !isBare(level.getBlockState(post))) {
      return null;
    }
    Item sapling = saplingIn(person);
    if (sapling == null) {
      return null; // nothing to plant: the chop's dry report says so
    }
    // Only ground a sapling can live on: the dirt the lodge carries under the
    // stand, or whatever a player swapped in for it.
    return saplingState(sapling).canSurvive(level, post) ? post : null;
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    if (!(person.level() instanceof ServerLevel level) || !isBare(level.getBlockState(target))) {
      return false; // something grew or was set there on the way over
    }
    Item sapling = saplingIn(person);
    if (sapling == null) {
      return false; // lost between selecting and arriving
    }
    BlockState planted = saplingState(sapling);
    if (!planted.canSurvive(level, target) || person.removeItem(sapling, 1).getCount() != 1) {
      return false;
    }
    level.setBlockAndUpdate(target, planted);
    level.playSound((Player) null, target.getX(), target.getY(), target.getZ(),
        planted.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F,
        person.getRandom().nextFloat() * 0.4F + 0.8F);
    Kithkyn.LOGGER.debug("[resource-flow] {} ({}) planted a {} on their stand at {}",
        person.getName().getString(), person.getOccupation(),
        sapling.getDescription().getString(), target.toShortString());
    return false; // one sapling per stand
  }

  @Override
  public String describe() {
    return "my stand, to replant it";
  }

  @Override
  public String activity() {
    return "replanting the stand";
  }

  /** Setting the sapling is one action, taken on arrival. */
  @Override
  public int actEveryTicks() {
    return 1;
  }

  /**
   * A stump with room for a sapling: air, or something a sapling may replace,
   * such as the snow that settles on a snowy lodge's dirt overnight. A log or
   * a growing sapling is not bare.
   */
  static boolean isBare(BlockState state) {
    return state.isAir() || state.canBeReplaced();
  }

  /**
   * A sapling of any kind the worker carries, or null. Shared with the chop's
   * dry report and the bedtime restock, so all three agree on what a sapling
   * is ({@link #isSapling}).
   */
  @Nullable
  static Item saplingIn(RealPerson person) {
    Container pack = person.personMainInv;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      if (!stack.isEmpty() && isSapling(stack.getItem())) {
        return stack.getItem();
      }
    }
    return null;
  }

  /**
   * Whether this item sets down as a sapling that grows a trunk on the stand,
   * which is what the loop needs: a block {@link BonemealStep} will feed, whose
   * tree stands where it was planted. An azalea bush is tagged as a sapling but
   * grows only under bone meal that step never gives it; a mangrove propagule
   * is a sapling block, but what it grows puts roots where the trunk should be,
   * and roots are not logs, so the stand would never be bare again. Both stay
   * in the pack.
   */
  public static boolean isSapling(Item item) {
    Block block = Block.byItem(item);
    return block instanceof SaplingBlock && !(block instanceof MangrovePropaguleBlock);
  }

  /** How many saplings of any kind the worker carries. */
  public static int saplingsHeld(RealPerson person) {
    Container pack = person.personMainInv;
    int held = 0;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      if (isSapling(stack.getItem())) {
        held += stack.getCount();
      }
    }
    return held;
  }

  private static BlockState saplingState(Item sapling) {
    return Block.byItem(sapling).defaultBlockState();
  }

}
