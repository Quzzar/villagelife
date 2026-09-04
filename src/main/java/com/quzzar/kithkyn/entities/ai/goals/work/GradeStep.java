package com.quzzar.kithkyn.entities.ai.goals.work;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.Utils;
import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.entities.ai.goals.ShortageWatch;
import com.quzzar.kithkyn.savedata.GradedColumnStore;
import com.quzzar.kithkyn.savedata.PlacedBlockStore;
import com.quzzar.kithkyn.village.GradingSurvey;
import com.quzzar.kithkyn.village.Village;
import com.quzzar.kithkyn.village.buildings.SitePreparation;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Grading the ground between the village's buildings: cut-and-fill work the
 * builder does when there is nothing to build, outranking the path-laying
 * that is the other half of an idle builder's day (docs/worker-loops.md).
 *
 * A village on a hillside gets its buildings on level footprints and rough
 * ground between them: two-block steps a villager cannot climb, notches where
 * a site was cut in, ledges where one was filled up. The builder reads that
 * ground once ({@link GradingSurvey}), which yields a plan: the height every
 * column should stand at for there to be no step taller than one block. Then
 * they work the plan nearest column first, digging high ground out a block at
 * a time into the pack and raising low ground with the same spoil, so the
 * earth from one side of a slope becomes the fill on the other: dirt where
 * there is dirt, mud in a swamp, sand in a desert. When the pack holds no
 * fill and only filling is left, the builder fetches earth from a village
 * chest by hand, dirt first and then whatever soft ground the stores hold,
 * and shelves the spoil there when it piles up.
 * Nothing teleports. When the plan is spent the ground is read again, and
 * the job ends when a reading finds nothing left to do.
 * A small deep opening is different work: the survey removes its cave floor
 * from the smoothing surface, and the builder bridges one stable layer across
 * its mouth from the rim inward instead of filling it from the bottom.
 *
 * Planning first and then executing matters: a plan is a single walkable
 * surface, so working it produces one, whereas re-reading the ground after
 * every block chased a target that moved with each block and left dips and
 * odd steps on steep ground. It is also far cheaper.
 *
 * What it will not touch is the point of it. Only soft natural ground
 * ({@code kithkyn:gradeable}) is moved: never a block the village placed,
 * never a block entity, never ground the village has claimed for a building
 * or that lies under a village torch or a sapling, and never beside water. A
 * player's placed blocks are not protected here: a dirt hummock is a hummock
 * whoever piled it. And every move is checked against the height the column
 * had before grading began ({@link GradedColumnStore}), so the village smooths
 * the surface of its land and never reshapes it. A cliff stays a cliff, a hill
 * stays a hill, and after a hundred hours the valley is still a valley.
 *
 * One job strings many blocks together. The target the loop is handed is a
 * {@link Job} whose position advances as each block is finished, the way a
 * cleric's patient walks about while being treated, so the builder moves from
 * block to block without letting go of the work between them, and the
 * lower-priority path-laying never gets a tick to twitch them toward some
 * other building.
 */
public final class GradeStep implements WorkStep<GradeStep.Job> {

  /** Ticks digging one block takes. */
  private static final int DIG_TICKS = 20;

  /** Ticks setting one block of fill takes. */
  private static final int FILL_TICKS = 10;

  /** Fill kept in the pack, dirt first; spoil beyond it is shelved. */
  private static final int FILL_RESERVE = 16;

  /** Spoil in the pack past this many items is worth a trip to a chest. */
  private static final int SPOIL_BEFORE_TRIP = 32;

  /** Ticks between readings of the ground while nothing is being graded. */
  private static final int SCAN_TICKS = 100;

  /**
   * How long a column the builder could not get to is left alone. On steep
   * ground the nearest uneven column is sometimes a ledge no path reaches;
   * without this the loop gives up on it, stands down, and then picks the
   * very same column again as the nearest, forever.
   */
  private static final int SHUN_TICKS = 2400;

  private enum Kind {
    COVER, CUT, FILL, FETCH_COVER_EARTH, FETCH_EARTH, SHELVE_SPOIL
  }

