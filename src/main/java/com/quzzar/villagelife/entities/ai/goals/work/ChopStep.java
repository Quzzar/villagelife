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
import com.quzzar.villagelife.village.Village;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
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
 * wood and spares the building's. The one exception is the lumberjack's own
 * stand: the station is the sapling the lodge is authored with, on its own
 * block of dirt, and {@link TreeFelling#fellStand} takes whatever tree is
 * connected to the station without asking who placed it. Once it is down the
 * loop is {@link PlantStep} (a sapling
 * from the pack on the stump), {@link BonemealStep} (fed while there is bone
 * meal), and this step again when it is a tree.
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

  /** Vertical reach of the bounded woodland scan around a worker's post. */
  private static final int WOODLAND_VERTICAL_RADIUS = 8;

  /**
   * The most a village-wide sweep reaches from the claim centre, so a very large
   * town caps the per-scan work rather than reading a quarter-million blocks at
   * once. Beyond it the far corners wait for the lumberjack to drift their way,
   * as they did before. Generous: the pass only runs in the stand's regrowth
   * lulls, not every tick.
   */
  private static final int VILLAGE_SCAN_CAP = 64;

  /** Beside the trunk or under it: arm's length across the ground, squared. */
  private static final double HORIZONTAL_REACH_SQR = 6.0D;

  /** How far from the eyes an axe reaches a log: a player's block reach. */
  private static final double AXE_REACH = 4.5D;

  /** How far below a trunk's base to look for ground to stand on. */
  private static final int STAND_SEARCH_DEPTH = 6;

  /** Paths the navigator is asked for per tree before it is called unreachable. */
  private static final int PATHS_PER_TREE = 4;

  /** How far out and up from a hemmed-in trunk to look for a leaf blocking the way in. */
  private static final int LEAF_CLEAR_RADIUS = 3;

  /** Leaves the navigator is asked about per pass before a way in is given up on. */
  private static final int LEAVES_PER_PASS = 6;

  /** Ticks to swat one leaf out of the way: far quicker than felling a log. */
  private static final int LEAF_TRIM_TICKS = 20;

  private final int woodlandRadius;
  private final float woodlandChance;
  private final int woodlandScanTicks;
  /**
   * Village-wide: the lumberjack's pass, centred on the whole claim rather than
   * a bubble around the worker, so a wild tree anywhere in town is walked to and
   * felled and the streets stay clear, not only the ground by the lodge. Off is
   * the guard's pass: a bounded ring around wherever they patrol.
   */
  private final boolean villageWide;

  private final ShortageWatch dry = new ShortageWatch();

  private BlockPos chopping;
  private int chopTime;
  private int lastProgress = -1;

  /** The lumberjack's planted stand: deterministic, renewable primary work. */
  public ChopStep() {
    this(0, 0.0F, 20, false);
  }

  /** A bounded idle woodland pass around the worker (the guard's), off the claim. */
  public ChopStep(int woodlandRadius, float woodlandChance, int woodlandScanTicks) {
    this(woodlandRadius, woodlandChance, woodlandScanTicks, false);
  }

  /**
   * An idle woodland pass. With {@code villageWide} the scan is the whole
   * village claim plus {@code woodlandRadius} of margin past its edge (the
   * lumberjack, keeping the town clear); without it the scan is a box of
   * {@code woodlandRadius} around wherever the worker stands (the guard, or the
   * lumberjack before a claim exists).
   *
   * @param woodlandRadius    village-wide: margin past the claim edge; otherwise
   *                          the box's half-width around the worker
   * @param woodlandChance    chance that a scan elects to fell one tree
   * @param woodlandScanTicks ticks between scans while idle
   * @param villageWide       sweep the whole claim rather than a bubble
   */
  public ChopStep(int woodlandRadius, float woodlandChance, int woodlandScanTicks, boolean villageWide) {
    this.woodlandRadius = woodlandRadius;
    this.woodlandChance = woodlandChance;
    this.woodlandScanTicks = woodlandScanTicks;
    this.villageWide = villageWide;
  }

  @Override
  @Nullable
  public Cut select(RealPerson person) {
    if (!(person.level() instanceof ServerLevel level)) {
      return null;
    }
    if (this.woodlandRadius > 0) {
      // The guard's pass sweeps a bubble around wherever they patrol; the
      // lumberjack's sweeps the whole village claim, so a wild tree anywhere in
      // town is walked to and felled rather than only those near one spot.
      if (person.isInventoryFull() || person.getRandom().nextFloat() >= this.woodlandChance) {
        return null;
      }
      Scan scan = woodlandScan(person);
      return findWoodlandTree(person, level, scan.center(), scan.radius());
    }
    BlockPos post = LocationManager.getJobLocation(person);
    if (post == BlockPos.ZERO) {
      return null; // no post assigned yet: a misconfiguration, not a wood shortage
    }
    BlockState state = level.getBlockState(post);

    if (state.is(BlockTags.LOGS_THAT_BURN)) {
      this.dry.foundWork();
      BlockPos stand = standBeside(person, level, post);
      if (stand != null) {
        return new Cut(post, stand);
      }
      // The trunk is grown but hemmed in by its own low, dense canopy (a spruce
      // skirts the ground in leaves): no spot beside it can be stood on or
      // walked to. Cut a way in rather than stand on the log, which is
      // unreachable and left the lumberjack stranded on a loop (Aaron,
      // 2026-09-02). The nearest leaf the worker CAN reach is trimmed, one per
      // pass, until a stand to the trunk itself comes free.
      Cut throughLeaves = reachableLeaf(person, level, post);
      return throughLeaves != null ? throughLeaves : new Cut(post, post);
    }

    // Nothing standing to fell: a sapling coming back, or a bare stump with
    // nothing in the pack to plant on it. Most of these lulls are just the
    // regrowth, so only a sustained dry stretch is reported, once, as a genuine
    // wood shortage; the words tell the two apart, since a bare stand is the
    // one a village can do something about.
    boolean bare = PlantStep.isBare(state) && PlantStep.saplingIn(person) == null;
    this.dry.wentDry(person, Items.OAK_LOG, 1, bare
        ? "My stand is bare and I have no sapling to plant on it; there is no wood to cut until I find one."
        : "The tree at my stand is felled; I have no wood to cut until it grows back.");
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
    boolean trimming = state.is(BlockTags.LEAVES);
    if (!state.is(BlockTags.LOGS_THAT_BURN) && !trimming) {
      return false; // somebody else got the log, or a leaf already fell away
    }

    this.chopTime++;
    int ticksNeeded = trimming ? LEAF_TRIM_TICKS : CHOP_TICKS;
    int progress = (int) ((float) this.chopTime / ticksNeeded * 10.0F);
    if (progress != this.lastProgress) {
      person.level().destroyBlockProgress(person.getId(), target, progress);
      this.lastProgress = progress;
    }
    if (this.chopTime < ticksNeeded) {
      return true;
    }

    if (trimming) {
      // A leaf out of the way, not a tree down: clear it and look again next
      // pass, when the stand to the trunk may be open or the next leaf in is
      // the target. No drop; this is clearing a path, not a harvest.
      person.level().destroyBlockProgress(person.getId(), target, -1);
      person.level().destroyBlock(target, false);
      return false;
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

  /**
   * Whether an axe swung from these eyes, over these feet, reaches the log.
   * Reach is to the log's nearest face, as a player's is, not to its centre:
   * the half block that gains is the margin the navigator needs, since it may
   * stop a block short of the standing spot, and a trunk five blocks up was
   * within reach from the spot and just out of it from one block beside it.
   */
  private static boolean axeReachesFrom(BlockPos feet, Vec3 eyes, BlockPos log) {
    int dx = feet.getX() - log.getX();
    int dz = feet.getZ() - log.getZ();
    return dx * dx + dz * dz <= HORIZONTAL_REACH_SQR
        && new AABB(log).distanceToSqr(eyes) <= AXE_REACH * AXE_REACH;
  }

  /** The eyes of a worker standing with their feet in this block. */
  private static Vec3 eyesAt(BlockPos feet, RealPerson person) {
    return new Vec3(feet.getX() + 0.5D, feet.getY() + person.getEyeHeight(), feet.getZ() + 0.5D);
  }

  /**
   * Brings a whole tree down from the struck log through {@link TreeFelling}
   * and pockets the wood: straight into the pack, where HaulStep walks it to
   * the workplace container once the pack is worth a trip. At the stand the
   * tree is the village's own and comes down whoever planted it; in the
   * woodland only a tree nobody placed does.
   */
  private void fellTree(RealPerson person, BlockPos struck) {
    if (!(person.level() instanceof ServerLevel level)) {
      return;
    }
    ItemStack axe = person.getMainHandItem();
    List<ItemStack> haul = this.woodlandRadius > 0
        ? TreeFelling.fell(level, struck, person, axe)
        : TreeFelling.fellStand(level, struck, person, axe);
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

  /** A place to scan around and how far out from it: see {@link #woodlandScan}. */
  private record Scan(BlockPos center, int radius) {
  }

  /**
   * Where this pass looks and how far. The lumberjack's village-wide pass is
   * centred on the whole claim (its centre, out to its half-span plus a margin,
   * capped at {@link #VILLAGE_SCAN_CAP}) so every street is swept; the guard's,
   * and the lumberjack's before a claim exists, is a bubble around the worker.
   * The claim carries no height, so the sweep sits at the worker's own Y and
   * leans on the vertical reach for the rest: village ground is graded near
   * level.
   */
  private Scan woodlandScan(RealPerson person) {
    if (this.villageWide) {
      Village village = person.getVillage();
      BoundingBox claim = village == null ? null : village.getClaimFootprint();
      if (claim != null) {
        int centerX = (claim.minX() + claim.maxX()) / 2;
        int centerZ = (claim.minZ() + claim.maxZ()) / 2;
        int half = Math.max((claim.maxX() - claim.minX()) / 2, (claim.maxZ() - claim.minZ()) / 2);
        int radius = Math.min(half + this.woodlandRadius, VILLAGE_SCAN_CAP);
        return new Scan(new BlockPos(centerX, person.blockPosition().getY(), centerZ), radius);
      }
    }
    return new Scan(person.blockPosition(), this.woodlandRadius);
  }

  /**
   * Reservoir-samples a fellable tree with a natural canopy that the worker
   * can walk up to, and returns its base and the spot to cut it from. Each
   * tree is flood-filled once per scan and every log on it mapped to the base,
   * and each base is asked for its standing spot once, because every log of a
   * tree in the box asks.
   */
  @Nullable
  private Cut findWoodlandTree(RealPerson person, ServerLevel level, BlockPos around, int radius) {
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    Long2LongOpenHashMap baseOf = new Long2LongOpenHashMap();
    Long2ObjectOpenHashMap<BlockPos> standOf = new Long2ObjectOpenHashMap<>();
    Cut found = null;
    int seen = 0;
    for (int x = -radius; x <= radius; ++x) {
      for (int y = -WOODLAND_VERTICAL_RADIUS; y <= WOODLAND_VERTICAL_RADIUS; ++y) {
        for (int z = -radius; z <= radius; ++z) {
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
   * A way in through a trunk's own canopy when nothing beside it can be reached:
   * the nearest leaf around its base that the worker can both walk to and reach
   * with an axe, one leaf per pass. Trimming it opens the skirt until a stand to
   * the trunk itself comes free. Only the lumberjack's OWN stand tree is ever
   * reached here (the woodland pass returns earlier), so this never swats a wild
   * canopy or a player's. Null when no leaf is reachable either.
   */
  @Nullable
  private Cut reachableLeaf(RealPerson person, ServerLevel level, BlockPos trunk) {
    List<BlockPos> leaves = new ArrayList<>();
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int dx = -LEAF_CLEAR_RADIUS; dx <= LEAF_CLEAR_RADIUS; dx++) {
      for (int dy = -1; dy <= LEAF_CLEAR_RADIUS; dy++) {
        for (int dz = -LEAF_CLEAR_RADIUS; dz <= LEAF_CLEAR_RADIUS; dz++) {
          cursor.set(trunk.getX() + dx, trunk.getY() + dy, trunk.getZ() + dz);
          if (level.getBlockState(cursor).is(BlockTags.LEAVES)) {
            leaves.add(cursor.immutable());
          }
        }
      }
    }
    leaves.sort(Comparator.comparingDouble(leaf -> leaf.distSqr(person.blockPosition())));
    int asked = 0;
    for (BlockPos leaf : leaves) {
      if (asked++ >= LEAVES_PER_PASS) {
        break;
      }
      BlockPos stand = standBeside(person, level, leaf);
      if (stand != null) {
        return new Cut(leaf, stand);
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
