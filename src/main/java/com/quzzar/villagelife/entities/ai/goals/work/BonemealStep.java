package com.quzzar.villagelife.entities.ai.goals.work;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Feeding bone meal to something that is still growing: an APPLY step, which
 * consumes an item to change a target that survives.
 *
 * The first job moved onto {@link WorkLoopGoal}, chosen because it was broken.
 * The goal this replaces set no movement flag, so it never competed with
 * anything; never navigated, so it fed whatever happened to be within two
 * blocks of the STATION from wherever the villager was standing; kept running
 * on a {@code canContinueToUse} that asked only whether the villager had been
 * interrupted, so it neither noticed running out of bone meal nor noticed
 * having nothing to feed; and tried to end itself by calling a {@code stop()}
 * it never overrode, which reached an empty method. A farmer who picked up one
 * piece of bone meal ran that goal until nightfall and fertilised nothing
 * unless luck put a crop in range.
 *
 * Every one of those was in the surround, and none of them can be expressed
 * here. What is left is the act, which was always correct.
 */
public final class BonemealStep implements BlockWorkStep {

  /**
   * How far around the station to look. The old goal searched two blocks and
   * never walked, which is a box barely larger than the villager - so the work
   * only happened by coincidence. Eight covers a plot the worker can now
   * actually walk across.
   */
  private static final int WORK_RADIUS = 8;

  private final boolean useStation;

  public BonemealStep(boolean useStation) {
    this.useStation = useStation;
  }

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    if (!person.hasItem(Items.BONE_MEAL)) {
      return null; // nothing to feed it with, so there is no work
    }
    return findGrowable(person, origin(person));
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    ItemStack item = person.removeItem(Items.BONE_MEAL, 1);
    if (item.getCount() == 1 && BoneMealItem.growCrop(item, person.level(), target)) {
      person.level().levelEvent(1505, target, 0);
    }
    // One application per target either way: if it grew, it may now be ripe and
    // belong to the harvester; if it did not, feeding it again will not help.
    return false;
  }

  @Override
  public String describe() {
    return "the crops that need feeding";
  }

  @Override
  public String activity() {
    return "feeding bone meal to what is still growing";
  }

  /**
   * Resolved every time rather than once at construction. The goal this
   * replaces read the job location in its constructor, so a worker assigned
   * their station afterwards carried a zeroed one for the rest of their life.
   */
  private BlockPos origin(RealPerson person) {
    if (!this.useStation) {
      return BlockPos.containing(person.getEyePosition());
    }
    BlockPos station = LocationManager.getJobLocation(person);
    return station == BlockPos.ZERO ? BlockPos.containing(person.getEyePosition()) : station;
  }

  /**
   * Reservoir-samples one growable block, so a worker does not always pick the
   * same corner. Package-shared: the farmer's shelf steps ask the same question
   * ("does anything here still want feeding?") to decide fetch versus stash.
   */
  @Nullable
  static BlockPos findGrowable(RealPerson person, BlockPos around) {
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    BlockPos found = null;
    int seen = 0;
    for (int x = -WORK_RADIUS; x <= WORK_RADIUS; ++x) {
      for (int y = -2; y <= 2; ++y) {
        for (int z = -WORK_RADIUS; z <= WORK_RADIUS; ++z) {
          cursor.setWithOffset(around, x, y, z);
          if (!growable(person, cursor)) {
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

  private static boolean growable(RealPerson person, BlockPos pos) {
    BlockState state = person.level().getBlockState(pos);
    Block block = state.getBlock();
    if (block instanceof CropBlock crop) {
      return !crop.isMaxAge(state);
    }
    return block instanceof SaplingBlock;
  }

}