  /** One column of the plan: where its top stands now, and where the plan wants it. */
  private static final class Planned {

    private final int x;
    private final int z;
    private final int target;
    private final boolean apron;
    private final boolean cover;
    private int height;

    private Planned(GradingSurvey.Column column) {
      this.x = column.x();
      this.z = column.z();
      this.height = column.height();
      this.target = column.target();
      this.apron = column.apron();
      this.cover = column.cover();
    }

    private boolean done() {
      return height == target;
    }

    private boolean wantsCut() {
      return !cover && target < height;
    }

    /** The block a cut takes: the column's top. */
    private BlockPos top() {
      return new BlockPos(x, height, z);
    }

    /** The block a fill sets: the air over the column's top. */
    private BlockPos spot() {
      return new BlockPos(x, cover ? target : height + 1, z);
    }

    private long distSqr(BlockPos from) {
      long dx = x - from.getX();
      long dz = z - from.getZ();
      return dx * dx + dz * dz;
    }
  }

  /** What the builder is walking to right now, advanced in place as the work goes on. */
  public static final class Job {

    private Kind kind;
    private BlockPos pos;
    private BlockPos travelPos;
    @Nullable
    private Planned column;
    @Nullable
    private Item fillWith;
    private int ticks;
    private int shownProgress = -1;
    private boolean coverFetchFailed;
    private boolean fetchFailed;
    private boolean shelveFailed;

    /** The columns still to move, from the last reading of the ground. */
    private final List<Planned> plan = new ArrayList<>();

    /** Spots this job found it could not work after all; left alone until the next job. */
    private final LongOpenHashSet passedOver = new LongOpenHashSet();

    private void set(Kind kind, BlockPos pos, @Nullable Planned column) {
      this.kind = kind;
      this.pos = pos;
      this.travelPos = pos;
      this.column = column;
      this.fillWith = null;
      this.ticks = 0;
      this.shownProgress = -1;
    }
  }

  private final ShortageWatch dry = new ShortageWatch();

  /** Columns given up on, keyed like {@link GradedColumnStore}, to the tick they may be tried again. */
  private final Long2IntOpenHashMap shunnedUntil = new Long2IntOpenHashMap();

  /** Whether the last reading that planned work the builder could not do has been logged. */
  private boolean idleReported;

  @Override
  @Nullable
  public Job select(RealPerson person) {
    Village village = person.getVillage();
    if (village == null || (village.getCurrentProject() != null && person.isConstructionLead())) {
      return null; // there is something to build; grading can wait
    }
    if (!(person.level() instanceof ServerLevel level)) {
      return null;
    }
    Job job = new Job();
    return aim(person, level, village, job) ? job : null;
  }

  @Override
  public boolean act(RealPerson person, Job job) {
    Village village = person.getVillage();
    if (village == null || (village.getCurrentProject() != null && person.isConstructionLead())
        || !(person.level() instanceof ServerLevel level)) {
      return false;
    }
    boolean finished = switch (job.kind) {
      case COVER -> cover(person, level, job);
      case CUT -> cut(person, level, job);
      case FILL -> fill(person, level, job);
      case FETCH_COVER_EARTH -> fetchEarth(person, job, true);
      case FETCH_EARTH -> fetchEarth(person, job, false);
      case SHELVE_SPOIL -> shelveSpoil(person, job);
    };
    if (!finished) {
      return true;
    }
    return aim(person, level, village, job); // the next block, or the job is over
  }

  @Override
  public BlockPos positionOf(Job job) {
    return job.travelPos;
  }

  @Override
  public void released(RealPerson person, Job job) {
    if (job.kind == Kind.CUT) {
      person.level().destroyBlockProgress(person.getId(), job.pos, -1);
    }
    // Let go of a column before it was finished: could not be reached, or
    // something interrupted. Either way the next pick should not walk straight
    // back to it.
    if (job.column != null && !job.column.done()) {
      this.shunnedUntil.put(BlockPos.asLong(job.column.x, 0, job.column.z), person.tickCount + SHUN_TICKS);
    }
  }

  @Override
  public String describe() {
    return "the ground between our buildings";
  }

  @Override
  public String activity() {
    return "levelling the ground between our buildings";
  }

