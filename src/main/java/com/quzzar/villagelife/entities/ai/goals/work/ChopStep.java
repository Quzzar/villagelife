package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.ai.goals.ShortageWatch;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.TreeFelling;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Felling a tree: a BREAK step that brings a whole tree down and takes what it
 * drops.
 *
 * A worker strikes one log for a chop's worth of ticks; when it gives, the
 * whole connected tree comes down at once through {@link TreeFelling}, and
 * every log lands in the worker's pack. Leaves are left to decay on their own
 * schedule, as natural leaves do once their tree is gone.
 *
 * <b>It only ever cuts real trees.</b> {@link TreeFelling#isFellableLog} holds
 * the two guards, a natural canopy nearby and nobody's ownership, that keep the
 * axe off the village's own timber and off anything a player built; the flood
 * fill re-checks every log, so a wild tree overhanging a roof drops its own
 * wood and spares the building's.
 *
 * <b>A tree is cut from beside its trunk or from beneath it.</b> The worker
 * walks to a standing spot beside the tree's base, within arm's length of it
 * horizontally, from which the base is within an axe's reach of their eyes: a
 * player's block reach. Mangrove trunks stand on stilts three to seven blocks
 * over the mud, and measured from the feet a guard walked under every one,
 * gave up, stood down and was eventually sent home. The base is the lowest
 * log of the whole connected tree ({@link TreeFelling#treeLogs}), because a
 * mangrove is all branches and walking straight down from a scanned log stops
 * at one in the canopy. The standing spot is one the navigator confirms a path
 * to before the tree is offered at all, so a trunk nobody could walk beside is
 * left rather than walked to; a spot on top of the leaves does not count.
 */
public final class ChopStep implements WorkStep<ChopStep.Cut> {

  /** A tree to fell: the log to strike, and the ground to strike it from. */
  public record Cut(BlockPos log, BlockPos stand) {
  }

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

  /** How far below a trunk's base to look for ground to stand on. */
  private static final int STAND_SEARCH_DEPTH = 6;

  /** Paths the navigator is asked for per tree before it is called unreachable. */
  private static final int PATHS_PER_TREE = 4;

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
  public Cut select(RealPerson person) {
    if (!(person.level() instanceof ServerLevel level)) {
      return null;
    }
    if (this.woodlandRadius > 0) {
      // The idle pass sweeps around wherever the worker is standing, not a
      // fixed post, so as a guard patrols or a lumberjack roams they clear the
      // natural trees ringing the village rather than only those at one spot.
      if (person.isInventoryFull() || person.getRandom().nextFloat() >= this.woodlandChance) {
        return null;
      }
      return findWoodlandTree(person, level, person.blockPosition());
    }
    BlockPos post = LocationManager.getJobLocation(person);
    if (post == BlockPos.ZERO) {
      return null; // no post assigned yet: a misconfiguration, not a wood shortage
    }
    BlockState state = level.getBlockState(post);

    if (state.is(BlockTags.LOGS_THAT_BURN)) {
      this.dry.foundWork();
      BlockPos stand = standBeside(person, level, post);
      return new Cut(post, stand == null ? post : stand);
    }

    // An empty stand over dirt is a felled tree waiting to be replanted. This
    // branch existed before and could never fire, because nothing ever cleared
    // the stand.
    if (state.isAir() && level.getBlockState(post.below()).is(BlockTags.DIRT)) {
      if (person.getRandom().nextFloat() < REPLANT_CHANCE) {
        level.setBlock(post, Blocks.OAK_SAPLING.defaultBlockState(), 2);
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
  public boolean act(RealPerson person, Cut cut) {
    BlockPos target = cut.log();
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

  /** The worker walks to the ground beside the tree, not to the log. */
  @Override
  public BlockPos positionOf(Cut cut) {
    return cut.stand();
  }

  @Override
  public void released(RealPerson person, Cut cut) {
    person.level().destroyBlockProgress(person.getId(), cut.log(), -1);
    this.chopping = null;
    this.chopTime = 0;
    this.lastProgress = -1;
  }

  @Override
  public String describe() {
    return "the trees";
  }

  @Override
  public String activity() {
    return "felling trees";
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
  public boolean inReach(RealPerson person, Cut cut) {
    return axeReachesFrom(person.blockPosition(), person.getEyePosition(), cut.log());
  }

  /** Whether an axe swung from these eyes, over these feet, reaches the log. */
  private static boolean axeReachesFrom(BlockPos feet, Vec3 eyes, BlockPos log) {
    int dx = feet.getX() - log.getX();
    int dz = feet.getZ() - log.getZ();
    return dx * dx + dz * dz <= HORIZONTAL_REACH_SQR
        && eyes.distanceToSqr(Vec3.atCenterOf(log)) <= AXE_REACH * AXE_REACH;
  }

  /** The eyes of a worker standing with their feet in this block. */
  private static Vec3 eyesAt(BlockPos feet, RealPerson person) {
    return new Vec3(feet.getX() + 0.5D, feet.getY() + person.getEyeHeight(), feet.getZ() + 0.5D);
  }

  /**
   * Brings a whole tree down from the struck log through {@link TreeFelling}
   * and pockets the wood: straight into the pack, where HaulStep walks it to
   * the workplace container once the pack is worth a trip.
   */
  private void fellTree(RealPerson person, BlockPos struck) {
    if (!(person.level() instanceof ServerLevel level)) {
      return;
    }
    List<ItemStack> haul = TreeFelling.fell(level, struck, person, person.getMainHandItem());
    stripSome(person, haul);
    // Counted before the pack takes it: stacks that merge into ones already
    // carried are emptied as they go in, and read as nothing afterwards.
    int logs = haul.stream().mapToInt(ItemStack::getCount).sum();
    person.addItems(haul);
    Villagelife.LOGGER.debug("[resource-flow] {} ({}) felled a tree at {}: {} log(s) into the pack",
        person.getName().getString(), person.getOccupation(), struck.toShortString(), logs);
  }

  /** A strippable log sometimes comes off already stripped. */
  private void stripSome(RealPerson person, List<ItemStack> drops) {
    for (int i = 0; i < drops.size(); i++) {
      ItemStack drop = drops.get(i);
      BlockState stripped = AxeItem.getAxeStrippingState(Block.byItem(drop.getItem()).defaultBlockState());
      if (stripped != null && person.getRandom().nextFloat() < STRIP_CHANCE) {
        drops.set(i, new ItemStack(stripped.getBlock().asItem(), drop.getCount()));
      }
    }
  }

  /**
   * Reservoir-samples a fellable tree with a natural canopy that the worker
   * can walk up to, and returns its base and the spot to cut it from. Each
   * tree is flood-filled once per scan and every log on it mapped to the base,
   * and each base is asked for its standing spot once, because every log of a
   * tree in the box asks.
   */
  @Nullable
  private Cut findWoodlandTree(RealPerson person, ServerLevel level, BlockPos around) {
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    Long2LongOpenHashMap baseOf = new Long2LongOpenHashMap();
    Long2ObjectOpenHashMap<BlockPos> standOf = new Long2ObjectOpenHashMap<>();
    Cut found = null;
    int seen = 0;
    for (int x = -this.woodlandRadius; x <= this.woodlandRadius; ++x) {
      for (int y = -WOODLAND_VERTICAL_RADIUS; y <= WOODLAND_VERTICAL_RADIUS; ++y) {
        for (int z = -this.woodlandRadius; z <= this.woodlandRadius; ++z) {
          cursor.setWithOffset(around, x, y, z);
          if (!TreeFelling.isFellableLog(level, cursor)) {
            continue;
          }
          long key = cursor.asLong();
          if (!baseOf.containsKey(key)) {
            List<BlockPos> logs = TreeFelling.treeLogs(level, cursor.immutable());
            long base = lowestOf(logs).asLong();
            for (BlockPos log : logs) {
              baseOf.put(log.asLong(), base);
            }
          }
          long baseKey = baseOf.get(key);
          if (!standOf.containsKey(baseKey)) {
            standOf.put(baseKey, standBeside(person, level, BlockPos.of(baseKey)));
          }
          BlockPos stand = standOf.get(baseKey);
          if (stand == null) {
            continue;
          }
          if (person.getRandom().nextInt(++seen) == 0) {
            found = new Cut(BlockPos.of(baseKey), stand);
          }
        }
      }
    }
    // Once per scan that rolled to fell: what the box held. A guard that picks
    // nothing for an hour is either surrounded by nothing fellable or by trunks
    // nobody can stand beside, and only this line tells the two apart.
    int trees = new java.util.HashSet<>(baseOf.values()).size();
    int cuttable = (int) standOf.values().stream().filter(java.util.Objects::nonNull).count();
    Villagelife.LOGGER.debug("[chop] {} ({}) scanned the woodland round {}: {} tree(s), {} with ground to cut from{}",
        person.getName().getString(), person.getOccupation(), around.toShortString(), trees, cuttable,
        found == null ? "" : ", chose the one at " + found.log().toShortString());
    return found;
  }

  /** The base of a tree: the lowest of its logs. */
  private static BlockPos lowestOf(List<BlockPos> logs) {
    BlockPos lowest = logs.get(0);
    for (BlockPos log : logs) {
      if (log.getY() < lowest.getY()) {
        lowest = log;
      }
    }
    return lowest;
  }

  /**
   * Somewhere within arm's length of this trunk that the worker can walk to
   * and swing an axe at its base from: beside it, or beneath it in the mud a
   * stilted mangrove stands over. Candidate spots are the highest standable
   * block of each column round the base, nearest column first, and the
   * navigator has the last word: a spot with no path to it, such as one on top
   * of the leaves, is not a spot. Null when there is none.
   */
  @Nullable
  private static BlockPos standBeside(RealPerson person, ServerLevel level, BlockPos base) {
    List<BlockPos> spots = new ArrayList<>();
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
          if (axeReachesFrom(feet, eyesAt(feet, person), base)) {
            spots.add(feet.immutable());
          }
          break; // the highest standing spot of a column is its nearest to the base
        }
      }
    }
    spots.sort(Comparator.comparingInt(spot -> spot.distManhattan(base)));
    int asked = 0;
    for (BlockPos spot : spots) {
      if (asked++ >= PATHS_PER_TREE) {
        break;
      }
      Path path = person.getNavigation().createPath(spot, 1);
      if (path == null || !path.canReach()) {
        continue;
      }
      Node end = path.getEndNode();
      if (end == null) {
        continue;
      }
      // Where the path actually ends is where the worker will stand.
      BlockPos there = end.asBlockPos();
      if (axeReachesFrom(there, eyesAt(there, person), base)) {
        return there;
      }
    }
    return null;
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

}
