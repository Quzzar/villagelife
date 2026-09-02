package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.TreeFelling;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;

/**
 * A roaming wanderer's timber: a tree by the road, brought down whole and
 * pocketed (docs/population-and-labor.md, "The road"). A wanderer with no axe
 * pounds the trunk bare-handed, slowly, the way a player with nothing in hand
 * does; one with an axe is quicker. The wood is what the road's tools are made
 * of, so the moment a tree is down the wanderer looks at their empty hand and
 * makes an axe out of a log ({@link RealPerson#toolUpFromPack}), and a pack
 * that already holds a few logs walks past the next tree.
 *
 * <p>The felling itself is the shared {@link TreeFelling}: only a living tree
 * with a natural canopy that nobody placed comes down, so the village's own
 * timber and a player's cabin are safe from a wanderer passing through. The
 * scan is around the wanderer, wherever the day's heading has brought them,
 * and the tree's base is what they walk to and strike.
 */
public final class ForageChopStep implements BlockWorkStep {
  /** Logs a wanderer keeps: a reserve of camp fires and an axe (Aaron: sixteen). Past it, trees are left standing. */
  private static final int LOG_TARGET = 16;
  /** How far to either side of the road a tree is worth the stop. */
  private static final int RANGE = 10;
  private static final int VERTICAL_RANGE = 6;
  /** Beside the trunk, squared: arm's length with a little slack for a raised base. */
  private static final double REACH_SQR = 6.0D;
  /** Acts of pounding before a trunk gives, bare-handed and with an axe. */
  private static final int ACTS_BY_HAND = 12;
  private static final int ACTS_WITH_AXE = 5;

  @Nullable
  private BlockPos chopping;
  private int chopTime;
  private int lastProgress = -1;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    if (!person.isRoamingWanderer() || person.isInventoryFull()
        || PackLogistics.carriedIn(person, ItemTags.LOGS) >= LOG_TARGET) {
      return null;
    }
    if (!(person.level() instanceof ServerLevel level)) {
      return null;
    }
    BlockPos here = person.blockPosition();
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    BlockPos nearestLog = null;
    double nearest = Double.MAX_VALUE;
    for (int x = -RANGE; x <= RANGE; x++) {
      for (int y = -VERTICAL_RANGE; y <= VERTICAL_RANGE; y++) {
        for (int z = -RANGE; z <= RANGE; z++) {
          cursor.setWithOffset(here, x, y, z);
          if (!TreeFelling.isFellableLog(level, cursor)) {
            continue;
          }
          double distance = cursor.distSqr(here);
          if (distance < nearest) {
            nearest = distance;
            nearestLog = cursor.immutable();
          }
        }
      }
    }
    if (nearestLog == null) {
      return null;
    }
    // The base of the tree is what a feller strikes; the flood fill finds it
    // from any log, which a straight walk down does not on a mangrove.
    BlockPos base = nearestLog;
    for (BlockPos log : TreeFelling.treeLogs(level, nearestLog)) {
      if (log.getY() < base.getY()) {
        base = log;
      }
    }
    return base;
  }

  @Override
  public boolean act(RealPerson person, BlockPos base) {
    if (!(person.level() instanceof ServerLevel level)) {
      return false;
    }
    if (!level.getBlockState(base).is(BlockTags.LOGS_THAT_BURN)) {
      return false; // somebody else got it
    }
    if (!base.equals(this.chopping)) {
      this.chopping = base;
      this.chopTime = 0;
      this.lastProgress = -1;
    }
    boolean axe = person.getMainHandItem().getItem() instanceof AxeItem;
    int acts = axe ? ACTS_WITH_AXE : ACTS_BY_HAND;
    this.chopTime++;
    int progress = this.chopTime * 10 / acts;
    if (progress != this.lastProgress) {
      level.destroyBlockProgress(person.getId(), base, progress);
      this.lastProgress = progress;
    }
    if (this.chopTime < acts) {
      return true;
    }
    List<ItemStack> haul = TreeFelling.fell(level, base, person, person.getMainHandItem());
    int logs = haul.stream().mapToInt(ItemStack::getCount).sum();
    person.addItems(haul);
    Villagelife.LOGGER.info("[road] '{}' brought down a tree at {} {}: {} log(s) into the pack",
        person.getFullName(), base.toShortString(), axe ? "with an axe" : "bare-handed", logs);
    person.toolUpFromPack();
    return false;
  }

  @Override
  public void released(RealPerson person, BlockPos base) {
    person.level().destroyBlockProgress(person.getId(), base, -1);
    this.chopping = null;
    this.chopTime = 0;
    this.lastProgress = -1;
  }

  @Override
  public String describe() {
    return "a tree by the road";
  }

  @Override
  public String activity() {
    return "cutting wood for the road";
  }

  @Override
  public double reachSqr(RealPerson person) {
    return REACH_SQR;
  }

  /** Every second or so: a roadside scan is a few thousand block reads, and the road is long. */
  @Override
  public int selectEveryTicks() {
    return 40;
  }
}