  @Override
  public int selectEveryTicks() {
    return SCAN_TICKS;
  }

  /** Digging is timed in ticks, so the act runs on every one of them. */
  @Override
  public int actEveryTicks() {
    return 1;
  }

  /**
   * Points the job at the next thing worth doing, or returns false when the
   * ground within the village's reach is as smooth as it may be made.
   *
   * Spoil is shelved first once there is enough of it to be worth the walk.
   * Then the plan is worked; when it is spent, the ground is read once more,
   * and a reading that plans nothing the builder can do ends the job.
   */
  private boolean aim(RealPerson person, ServerLevel level, Village village, Job job) {
    int shelvable = spoilToShelve(person);
    if (!job.shelveFailed && (shelvable >= SPOIL_BEFORE_TRIP || (person.isInventoryFull() && shelvable > 0))) {
      BlockPos chest = PackLogistics.chestWithRoomFor(person, village, new ItemStack(Items.DIRT));
      if (chest != null) {
        job.set(Kind.SHELVE_SPOIL, chest, null);
        return true;
      }
    }
    if (pick(person, level, village, job, false)) {
      return true;
    }
    GradingSurvey survey = GradingSurvey.of(level, village);
    if (survey == null) {
      return false;
    }
    job.plan.clear();
    int covers = 0;
    int cuts = 0;
    for (GradingSurvey.Column column : survey.uneven(person.blockPosition())) {
      job.plan.add(new Planned(column));
      covers += column.cover() ? 1 : 0;
      cuts += !column.cover() && column.wantsCut() ? 1 : 0;
    }
    boolean found = pick(person, level, village, job, true);
    // One line per plan, and one per stretch of wanting work it cannot do:
    // an idle builder re-reads the ground every few seconds, which is not news.
    if (found || (!job.plan.isEmpty() && !this.idleReported)) {
      Kithkyn.LOGGER.debug(
          "[grading] {} ({}) read the ground of '{}': {} opening block(s) to cover, {} column(s) to cut, {} to fill; {}",
          person.getName().getString(), person.getOccupation(), village.getName(), covers, cuts,
          job.plan.size() - covers - cuts,
          found ? "next is " + job.kind + " at " + job.pos.toShortString() : "nothing doable now, job over");
    }
    this.idleReported = !found;
    return found;
  }

  /**
   * Aims at the nearest column of the plan the builder can move right now, or
   * at a chest holding the earth the plan needs. Cover work goes first so the
   * cave floor cannot shape any ordinary grading that follows. Only when no
   * carried earth can do the remaining work does the builder go to a chest.
   * False when nothing in the plan can be done now.
   */
  private boolean pick(RealPerson person, ServerLevel level, Village village, Job job, boolean reportShortage) {
    GradedColumnStore graded = GradedColumnStore.get(level);
    Item coverFillInHand = fillInHand(person, true);
    Item fillInHand = fillInHand(person, false);
    boolean packFull = person.isInventoryFull();
    boolean coverFillWanted = false;
    boolean fillWanted = false;
    BlockPos from = person.blockPosition();
    job.plan.removeIf(Planned::done);
    job.plan.sort(Comparator.comparingInt((Planned column) -> column.cover ? 0 : 1)
        .thenComparingLong(column -> column.distSqr(from)));
    for (Planned column : job.plan) {
      if (shunned(person, column)) {
        continue;
      }
      if (column.cover) {
        BlockPos spot = column.spot();
        if (job.passedOver.contains(spot.asLong()) || !mayCover(level, spot)) {
          continue;
        }
        BlockPos stand = safePlacementStand(level, job, spot, from);
        if (stand == null) {
          continue;
        }
        if (coverFillInHand == null) {
          coverFillWanted = true;
          continue;
        }
        job.set(Kind.COVER, spot, column);
        job.travelPos = stand;
        job.fillWith = coverFillInHand;
        this.dry.foundWork(person);
        return true;
      }
      if (column.wantsCut()) {
        BlockPos top = column.top();
        if (packFull || job.passedOver.contains(top.asLong())
            || !withinBudget(graded, column, -1) || !mayCut(level, top)) {
          continue;
        }
        job.set(Kind.CUT, top, column);
        this.dry.foundWork(person);
        return true;
      }
      BlockPos spot = column.spot();
      if (job.passedOver.contains(spot.asLong()) || !withinBudget(graded, column, 1) || !mayFill(level, spot)) {
        continue;
      }
      BlockPos stand = safePlacementStand(level, job, spot, from);
      if (stand == null) {
        continue;
      }
      if (fillInHand == null) {
        fillWanted = true;
        continue;
      }
      job.set(Kind.FILL, spot, column);
      job.travelPos = stand;
      job.fillWith = fillInHand;
      this.dry.foundWork(person);
      return true;
    }

    if (coverFillWanted) {
      BlockPos chest = job.coverFetchFailed ? null
          : PackLogistics.chestHolding(person, village, earthWanted(true));
      if (chest != null) {
        job.set(Kind.FETCH_COVER_EARTH, chest, null);
        this.dry.foundWork(person);
        return true;
      }
      if (reportShortage) {
        this.dry.wentDry(person, Items.DIRT, FILL_RESERVE,
            "We have no stable earth to cover a hole in the village ground.");
      }
    }
    if (fillWanted) {
      BlockPos chest = job.fetchFailed ? null : PackLogistics.chestHolding(person, village, earthWanted(false));
      if (chest != null) {
        job.set(Kind.FETCH_EARTH, chest, null);
        this.dry.foundWork(person);
        return true;
      }
      if (reportShortage) {
        this.dry.wentDry(person, Items.DIRT, FILL_RESERVE,
            "We have no earth to grade the ground between our buildings.");
      }
    }
    return false;
  }

