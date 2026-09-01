package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.ai.goals.ShortageWatch;
import com.quzzar.villagelife.savedata.GradedColumnStore;
import com.quzzar.villagelife.savedata.PlacedBlockStore;
import com.quzzar.villagelife.village.GradingSurvey;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.buildings.SitePreparation;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
 *
 * Planning first and then executing matters: a plan is a single walkable
 * surface, so working it produces one, whereas re-reading the ground after
 * every block chased a target that moved with each block and left dips and
 * odd steps on steep ground. It is also far cheaper.
 *
 * What it will not touch is the point of it. Only soft natural ground
 * ({@code villagelife:gradeable}) is moved: never a block the village placed,
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

  private enum Kind {
    CUT, FILL, FETCH_EARTH, SHELVE_SPOIL
  }

  /** One column of the plan: where its top stands now, and where the plan wants it. */
  private static final class Planned {

    private final int x;
    private final int z;
    private final int target;
    private int height;

    private Planned(GradingSurvey.Column column) {
      this.x = column.x();
      this.z = column.z();
      this.height = column.height();
      this.target = column.target();
    }

    private boolean done() {
      return height == target;
    }

    private boolean wantsCut() {
      return target < height;
    }

    /** The block a cut takes: the column's top. */
    private BlockPos top() {
      return new BlockPos(x, height, z);
    }

    /** The block a fill sets: the air over the column's top. */
    private BlockPos spot() {
      return new BlockPos(x, height + 1, z);
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
    @Nullable
    private Planned column;
    @Nullable
    private Item fillWith;
    private int ticks;
    private int shownProgress = -1;
    private boolean fetchFailed;
    private boolean shelveFailed;

    /** The columns still to move, from the last reading of the ground. */
    private final List<Planned> plan = new ArrayList<>();

    /** Spots this job found it could not work after all; left alone until the next job. */
    private final LongOpenHashSet passedOver = new LongOpenHashSet();

    private void set(Kind kind, BlockPos pos, @Nullable Planned column) {
      this.kind = kind;
      this.pos = pos;
      this.column = column;
      this.fillWith = null;
      this.ticks = 0;
      this.shownProgress = -1;
    }
  }

  private final ShortageWatch dry = new ShortageWatch();

  /** Whether the last reading that planned work the builder could not do has been logged. */
  private boolean idleReported;

  @Override
  @Nullable
  public Job select(RealPerson person) {
    Village village = person.getVillage();
    if (village == null || village.getCurrentProject() != null) {
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
    if (village == null || village.getCurrentProject() != null
        || !(person.level() instanceof ServerLevel level)) {
      return false;
    }
    boolean finished = switch (job.kind) {
      case CUT -> cut(person, level, job);
      case FILL -> fill(person, level, job);
      case FETCH_EARTH -> fetchEarth(person, job);
      case SHELVE_SPOIL -> shelveSpoil(person, job);
    };
    if (!finished) {
      return true;
    }
    return aim(person, level, village, job); // the next block, or the job is over
  }

  @Override
  public BlockPos positionOf(Job job) {
    return job.pos;
  }

  @Override
  public void released(RealPerson person, Job job) {
    if (job.kind == Kind.CUT) {
      person.level().destroyBlockProgress(person.getId(), job.pos, -1);
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
    int cuts = 0;
    for (GradingSurvey.Column column : survey.uneven(person.blockPosition())) {
      job.plan.add(new Planned(column));
      cuts += column.wantsCut() ? 1 : 0;
    }
    boolean found = pick(person, level, village, job, true);
    // One line per plan, and one per stretch of wanting work it cannot do:
    // an idle builder re-reads the ground every few seconds, which is not news.
    if (found || (!job.plan.isEmpty() && !this.idleReported)) {
      Villagelife.LOGGER.debug("[grading] {} ({}) read the ground of '{}': {} column(s) to cut, {} to fill; {}",
          person.getName().getString(), person.getOccupation(), village.getName(), cuts, job.plan.size() - cuts,
          found ? "next is " + job.kind + " at " + job.pos.toShortString() : "nothing doable now, job over");
    }
    this.idleReported = !found;
    return found;
  }

  /**
   * Aims at the nearest column of the plan the builder can move right now, or
   * at a chest holding the earth the plan needs. The nearest uneven column is
   * taken, cut or fill; only when nothing is left but filling and the pack
   * holds nothing to fill with does the builder go to a chest for earth, so
   * cutting sorts itself ahead of fetching, because cutting is what makes
   * fill. False when nothing in the plan can be done now.
   */
  private boolean pick(RealPerson person, ServerLevel level, Village village, Job job, boolean reportShortage) {
    GradedColumnStore graded = GradedColumnStore.get(level);
    Item fillInHand = fillInHand(person);
    boolean packFull = person.isInventoryFull();
    boolean fillWanted = false;
    BlockPos from = person.blockPosition();
    job.plan.removeIf(Planned::done);
    job.plan.sort(Comparator.comparingLong(column -> column.distSqr(from)));
    for (Planned column : job.plan) {
      if (column.wantsCut()) {
        BlockPos top = column.top();
        if (packFull || job.passedOver.contains(top.asLong())
            || !withinBudget(graded, column, -1) || !mayCut(level, top)) {
          continue;
        }
        job.set(Kind.CUT, top, column);
        this.dry.foundWork();
        return true;
      }
      BlockPos spot = column.spot();
      if (job.passedOver.contains(spot.asLong()) || !withinBudget(graded, column, 1) || !mayFill(level, spot)) {
        continue;
      }
      if (fillInHand == null) {
        fillWanted = true;
        continue;
      }
      job.set(Kind.FILL, spot, column);
      job.fillWith = fillInHand;
      this.dry.foundWork();
      return true;
    }

    if (fillWanted) {
      BlockPos chest = job.fetchFailed ? null : PackLogistics.chestHolding(person, village, earthWanted());
      if (chest != null) {
        job.set(Kind.FETCH_EARTH, chest, null);
        this.dry.foundWork();
        return true;
      }
      if (reportShortage) {
        this.dry.wentDry(person, Items.DIRT, FILL_RESERVE,
            "We have no earth to grade the ground between our buildings.");
      }
    }
    return false;
  }

  /**
   * The levelling budget, and the rule that a column never turns back: it may
   * stand at most {@link SitePreparation#MAX_COLUMN_DELTA} above or below the
   * height it had before grading, and one that has been raised is never cut
   * again nor a cut one refilled, so grading always ends.
   */
  private static boolean withinBudget(GradedColumnStore graded, Planned column, int delta) {
    int original = graded.originalHeight(column.x, column.z, column.height);
    if (Math.abs(column.height + delta - original) > SitePreparation.MAX_COLUMN_DELTA) {
      return false;
    }
    return delta < 0 ? column.height <= original : column.height >= original;
  }

  /**
   * Whether the top block of a column may be dug out: soft ground that is
   * nobody's structure, with nothing but loose cover on it and no water or
   * lava beside it to pour into the hole.
   */
  private static boolean mayCut(ServerLevel level, BlockPos top) {
    BlockState state = level.getBlockState(top);
    if (!state.is(GradingSurvey.GRADEABLE) || level.getBlockEntity(top) != null
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

  /** Whether a block of fill may be set here: loose cover over solid ground, and nobody standing in it. */
  private static boolean mayFill(ServerLevel level, BlockPos spot) {
    if (!looseCover(level, spot) || !level.getFluidState(spot).isEmpty()) {
      return false;
    }
    BlockState below = level.getBlockState(spot.below());
    if (below.isAir() || !below.getFluidState().isEmpty()) {
      return false;
    }
    return level.isUnobstructed(Blocks.DIRT.defaultBlockState(), spot, CollisionContext.empty());
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
    if (!mayCut(level, top)) {
      job.passedOver.add(top.asLong());
      return true; // changed under them; on to the next
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

  /**
   * One block of fill set from the pack: the earth the builder dug, dirt
   * first. Fill is deliberately not recorded as village-placed: earth is not
   * a structure (docs/block-ownership.md), and a later cut may take it back.
   */
  private boolean fill(RealPerson person, ServerLevel level, Job job) {
    BlockPos spot = job.pos;
    if (!mayFill(level, spot)) {
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
    GradedColumnStore.get(level).recordBeforeGrading(spot.getX(), spot.getZ(), spot.getY() - 1);
    BlockState fill = Block.byItem(earth.getItem()).defaultBlockState();
    level.setBlock(spot, fill, 3);
    level.playSound((Player) null, spot, fill.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F,
        person.getRandom().nextFloat() * 0.4F + 0.8F);
    if (job.column != null) {
      job.column.height++;
    }
    return true;
  }

  /**
   * A chest visit that tops the pack up to its working reserve of fill: dirt
   * first, then whatever other earth the chest holds, until the reserve is
   * met.
   */
  private boolean fetchEarth(RealPerson person, Job job) {
    Container chest = PackLogistics.containerAt(person, job.pos);
    int pulled = 0;
    if (chest != null) {
      for (Item earth : earthItems()) {
        int need = FILL_RESERVE - fillCarried(person);
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
      Villagelife.LOGGER.debug("[resource-flow] {} ({}) fetched {} earth from a chest for grading",
          person.getName().getString(), person.getOccupation(), pulled);
    } else {
      job.fetchFailed = true; // the chest emptied, or the pack is full: not again this job
    }
    return true;
  }

  /** How much earth the pack holds that could be set as fill. */
  private static int fillCarried(RealPerson person) {
    Container pack = person.personMainInv;
    int total = 0;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      if (fillBlock(stack)) {
        total += stack.getCount();
      }
    }
    return total;
  }

  /** Every earth a chest might hold, as a reserve's worth each, for finding the chest to go to. */
  private static List<ItemStack> earthWanted() {
    List<ItemStack> wanted = new ArrayList<>();
    for (Item earth : earthItems()) {
      wanted.add(new ItemStack(earth, FILL_RESERVE));
    }
    return wanted;
  }

  /**
   * The earths that can be set as fill, dirt first and then the rest of the
   * {@code villagelife:gradeable} tag, so a pack author's ground joins in.
   */
  private static List<Item> earthItems() {
    List<Item> items = new ArrayList<>();
    items.add(Items.DIRT);
    BuiltInRegistries.BLOCK.getTag(GradingSurvey.GRADEABLE).ifPresent(blocks -> {
      for (Holder<Block> holder : blocks) {
        Item item = holder.value().asItem();
        if (item != Items.AIR && item != Items.DIRT && fillBlock(new ItemStack(item))) {
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
      Villagelife.LOGGER.debug("[resource-flow] {} ({}) shelved {} item(s) of spoil into a chest",
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
  private static Item fillInHand(RealPerson person) {
    if (PackLogistics.carried(person, Items.DIRT) > 0) {
      return Items.DIRT;
    }
    Container pack = person.personMainInv;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      if (fillBlock(stack)) {
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
    return !stack.isEmpty() && stack.getItem() instanceof BlockItem block
        && block.getBlock().defaultBlockState().is(GradingSurvey.GRADEABLE)
        && block.getBlock() != Blocks.DIRT_PATH;
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
