package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.ai.goals.ShortageWatch;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
public final class ChopStep implements BlockWorkStep {

  /** Ticks of chopping a log takes. */
  private static final int CHOP_TICKS = 100;

  /** Chance a felled log yields its stripped form instead. */
  private static final float STRIP_CHANCE = 0.3F;

  /** Chance per scan of planting a sapling on an empty stand. */
  private static final float REPLANT_CHANCE = 0.01F;

  /** Vertical reach of the bounded woodland scan around a worker's post. */
  private static final int WOODLAND_VERTICAL_RADIUS = 8;

  /** Leaves this close distinguish a tree trunk from a building's timber. */
  private static final int LEAF_RADIUS = 3;

  private final int woodlandRadius;
  private final float woodlandChance;
  private final int woodlandScanTicks;

  private final ShortageWatch dry = new ShortageWatch();

  private BlockPos chopping;
  private int chopTime;
  private int lastProgress = -1;

  /** The lumberjack's planted stand: deterministic, renewable primary work. */
  public ChopStep() {
    this(0, 0.0F, 20);
  }

  /**
   * A bounded idle woodland pass shared by lumberjacks and guards.
   *
   * @param woodlandRadius    horizontal distance from where the worker stands
   * @param woodlandChance    chance that a scan elects to chop one tree
   * @param woodlandScanTicks ticks between scans while idle
   */
  public ChopStep(int woodlandRadius, float woodlandChance, int woodlandScanTicks) {
    this.woodlandRadius = woodlandRadius;
    this.woodlandChance = woodlandChance;
    this.woodlandScanTicks = woodlandScanTicks;
  }

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    if (this.woodlandRadius > 0) {
      // The idle pass sweeps around wherever the worker is standing, not a
      // fixed post, so as a guard patrols or a lumberjack roams they clear the
      // natural trees ringing the village rather than only those at one spot.
      if (person.isInventoryFull() || person.getRandom().nextFloat() >= this.woodlandChance) {
        return null;
      }
      return findWoodlandLog(person, person.blockPosition());
    }
    BlockPos stand = LocationManager.getJobLocation(person);
    if (stand == BlockPos.ZERO) {
      return null; // no post assigned yet: a misconfiguration, not a wood shortage
    }
    BlockState state = person.level().getBlockState(stand);

    if (state.is(BlockTags.LOGS_THAT_BURN)) {
      this.dry.foundWork();
      return stand;
    }

    // An empty stand over dirt is a felled tree waiting to be replanted. This
    // branch existed before and could never fire, because nothing ever cleared
    // the stand.
    if (state.isAir() && person.level().getBlockState(stand.below()).is(BlockTags.DIRT)) {
      if (person.getRandom().nextFloat() < REPLANT_CHANCE) {
        person.level().setBlock(stand, Blocks.OAK_SAPLING.defaultBlockState(), 2);
      }
    }

    // Nothing standing to fell: felled and regrowing, or a stand that will not
    // regrow one. Most of these lulls are just the sapling coming back, so only a
    // sustained dry stretch is reported, once, as a genuine wood shortage.
    this.dry.wentDry(person, Items.OAK_LOG, 1,
        "The trees at my stand are felled; I have no wood to cut until they grow back.");
    return null;
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

  @Override
  public int selectEveryTicks() {
    return this.woodlandScanTicks;
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

    // The log actually comes down. Straight into the pack; HaulStep walks it to
    // the workplace container once the pack is worth a trip.
    person.level().removeBlock(target, false);
    person.addItems(drops);
  }

  /**
   * Reservoir-samples a natural trunk, then returns its lowest connected log.
   * Requiring nearby leaves keeps authored buildings and log piles out of the
   * pass; descending the trunk makes a tree come down from the base upward.
   */
  @Nullable
  private BlockPos findWoodlandLog(RealPerson person, BlockPos around) {
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    BlockPos found = null;
    int seen = 0;
    for (int x = -this.woodlandRadius; x <= this.woodlandRadius; ++x) {
      for (int y = -WOODLAND_VERTICAL_RADIUS; y <= WOODLAND_VERTICAL_RADIUS; ++y) {
        for (int z = -this.woodlandRadius; z <= this.woodlandRadius; ++z) {
          cursor.setWithOffset(around, x, y, z);
          if (!person.level().hasChunkAt(cursor)
              || !person.level().getBlockState(cursor).is(BlockTags.LOGS_THAT_BURN)
              || !hasLeavesNearby(person, cursor)) {
            continue;
          }
          BlockPos lowest = lowestConnectedLog(person, cursor);
          if (person.getRandom().nextInt(++seen) == 0) {
            found = lowest;
          }
        }
      }
    }
    return found;
  }

  private boolean hasLeavesNearby(RealPerson person, BlockPos log) {
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int x = -LEAF_RADIUS; x <= LEAF_RADIUS; ++x) {
      for (int y = -LEAF_RADIUS; y <= LEAF_RADIUS; ++y) {
        for (int z = -LEAF_RADIUS; z <= LEAF_RADIUS; ++z) {
          cursor.setWithOffset(log, x, y, z);
          if (person.level().hasChunkAt(cursor)
              && person.level().getBlockState(cursor).is(BlockTags.LEAVES)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private BlockPos lowestConnectedLog(RealPerson person, BlockPos log) {
    BlockPos lowest = log;
    while (lowest.getY() > person.level().getMinBuildHeight()
        && person.level().getBlockState(lowest.below()).is(BlockTags.LOGS_THAT_BURN)) {
      lowest = lowest.below();
    }
    return lowest;
  }

}