  /** Whether this column was given up on recently; forgets it once the time has passed. */
  private boolean shunned(RealPerson person, Planned column) {
    long key = BlockPos.asLong(column.x, 0, column.z);
    int until = this.shunnedUntil.getOrDefault(key, Integer.MIN_VALUE);
    if (until == Integer.MIN_VALUE) {
      return false;
    }
    if (person.tickCount >= until) {
      this.shunnedUntil.remove(key);
      return false;
    }
    return true;
  }

  /**
   * The levelling budget. A column may stand at most
   * {@link SitePreparation#MAX_COLUMN_DELTA} above or below the height it had
   * before grading. The survey now derives its target from that stable original
   * surface, so a later pass may safely correct an earlier partial move without
   * making the target drift.
   *
   * <p>A building's apron column is exempt from the height cap: it is graded all
   * the way to the ramp that meets the building's floor, however far that is from
   * where the raw land stood, so a villager can always step onto the building and
   * in at its door (tight apron, 2026-09-03).
   */
  private static boolean withinBudget(GradedColumnStore graded, Planned column, int delta) {
    int original = graded.originalHeight(column.x, column.z, column.height);
    return withinBudget(original, column.height, delta, column.apron);
  }

  /**
   * A fill is approached from finished neighbouring ground, never from another
   * column that still needs to rise. Working from the latter is what led a
   * builder down into a deep depression and then removed the only route back
   * out as the surrounding columns filled.
   */
  @Nullable
  private static BlockPos safePlacementStand(ServerLevel level, Job job, BlockPos spot, BlockPos from) {
    BlockPos best = null;
    double bestDistance = Double.MAX_VALUE;
    for (Direction side : Direction.Plane.HORIZONTAL) {
      int x = spot.getX() + side.getStepX();
      int z = spot.getZ() + side.getStepZ();
      int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
      BlockPos feet = new BlockPos(x, topY + 1, z);
      boolean unfinished = unfinishedColumn(job, x, z);
      if (!safePlacementLevel(spot.getY(), feet.getY(), unfinished)
          || !standable(level, feet)) {
        continue;
      }
      double distance = feet.distSqr(from);
      if (distance < bestDistance) {
        best = feet;
        bestDistance = distance;
      }
    }
    return best;
  }

  /** Pure height and plan rule kept visible to the regression test. */
  static boolean safePlacementLevel(int workY, int standY, boolean standColumnUnfinished) {
    return !standColumnUnfinished && Math.abs(standY - workY) <= 2;
  }

