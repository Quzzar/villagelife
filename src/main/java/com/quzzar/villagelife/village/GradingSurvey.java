package com.quzzar.villagelife.village;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.savedata.PlacedBlockStore;
import com.quzzar.villagelife.village.buildings.Building;
import com.quzzar.villagelife.village.buildings.SitePreparation;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * One reading of the ground around a village's buildings, and the walkable
 * surface each column is being graded toward (docs/worker-loops.md, "The
 * builder builds, and between builds it makes the village walkable").
 *
 * The rule the survey encodes is walkability: between two neighbouring
 * columns the ground should never step more than one block, which is the step
 * a villager climbs without jumping. Everything else follows from choosing
 * each column's target with care:
 *
 * <ul>
 * <li><b>Each column is read once.</b> A column of soft ground (the
 * {@code villagelife:gradeable} tag: dirt, grass, sand, gravel, clay) can be
 * moved. Ground the village has claimed for a building stands at that
 * building's own plane and is fixed, so graded ground meets a building's
 * floor rather than stopping a step short of it. Natural rock (the
 * {@code villagelife:firm_ground} tag: stone, terracotta, sandstone, ice) is
 * fixed too: the builder has no tool for it, and it is the shape of the land.
 * Everything else is not ground at all and takes no part: water, trees,
 * anything the village or a player built, and any block the two tags do not
 * name. That last rule was learned live. Rock used to be "whatever is left",
 * so a mangrove's stilt roots read as a rock ledge four blocks up and the
 * builder set about raising the whole swamp toward the trees. The edge of the
 * area is ground like any other; what lies beyond it is not read, so a step
 * may remain where the village's ground meets the world's.</li>
 * <li><b>A cliff is a feature.</b> Neighbours that differ by more than
 * {@link #MAX_STEP} are not connected, so nothing ramps up to a cliff or
 * chews its crown down.</li>
 * <li><b>The target is the middle of the envelope.</b> Every column's target
 * sits halfway between the steepest one-step surface that fits under the
 * ground and the one that fits over it, so cut and fill come out roughly even
 * and a slope is spread across the ground rather than pushed uphill or down.
 * That middle is then clamped to what the fixed columns allow, where they
 * agree among themselves; two fixed columns too close to ramp between impose
 * nothing, rather than dragging the ground between them to one side.</li>
 * </ul>
 *
 * The survey is a snapshot. The builder re-reads it after every block moved,
 * so a target only ever asks for the next single step, and the per-column
 * budget kept in {@code GradedColumnStore} is what bounds the whole process.
 */
public final class GradingSurvey {

  public static final TagKey<Block> GRADEABLE = TagKey.create(Registries.BLOCK,
      ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "gradeable"));

  /** Natural ground the builder cannot move but grades up to: the shape of the land. */
  public static final TagKey<Block> FIRM_GROUND = TagKey.create(Registries.BLOCK,
      ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "firm_ground"));

  /** How far past the buildings' own ground the village grades. */
  public static final int MARGIN = 8;

  /** The widest area one survey reads, so a sprawling village's scan stays bounded. */
  public static final int MAX_SPAN = 96;

  /**
   * A step taller than this between neighbours is a cliff: left alone, and
   * never ramped to. One more than the levelling budget, so a fixed column can
   * always be met within it.
   */
  public static final int MAX_STEP = SitePreparation.MAX_COLUMN_DELTA + 1;

  /** Most clearable cover the reading skips on its way down to the ground under it. */
  private static final int COVER_DEPTH = 4;

  /**
   * Diffusion passes that relax a worn path toward a straight ramp between the
   * fixed ground it runs between. More passes carry the ramp further along a
   * long path; a path longer than this smooths locally rather than end to end,
   * which is enough to read as a graded route.
   */
  private static final int PATH_SMOOTH_PASSES = 32;

  /** The four cardinal neighbours, for the path diffusion. */
  private static final int[][] NEIGHBOURS = { {-1, 0}, {1, 0}, {0, -1}, {0, 1} };

  private static final int NO_GROUND = Integer.MIN_VALUE;

  /** An envelope value no source reaches. */
  private static final int FAR = Integer.MAX_VALUE / 2;

  /**
   * A column the survey would move: its ground height now (the Y of its top
   * block) and the height it should stand at.
   */
  public record Column(int x, int z, int height, int target) {

    public boolean wantsCut() {
      return target < height;
    }
  }

  private final int minX;
  private final int minZ;
  private final int width;
  private final int depth;
  private final int[] height;
  private final boolean[] fixed;
  /** Columns whose top is a worn path, given a gentler grade than the rest. */
  private final boolean[] path;
  private final int[] target;

  private GradingSurvey(int minX, int minZ, int width, int depth) {
    this.minX = minX;
    this.minZ = minZ;
    this.width = width;
    this.depth = depth;
    this.height = new int[width * depth];
    this.fixed = new boolean[width * depth];
    this.path = new boolean[width * depth];
    this.target = new int[width * depth];
  }

  /**
   * Reads the ground around every building of the village, out to
   * {@link #MARGIN} past their footprints and clamped to {@link #MAX_SPAN}
   * about the town centre. Null for a village with nothing built.
   */
  @Nullable
  public static GradingSurvey of(ServerLevel level, Village village) {
    Collection<Building> buildings = village.getBuildings();
    if (buildings.isEmpty()) {
      return null;
    }
    int minX = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int minZ = Integer.MAX_VALUE;
    int maxZ = Integer.MIN_VALUE;
    for (Building building : buildings) {
      BlockPos centre = BlockPos.of(building.getCenterLocation());
      int radius = (int) Math.ceil(building.getRadius());
      minX = Math.min(minX, centre.getX() - radius);
      maxX = Math.max(maxX, centre.getX() + radius);
      minZ = Math.min(minZ, centre.getZ() - radius);
      maxZ = Math.max(maxZ, centre.getZ() + radius);
    }
    minX -= MARGIN;
    maxX += MARGIN;
    minZ -= MARGIN;
    maxZ += MARGIN;

    Building townCenter = village.getTownCenter();
    int midX = townCenter != null ? BlockPos.of(townCenter.getCenterLocation()).getX() : (minX + maxX) / 2;
    int midZ = townCenter != null ? BlockPos.of(townCenter.getCenterLocation()).getZ() : (minZ + maxZ) / 2;
    if (maxX - minX + 1 > MAX_SPAN) {
      minX = midX - MAX_SPAN / 2;
      maxX = minX + MAX_SPAN - 1;
    }
    if (maxZ - minZ + 1 > MAX_SPAN) {
      minZ = midZ - MAX_SPAN / 2;
      maxZ = minZ + MAX_SPAN - 1;
    }

    GradingSurvey survey = new GradingSurvey(minX, minZ, maxX - minX + 1, maxZ - minZ + 1);
    survey.read(level, village, buildings);
    survey.aim();
    return survey;
  }

  /** The columns standing off their target, nearest to {@code from} first. */
  public List<Column> uneven(BlockPos from) {
    List<Column> out = new ArrayList<>();
    for (int i = 0; i < height.length; i++) {
      if (height[i] != NO_GROUND && !fixed[i] && target[i] != height[i]) {
        out.add(new Column(minX + i % width, minZ + i / width, height[i], target[i]));
      }
    }
    out.sort(Comparator.comparingLong(column -> {
      long dx = column.x() - from.getX();
      long dz = column.z() - from.getZ();
      return dx * dx + dz * dz;
    }));
    return out;
  }

  /**
   * Reads every column: what stands on top once loose cover is looked past,
   * and whether the builder may move it, must respect it, or should ignore it.
   */
  private void read(ServerLevel level, Village village, Collection<Building> buildings) {
    PlacedBlockStore placed = PlacedBlockStore.get(level);
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int z = 0; z < depth; z++) {
      for (int x = 0; x < width; x++) {
        int i = z * width + x;
        int worldX = minX + x;
        int worldZ = minZ + z;
        cursor.set(worldX, 0, worldZ);
        if (!level.isLoaded(cursor)) {
          height[i] = NO_GROUND;
          continue;
        }
        boolean claimed = village.hasClaimed(cursor);
        fixed[i] = claimed;
        if (claimed) {
          // A building's ground is its plane, whatever the roof above it reads.
          Building owner = buildingOver(buildings, worldX, worldZ);
          if (owner != null) {
            // A sunk building's origin is below its ground; the plane is what
            // the surrounding ground is graded to.
            int sink = owner.getInfo() == null ? 0 : owner.getInfo().getSink();
            height[i] = BlockPos.of(owner.getOriginLocation()).getY() + sink;
            continue;
          }
        }

        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) - 1;
        BlockState top = level.getBlockState(cursor.set(worldX, y, worldZ));
        for (int skipped = 0; skipped < COVER_DEPTH && top.is(SitePreparation.CLEARABLE); skipped++) {
          top = level.getBlockState(cursor.set(worldX, --y, worldZ));
        }

        if (top.isAir() || top.is(SitePreparation.CLEARABLE) || !top.getFluidState().isEmpty()
            || placed.isVillagePlaced(cursor) || placed.isPlayerPlaced(cursor)) {
          height[i] = NO_GROUND; // void, a tall tree, water, or somebody's structure
        } else if (top.is(GRADEABLE)) {
          height[i] = y;
          path[i] = top.is(Blocks.DIRT_PATH);
        } else if (top.is(FIRM_GROUND)) {
          height[i] = y; // rock: ground, but the shape of the land
          fixed[i] = true;
        } else {
          height[i] = NO_GROUND; // a stilt root, a fence, a mushroom stem: not ground
        }
      }
    }
  }

  /** The building whose circle covers this column, nearest first, or null. */
  @Nullable
  private static Building buildingOver(Collection<Building> buildings, int x, int z) {
    Building nearest = null;
    double nearestSqr = Double.MAX_VALUE;
    for (Building building : buildings) {
      BlockPos centre = BlockPos.of(building.getCenterLocation());
      double dx = centre.getX() - x;
      double dz = centre.getZ() - z;
      double distSqr = dx * dx + dz * dz;
      double radius = building.getRadius();
      if (distSqr <= radius * radius && distSqr < nearestSqr) {
        nearestSqr = distSqr;
        nearest = building;
      }
    }
    return nearest;
  }

  /**
   * Chooses every movable column's target: the middle of the one-step envelope
   * over all ground, clamped to the envelope the fixed columns impose.
   */
  private void aim() {
    int n = height.length;
    boolean[] ground = new boolean[n];
    boolean[] anchors = new boolean[n];
    int[] negated = new int[n];
    for (int i = 0; i < n; i++) {
      ground[i] = height[i] != NO_GROUND;
      anchors[i] = ground[i] && fixed[i];
      negated[i] = ground[i] ? -height[i] : 0;
    }
    int[] under = lowerEnvelope(height, ground);
    int[] overNegated = lowerEnvelope(negated, ground);
    int[] ceiling = lowerEnvelope(height, anchors);
    int[] floorNegated = lowerEnvelope(negated, anchors);

    for (int i = 0; i < n; i++) {
      if (!ground[i] || fixed[i]) {
        target[i] = height[i];
        continue;
      }
      int middle = Math.floorDiv(under[i] - overNegated[i], 2);
      int floor = floorNegated[i] == FAR ? -FAR : -floorNegated[i];
      // The fixed columns' own envelope is a hard bound where it is consistent.
      // Where two fixed columns stand too close to ramp between, it says
      // nothing, and the middle of the soft envelope stands.
      target[i] = floor <= ceiling[i] ? Math.max(floor, Math.min(ceiling[i], middle)) : middle;
    }
    smoothPaths();
  }

  /**
   * Overrides the target of every worn-path column with a gentler grade than the
   * rest of the ground gets: an established route should climb evenly rather than
   * trace the ≤1-step surface every other column is aimed at. Each path column
   * relaxes toward the average of the path and the fixed ground it touches (a
   * building's plane, natural rock), so a path between two buildings settles
   * toward a straight ramp between them. Free, still-uneven ground beside the
   * path is deliberately left out of the average, or a bumpy shoulder would pull
   * the path back into its bumps.
   *
   * Two rules keep it honest. Every column is held within the levelling budget of
   * where it stands now ({@link SitePreparation#MAX_COLUMN_DELTA}), so the path
   * smooths the surface and never sinks a trench; the per-original-height record
   * in {@code GradedColumnStore} enforces the same against the true start when the
   * work runs. And a final pass keeps no path column more than one block above a
   * path neighbour, lowering peaks only so it always settles, so the way the
   * builder grades stays one a villager can walk.
   */
  private void smoothPaths() {
    boolean any = false;
    for (boolean isPath : path) {
      if (isPath) {
        any = true;
        break;
      }
    }
    if (!any) {
      return; // no worn path: the grading is exactly as it was
    }

    int n = height.length;
    int delta = SitePreparation.MAX_COLUMN_DELTA;
    // The banks the path relaxes toward hold still at their own height; only the
    // path itself moves. A fixed column stands at its plane, a path column starts
    // where it stands and drifts toward its neighbours.
    int[] smooth = height.clone();
    for (int pass = 0; pass < PATH_SMOOTH_PASSES; pass++) {
      int[] next = smooth.clone();
      for (int i = 0; i < n; i++) {
        if (!path[i]) {
          continue;
        }
        int x = i % width;
        int z = i / width;
        int sum = 0;
        int count = 0;
        for (int[] step : NEIGHBOURS) {
          int j = neighbour(x, z, step);
          if (j < 0 || height[j] == NO_GROUND || !(path[j] || fixed[j]) || !connected(i, j)) {
            continue;
          }
          sum += smooth[j];
          count++;
        }
        if (count == 0) {
          continue;
        }
        int aimed = Math.floorDiv(sum + count / 2, count);
        next[i] = Math.max(height[i] - delta, Math.min(height[i] + delta, aimed));
      }
      smooth = next;
    }

    // No path column stands more than one step above a path neighbour, so the
    // graded way is always walkable. Lowering only, which always converges.
    boolean changed = true;
    for (int guard = 0; guard <= width + depth && changed; guard++) {
      changed = false;
      for (int i = 0; i < n; i++) {
        if (!path[i]) {
          continue;
        }
        int x = i % width;
        int z = i / width;
        for (int[] step : NEIGHBOURS) {
          int j = neighbour(x, z, step);
          if (j >= 0 && path[j] && connected(i, j) && smooth[i] > smooth[j] + 1) {
            smooth[i] = smooth[j] + 1;
            changed = true;
          }
        }
      }
    }

    for (int i = 0; i < n; i++) {
      if (path[i] && height[i] != NO_GROUND) {
        target[i] = smooth[i];
      }
    }
  }

  /** The grid index of a cardinal neighbour, or -1 off the edge. */
  private int neighbour(int x, int z, int[] step) {
    int nx = x + step[0];
    int nz = z + step[1];
    if (nx < 0 || nz < 0 || nx >= width || nz >= depth) {
      return -1;
    }
    return nz * width + nx;
  }

  /**
   * The lowest one-step surface at or below the sources: at every column, the
   * least of (source value plus walking distance) over the sources it is
   * connected to, or {@link #FAR} when none is. Passing negated heights gives
   * the highest surface at or above them.
   *
   * Dial's algorithm: unit steps and integer values make one bucket per value
   * exact and linear in the grid.
   */
  private int[] lowerEnvelope(int[] value, boolean[] isSource) {
    int n = height.length;
    int[] out = new int[n];
    int low = Integer.MAX_VALUE;
    int high = Integer.MIN_VALUE;
    for (int i = 0; i < n; i++) {
      out[i] = FAR;
      if (isSource[i]) {
        low = Math.min(low, value[i]);
        high = Math.max(high, value[i]);
      }
    }
    if (low == Integer.MAX_VALUE) {
      return out; // nothing to measure from
    }
    // A value rises by one per step, and no path visits a column twice.
    IntArrayList[] buckets = new IntArrayList[high - low + 1 + n];
    for (int i = 0; i < n; i++) {
      if (isSource[i]) {
        out[i] = value[i];
        enqueue(buckets, value[i] - low, i);
      }
    }
    for (int slot = 0; slot < buckets.length; slot++) {
      IntArrayList bucket = buckets[slot];
      if (bucket == null) {
        continue;
      }
      for (int j = 0; j < bucket.size(); j++) {
        int i = bucket.getInt(j);
        if (out[i] != slot + low) {
          continue; // bettered since it was queued
        }
        int x = i % width;
        int z = i / width;
        relax(out, buckets, low, i, x - 1, z);
        relax(out, buckets, low, i, x + 1, z);
        relax(out, buckets, low, i, x, z - 1);
        relax(out, buckets, low, i, x, z + 1);
      }
    }
    return out;
  }

  private void relax(int[] out, IntArrayList[] buckets, int low, int from, int x, int z) {
    if (x < 0 || z < 0 || x >= width || z >= depth) {
      return;
    }
    int to = z * width + x;
    if (!connected(from, to)) {
      return;
    }
    int candidate = out[from] + 1;
    if (candidate >= out[to]) {
      return;
    }
    out[to] = candidate;
    enqueue(buckets, candidate - low, to);
  }

  private static void enqueue(IntArrayList[] buckets, int slot, int column) {
    if (buckets[slot] == null) {
      buckets[slot] = new IntArrayList();
    }
    buckets[slot].add(column);
  }

  /** Two neighbouring columns of ground with no cliff between them. */
  private boolean connected(int a, int b) {
    return height[a] != NO_GROUND && height[b] != NO_GROUND
        && Math.abs(height[a] - height[b]) <= MAX_STEP;
  }

}
