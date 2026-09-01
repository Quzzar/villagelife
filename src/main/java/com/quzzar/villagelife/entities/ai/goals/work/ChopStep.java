package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.ai.goals.ShortageWatch;
import com.quzzar.villagelife.village.BlockOwnership;
import com.quzzar.villagelife.village.LocationManager;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Felling a tree: a BREAK step that brings a whole tree down and takes what it
 * drops.
 *
 * A worker strikes one log for a chop's worth of ticks; when it gives, the
 * whole connected tree comes down at once (a bounded flood fill over its logs),
 * and every log lands in the worker's pack. Leaves are left to decay on their
 * own schedule, as natural leaves do once their tree is gone.
 *
 * <b>It only ever cuts real trees.</b> Two guards keep it off the village's own
 * timber and off anything a player built:
 * <ul>
 * <li>A candidate must have a <i>natural</i> canopy nearby, leaves whose
 * {@code persistent} flag is false. Player-placed leaves are persistent; a
 * building's timber has none of its own; so this excludes authored structures
 * far more reliably than mere leaf-proximity did.</li>
 * <li>{@link BlockOwnership#mayFell} vetoes any block a player placed, and any
 * block the village owns unless it has declared that column a tree slot (the
 * lumberjack's planted stand). The flood fill re-checks every log, so a wild
 * tree overhanging a roof drops its own wood and spares the building's.</li>
 * </ul>
 *
 * <b>A tree is cut from beside its trunk or from beneath it.</b> The worker
 * stands within arm's length of the trunk horizontally, and the lowest log has
 * to be within an axe's reach of their eyes: a player's block reach. Mangrove
 * trunks stand on stilts three to seven blocks over the mud, and measured from
 * the feet a guard walked under every one, gave up, stood down and was
 * eventually sent home. The woodland scan applies the same test in advance
 * from every standing spot beside a trunk, so a trunk nobody could reach is
 * left rather than walked to.
 */
public final class ChopStep implements BlockWorkStep {

  /** Ticks of chopping the first log takes before the tree comes down. */
  private static final int CHOP_TICKS = 100;

  /** Chance a felled log yields its stripped form instead. */
  private static final float STRIP_CHANCE = 0.3F;

  /** Chance per scan of planting a sapling on an empty stand. */
  private static final float REPLANT_CHANCE = 0.01F;

  /** Vertical reach of the bounded woodland scan around a worker's post. */
  private static final int WOODLAND_VERTICAL_RADIUS = 8;

  /** Beside the trunk or under it: arm's length across the ground, squared. */
  private static final double HORIZONTAL_REACH_SQR = 6.0D;

  /** How far from the eyes an axe reaches a log: a player's block reach. */
  private static final double AXE_REACH = 4.5D;

  /** How far below a trunk's lowest log to look for ground to stand on. */
  private static final int STAND_SEARCH_DEPTH = 6;

  /** Natural leaves this close mark a trunk as a living tree, not authored timber. */
  private static final int LEAF_RADIUS = 3;

  /** Most logs one fell removes: a ceiling for giant trees so the flood fill always ends. */
  private static final int TREE_LOG_CAP = 256;

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
   * @param woodlandChance    chance that a scan elects to fell one tree
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
      if (!(person.level() instanceof ServerLevel level)) {
        return null;
      }
      return findWoodlandTree(person, level, person.blockPosition());
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

    fellTree(person, target);
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

  /**
   * Beside the trunk or beneath it, with the log within an axe's reach of the
   * eyes. The loop's flat distance from the feet cannot say this: a stilted
   * mangrove's lowest log sits four blocks over the mud a worker stands on.
   */
  @Override
  public boolean inReach(RealPerson person, BlockPos log) {
    BlockPos feet = person.blockPosition();
    int dx = feet.getX() - log.getX();
    int dz = feet.getZ() - log.getZ();
    return dx * dx + dz * dz <= HORIZONTAL_REACH_SQR && axeReaches(person.getEyePosition(), log);
  }

  /** Whether an axe swung from these eyes reaches the log. */
  private static boolean axeReaches(Vec3 eyes, BlockPos log) {
    return eyes.distanceToSqr(Vec3.atCenterOf(log)) <= AXE_REACH * AXE_REACH;
  }

  /**
   * Brings a whole tree down from the struck log. A bounded flood fill walks the
   * connected logs; each one the village and players do not own is removed and
   * its drops taken, so a tree touching a building loses its own wood and not
   * the building's. One break sound plays for the lot.
   */
  private void fellTree(RealPerson person, BlockPos struck) {
    if (!(person.level() instanceof ServerLevel level)) {
      return;
    }
    List<ItemStack> haul = new ArrayList<>();
    BlockState soundFrom = null;
    for (BlockPos pos : collectTree(level, struck)) {
      BlockState state = level.getBlockState(pos);
      if (!state.is(BlockTags.LOGS_THAT_BURN) || !BlockOwnership.mayFell(level, pos)) {
        continue;
      }
      List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos),
          person, person.getMainHandItem());
      stripSome(person, state, drops);
      haul.addAll(drops);
      if (soundFrom == null) {
        soundFrom = state;
      }
      level.removeBlock(pos, false);
    }
    if (soundFrom != null) {
      level.playSound((Player) null, struck.getX(), struck.getY(), struck.getZ(),
          soundFrom.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F,
          person.getRandom().nextFloat() * 0.4F + 0.8F);
    }
    // Straight into the pack; HaulStep walks it to the workplace container once
    // the pack is worth a trip.
    person.addItems(haul);
    Villagelife.LOGGER.debug("[resource-flow] {} ({}) felled a tree at {}: {} log(s) into the pack",
        person.getName().getString(), person.getOccupation(), struck.toShortString(),
        haul.stream().mapToInt(ItemStack::getCount).sum());
  }

  /** A strippable log sometimes comes off already stripped. */
  private void stripSome(RealPerson person, BlockState state, List<ItemStack> drops) {
    BlockState stripped = AxeItem.getAxeStrippingState(state);
    if (stripped == null || person.getRandom().nextFloat() >= STRIP_CHANCE) {
      return;
    }
    for (int i = 0; i < drops.size(); i++) {
      if (drops.get(i).getItem() == state.getBlock().asItem()) {
        drops.set(i, new ItemStack(stripped.getBlock().asItem(), drops.get(i).getCount()));
      }
    }
  }

  /**
   * The connected logs of one tree, reached from the struck log by a bounded
   * breadth-first walk over all twenty-six neighbours so branches and leaning
   * trunks come with it. Capped so a giant tree cannot make the fill unbounded.
   */
  private List<BlockPos> collectTree(ServerLevel level, BlockPos start) {
    List<BlockPos> logs = new ArrayList<>();
    ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
    LongOpenHashSet visited = new LongOpenHashSet();
    frontier.add(start);
    visited.add(start.asLong());
    while (!frontier.isEmpty() && logs.size() < TREE_LOG_CAP) {
      BlockPos pos = frontier.poll();
      if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(BlockTags.LOGS_THAT_BURN)) {
        continue;
      }
      logs.add(pos);
      for (int dx = -1; dx <= 1; dx++) {
        for (int dy = -1; dy <= 1; dy++) {
          for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) {
              continue;
            }
            BlockPos next = pos.offset(dx, dy, dz);
            if (visited.add(next.asLong())) {
              frontier.add(next);
            }
          }
        }
      }
    }
    return logs;
  }

  /**
   * Reservoir-samples a fellable trunk with a natural canopy that a worker
   * could reach from the ground beside it, then returns its lowest connected
   * log so the worker walks to the base and the tree comes down from there.
   * Each trunk's verdict is kept for the scan, since every log on it asks.
   */
  @Nullable
  private BlockPos findWoodlandTree(RealPerson person, ServerLevel level, BlockPos around) {
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    Long2BooleanOpenHashMap reachable = new Long2BooleanOpenHashMap();
    BlockPos found = null;
    int seen = 0;
    for (int x = -this.woodlandRadius; x <= this.woodlandRadius; ++x) {
      for (int y = -WOODLAND_VERTICAL_RADIUS; y <= WOODLAND_VERTICAL_RADIUS; ++y) {
        for (int z = -this.woodlandRadius; z <= this.woodlandRadius; ++z) {
          cursor.setWithOffset(around, x, y, z);
          if (!level.hasChunkAt(cursor)
              || !level.getBlockState(cursor).is(BlockTags.LOGS_THAT_BURN)
              || !hasNaturalCanopy(level, cursor)
              || !BlockOwnership.mayFell(level, cursor)) {
            continue;
          }
          BlockPos base = lowestConnectedLog(level, cursor.immutable());
          long key = base.asLong();
          if (!reachable.containsKey(key)) {
            reachable.put(key, cuttableFromTheGround(level, person, base));
          }
          if (!reachable.get(key)) {
            continue;
          }
          if (person.getRandom().nextInt(++seen) == 0) {
            found = base;
          }
        }
      }
    }
    return found;
  }

  /**
   * Whether somewhere within arm's length of this trunk there is ground to
   * stand on from which the lowest log is within the axe's reach: beside it,
   * or beneath it in the mud a stilted mangrove stands over. The highest
   * standing spot in each column is the nearest one, so it is the one asked.
   */
  private static boolean cuttableFromTheGround(ServerLevel level, RealPerson person, BlockPos base) {
    BlockPos.MutableBlockPos feet = new BlockPos.MutableBlockPos();
    for (int dx = -2; dx <= 2; dx++) {
      for (int dz = -2; dz <= 2; dz++) {
        if (dx * dx + dz * dz > HORIZONTAL_REACH_SQR) {
          continue;
        }
        for (int y = base.getY() + 1; y >= base.getY() - STAND_SEARCH_DEPTH; y--) {
          feet.set(base.getX() + dx, y, base.getZ() + dz);
          if (!standable(level, feet)) {
            continue;
          }
          Vec3 eyes = new Vec3(feet.getX() + 0.5D, y + person.getEyeHeight(), feet.getZ() + 0.5D);
          if (axeReaches(eyes, base)) {
            return true;
          }
          break;
        }
      }
    }
    return false;
  }

  /**
   * Ground under the feet and room for a body above it. Unloaded chunks are
   * never paged in to answer; a spot in one is simply not standable.
   */
  private static boolean standable(ServerLevel level, BlockPos feet) {
    if (!level.hasChunkAt(feet)) {
      return false;
    }
    BlockPos ground = feet.below();
    BlockPos head = feet.above();
    return !level.getBlockState(ground).getCollisionShape(level, ground).isEmpty()
        && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
        && level.getBlockState(head).getCollisionShape(level, head).isEmpty();
  }

  /**
   * Whether naturally grown leaves ({@code persistent == false}) sit close to a
   * log. This is what tells a living tree from a stack of authored timber: a
   * player's placed leaves are persistent, and a building carries none.
   */
  private boolean hasNaturalCanopy(ServerLevel level, BlockPos log) {
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int x = -LEAF_RADIUS; x <= LEAF_RADIUS; ++x) {
      for (int y = -LEAF_RADIUS; y <= LEAF_RADIUS; ++y) {
        for (int z = -LEAF_RADIUS; z <= LEAF_RADIUS; ++z) {
          cursor.setWithOffset(log, x, y, z);
          if (!level.hasChunkAt(cursor)) {
            continue;
          }
          BlockState state = level.getBlockState(cursor);
          if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT)
              && !state.getValue(LeavesBlock.PERSISTENT)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private BlockPos lowestConnectedLog(ServerLevel level, BlockPos log) {
    BlockPos lowest = log;
    while (lowest.getY() > level.getMinBuildHeight()
        && level.getBlockState(lowest.below()).is(BlockTags.LOGS_THAT_BURN)) {
      lowest = lowest.below();
    }
    return lowest;
  }

}