  /** Whether the neighbouring column is itself still part of this grading job. */
  private static boolean unfinishedColumn(Job job, int x, int z) {
    for (Planned column : job.plan) {
      if (column.x == x && column.z == z && !column.done()) {
        return true;
      }
    }
    return false;
  }

  /** Two clear body cells over a sturdy, dry block. */
  private static boolean standable(ServerLevel level, BlockPos feet) {
    BlockPos ground = feet.below();
    BlockState support = level.getBlockState(ground);
    return support.getFluidState().isEmpty()
        && support.isFaceSturdy(level, ground, Direction.UP)
        && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
        && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
  }

  /** Pure form kept visible to the regression tests. */
  static boolean withinBudget(int original, int height, int delta, boolean apron) {
    return apron || Math.abs(height + delta - original) <= SitePreparation.MAX_COLUMN_DELTA;
  }

  /**
   * Whether the top block of a column may be dug out: soft ground that is
   * nobody's structure, with nothing but loose cover on it and no water or
   * lava beside it to pour into the hole.
   */
  private static boolean mayCut(ServerLevel level, BlockPos top) {
    BlockState state = level.getBlockState(top);
    if (!hasSupportBelowCut(level, top)
        || !state.is(GradingSurvey.GRADEABLE) || level.getBlockEntity(top) != null
        || PlacedBlockStore.get(level).isVillagePlaced(top)) {
      return false;
    }
    BlockPos above = top.above();
    if (!looseCover(level, above)) {
      return false;
    }
    for (Direction side : Direction.Plane.HORIZONTAL) {
      BlockPos beside = top.relative(side);
      // A neighbour in a chunk nobody has loaded is not read, and not paged in
      // to be read: the cut waits until it can be checked.
      if (!level.isLoaded(beside) || !level.getFluidState(beside).isEmpty()
          || !level.getFluidState(beside.above()).isEmpty()) {
        return false;
      }
    }
    return true;
  }

  /**
   * A one-block cut must expose a dry, full-height standing surface. A thin
   * cap over a cavity is still ground: opening it would drop the real surface
   * farther than the survey planned, including when that cap covered a hole.
   * Checked again during the dig so support changing on the way cannot reopen it.
   */
  static boolean hasSupportBelowCut(BlockGetter level, BlockPos top) {
    BlockPos below = top.below();
    BlockState support = level.getBlockState(below);
    return support.getFluidState().isEmpty() && support.isFaceSturdy(level, below, Direction.UP);
  }

  /**
   * Whether a block of fill may be set here: loose cover over solid ground, and
   * nobody standing in it. The vanilla obstruction test counts only players and
   * vehicles as bodies, so a villager's own body is checked for directly. This
   * was learned live: the builder filled a gully from inside it, around and
   * then over himself, and was found encased in dirt at the bottom.
   */
  private static boolean mayFill(ServerLevel level, BlockPos spot) {
    if (!looseCover(level, spot) || !level.getFluidState(spot).isEmpty()) {
      return false;
    }
    BlockState below = level.getBlockState(spot.below());
    if (below.isAir() || !below.getFluidState().isEmpty()) {
      return false;
    }
    return level.isUnobstructed(Blocks.DIRT.defaultBlockState(), spot, CollisionContext.empty())
        && level.getEntitiesOfClass(LivingEntity.class, new AABB(spot)).isEmpty();
  }

  /**
   * Whether the mouth of a planned hole may be capped here. Unlike ordinary
   * fill, the block need not have ground directly beneath it. It must instead
   * join a sturdy horizontal face, which makes the builder work from the rim
   * inward and prevents an isolated floating block if the world changed since
   * the survey.
   */
  private static boolean mayCover(ServerLevel level, BlockPos spot) {
    PlacedBlockStore placed = PlacedBlockStore.get(level);
    if (!looseCover(level, spot) || !level.getFluidState(spot).isEmpty()
        || placed.isPlayerPlaced(spot) || placed.isVillagePlaced(spot)) {
      return false;
    }
    boolean joinedToRim = false;
    for (Direction side : Direction.Plane.HORIZONTAL) {
      BlockPos beside = spot.relative(side);
      if (level.isLoaded(beside)
          && !level.getBlockState(beside).getCollisionShape(level, beside).isEmpty()) {
        joinedToRim = true;
        break;
      }
    }
    return joinedToRim
        && level.isUnobstructed(Blocks.DIRT.defaultBlockState(), spot, CollisionContext.empty())
        && level.getEntitiesOfClass(LivingEntity.class, new AABB(spot)).isEmpty();
  }

  /**
   * Whether digging out the block under the builder's own feet would leave
   * them in a hole they cannot climb out of: every cardinal neighbour a wall
   * two high. A villager jumps one block, so one such neighbour open is enough.
   */
  private static boolean wouldTrap(ServerLevel level, BlockPos top) {
    for (Direction side : Direction.Plane.HORIZONTAL) {
      BlockPos wall = top.relative(side).above();
      if (level.getBlockState(wall).getCollisionShape(level, wall).isEmpty()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Cover a cut may take with it or fill may replace: air, or a plant standing
   * on the ground that is nobody's and not growing on purpose. A sapling, a
   * trunk, a crop, a torch, anything the village set down: each of those pins
   * the column under it.
   */
  private static boolean looseCover(ServerLevel level, BlockPos pos) {
    BlockState state = level.getBlockState(pos);
    if (state.isAir()) {
      return true;
    }
    if (!state.is(SitePreparation.CLEARABLE) || state.is(BlockTags.LOGS)
        || state.is(BlockTags.LEAVES) || state.is(BlockTags.SAPLINGS)) {
      return false;
    }
    return !PlacedBlockStore.get(level).isVillagePlaced(pos);
  }

  /** One block dug out over a dig's worth of ticks, its drops and its cover into the pack. */
  private boolean cut(RealPerson person, ServerLevel level, Job job) {
    BlockPos top = job.pos;
    if (!mayCut(level, top) || (top.equals(person.getOnPos()) && wouldTrap(level, top))) {
      job.passedOver.add(top.asLong()); // changed under them, or would dig them into a pit
      return true;
    }
    job.ticks++;
    int progress = job.ticks * 10 / DIG_TICKS;
    if (progress != job.shownProgress) {
      level.destroyBlockProgress(person.getId(), top, progress);
      job.shownProgress = progress;
    }
    if (job.ticks < DIG_TICKS) {
      return false;
    }
    level.destroyBlockProgress(person.getId(), top, -1);

    GradedColumnStore.get(level).recordBeforeGrading(top.getX(), top.getZ(), top.getY());
    List<ItemStack> spoil = new ArrayList<>();
    takeCover(person, level, top.above(), spoil);
    BlockState ground = level.getBlockState(top);
    spoil.addAll(Block.getDrops(ground, level, top, null, person, person.getMainHandItem()));
    level.removeBlock(top, false);
    PlacedBlockStore.get(level).clearPlaced(top); // a player's dirt, once dug, is nobody's record
    level.playSound((Player) null, top, ground.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F,
        person.getRandom().nextFloat() * 0.4F + 0.8F);
    person.addItems(spoil);
    if (job.column != null) {
      job.column.height--;
    }
    return true;
  }

  /**
   * The plant on top comes off with the ground under it, upper half first so
   * a tall plant does not pop its own drop onto the ground.
   */
  private static void takeCover(RealPerson person, ServerLevel level, BlockPos pos, List<ItemStack> into) {
    BlockState cover = level.getBlockState(pos);
    if (cover.isAir()) {
      return;
    }
    if (cover.getBlock() instanceof DoublePlantBlock && level.getBlockState(pos.above()).is(cover.getBlock())) {
      level.removeBlock(pos.above(), false);
    }
    into.addAll(Block.getDrops(cover, level, pos, null, person, ItemStack.EMPTY));
    level.removeBlock(pos, false);
    PlacedBlockStore.get(level).clearPlaced(pos);
  }

  /** One stable earth block laid across a cave mouth from its sturdy rim inward. */
  private boolean cover(RealPerson person, ServerLevel level, Job job) {
    return placeEarth(person, level, job, true);
  }

  /** One block of ordinary supported fill set on top of the current ground. */
  private boolean fill(RealPerson person, ServerLevel level, Job job) {
    return placeEarth(person, level, job, false);
  }

  /**
   * Sets one block from the pack. Ground fill rises from solid support; a hole
   * cover bridges inward from a sturdy side. Neither is recorded as
   * village-placed because earth is not a structure (docs/block-ownership.md),
   * and a later grading pass may move it normally.
   */
  private boolean placeEarth(RealPerson person, ServerLevel level, Job job, boolean covering) {
    BlockPos spot = job.pos;
    // Never fill from below: a builder standing lower than the spot is raising
    // a wall beside their own head, which is how a gully became a tomb. They
    // fill from level ground or above, and climb out to reach the rest.
    if (!(covering ? mayCover(level, spot) : mayFill(level, spot))
        || person.blockPosition().getY() < spot.getY()) {
      job.passedOver.add(spot.asLong());
      return true;
    }
    if (++job.ticks < FILL_TICKS) {
      return false;
    }
    ItemStack earth = job.fillWith == null ? ItemStack.EMPTY : person.removeItem(job.fillWith, 1);
    if (earth.isEmpty()) {
      return true; // spent on the way; the next aim picks again or fetches
    }
    if (!covering) {
      GradedColumnStore.get(level).recordBeforeGrading(spot.getX(), spot.getZ(), spot.getY() - 1);
    }
    BlockState fill = Block.byItem(earth.getItem()).defaultBlockState();
    level.setBlock(spot, fill, 3);
    level.playSound((Player) null, spot, fill.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F,
        person.getRandom().nextFloat() * 0.4F + 0.8F);
    if (job.column != null) {
      job.column.height = covering ? job.column.target : job.column.height + 1;
    }
    return true;
  }

  /**
   * A chest visit that tops the pack up to its working reserve of fill: dirt
   * first, then whatever other earth the chest holds, until the reserve is
   * met.
   */
  private boolean fetchEarth(RealPerson person, Job job, boolean stableOnly) {
    Container chest = PackLogistics.containerAt(person, job.pos);
    int pulled = 0;
    if (chest != null) {
      for (Item earth : earthItems(stableOnly)) {
        int need = FILL_RESERVE - fillCarried(person, stableOnly);
        if (need <= 0) {
          break;
        }
        ItemStack taken = Utils.removeItem(chest, earth, need);
        if (!taken.isEmpty()) {
          pulled += taken.getCount();
          Utils.insertItems(person.personMainInv, List.of(taken), person);
        }
      }
    }
    if (pulled > 0) {
      Kithkyn.LOGGER.debug("[resource-flow] {} ({}) fetched {} {}earth from a chest for grading",
          person.getName().getString(), person.getOccupation(), pulled, stableOnly ? "stable " : "");
    } else {
      if (stableOnly) {
        job.coverFetchFailed = true;
      } else {
        job.fetchFailed = true;
      }
    }
    return true;
  }

  /** How much earth the pack holds that could be set as fill. */
  private static int fillCarried(RealPerson person, boolean stableOnly) {
    Container pack = person.personMainInv;
    int total = 0;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      if (fillBlock(stack, stableOnly)) {
        total += stack.getCount();
      }
    }
    return total;
  }

  /** Every earth a chest might hold, as a reserve's worth each, for finding the chest to go to. */
  private static List<ItemStack> earthWanted(boolean stableOnly) {
    List<ItemStack> wanted = new ArrayList<>();
    for (Item earth : earthItems(stableOnly)) {
      wanted.add(new ItemStack(earth, FILL_RESERVE));
    }
    return wanted;
  }

  /**
   * The earths that can be set as fill, dirt first and then the rest of the
   * {@code kithkyn:gradeable} tag, so a pack author's ground joins in.
   */
  private static List<Item> earthItems(boolean stableOnly) {
    List<Item> items = new ArrayList<>();
    items.add(Items.DIRT);
    BuiltInRegistries.BLOCK.getTag(GradingSurvey.GRADEABLE).ifPresent(blocks -> {
      for (Holder<Block> holder : blocks) {
        Item item = holder.value().asItem();
        if (item != Items.AIR && item != Items.DIRT && fillBlock(new ItemStack(item), stableOnly)) {
          items.add(item);
        }
      }
    });
    return items;
  }

  /** A chest visit that sets the spoil down, all but the working reserve of fill. */
  private boolean shelveSpoil(RealPerson person, Job job) {
    Container chest = PackLogistics.containerAt(person, job.pos);
    int moved = chest == null ? 0 : shelve(person, chest);
    if (moved == 0) {
      job.shelveFailed = true; // the chest is full; carry on and try another job
    }
    return true;
  }

  private static int shelve(RealPerson person, Container chest) {
    Container pack = person.personMainInv;
    int[] keep = fillToKeep(pack);
    int moved = 0;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      int offer = stack.getCount() - keep[slot];
      if (!spoil(stack) || offer <= 0) {
        continue;
      }
      ItemStack leftover = HopperBlockEntity.addItem(pack, chest, stack.copyWithCount(offer), null);
      int placed = offer - leftover.getCount();
      stack.shrink(placed);
      if (stack.isEmpty()) {
        pack.setItem(slot, ItemStack.EMPTY);
      }
      moved += placed;
    }
    if (moved > 0) {
      pack.setChanged();
      Kithkyn.LOGGER.debug("[resource-flow] {} ({}) shelved {} item(s) of spoil into a chest",
          person.getName().getString(), person.getOccupation(), moved);
    }
    return moved;
  }

  /**
   * How much of each pack slot is kept back as the working reserve of fill:
   * up to {@link #FILL_RESERVE} blocks, dirt before any other earth.
   */
  private static int[] fillToKeep(Container pack) {
    int[] keep = new int[pack.getContainerSize()];
    int budget = FILL_RESERVE;
    for (boolean dirtPass : new boolean[] { true, false }) {
      for (int slot = 0; slot < pack.getContainerSize() && budget > 0; slot++) {
        ItemStack stack = pack.getItem(slot);
        if (!fillBlock(stack) || stack.is(Items.DIRT) != dirtPass) {
          continue;
        }
        keep[slot] = Math.min(budget, stack.getCount());
        budget -= keep[slot];
      }
    }
    return keep;
  }

  /** Spoil in the pack beyond the fill kept back. */
  private static int spoilToShelve(RealPerson person) {
    Container pack = person.personMainInv;
    int[] keep = fillToKeep(pack);
    int total = 0;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      if (spoil(stack)) {
        total += stack.getCount() - keep[slot];
      }
    }
    return total;
  }

  /** The earth in the pack to fill with, dirt before any other, or null when there is none. */
  @Nullable
  private static Item fillInHand(RealPerson person, boolean stableOnly) {
    if (PackLogistics.carried(person, Items.DIRT) > 0) {
      return Items.DIRT;
    }
    Container pack = person.personMainInv;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      if (fillBlock(stack, stableOnly)) {
        return stack.getItem();
      }
    }
    return null;
  }

  /**
   * Earth that can be set down as a full block of ground: any soft ground the
   * grading digs up except a trodden path, which is not a whole block.
   */
  private static boolean fillBlock(ItemStack stack) {
    return fillBlock(stack, false);
  }

  /** A full earth block, optionally excluding sand-like blocks that would fall through a cover. */
  private static boolean fillBlock(ItemStack stack, boolean stableOnly) {
    return !stack.isEmpty() && stack.getItem() instanceof BlockItem block
        && block.getBlock().defaultBlockState().is(GradingSurvey.GRADEABLE)
        && block.getBlock() != Blocks.DIRT_PATH
        && (!stableOnly || !(block.getBlock() instanceof FallingBlock));
  }

  /** What grading digs up: the ground itself, and what gravel and clay break into. */
  private static boolean spoil(ItemStack stack) {
    if (stack.isEmpty()) {
      return false;
    }
    if (stack.is(Items.FLINT) || stack.is(Items.CLAY_BALL)) {
      return true;
    }
    return stack.getItem() instanceof BlockItem block
        && block.getBlock().defaultBlockState().is(GradingSurvey.GRADEABLE);
  }

}
