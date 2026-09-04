package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A wall being raised around a village (docs/walls.md): the ring it follows, the
 * tier it is built to, and how far along that ring the builder has got.
 *
 * Not a {@link StructureInProgress}: a wall is a route, not a fixed footprint, so
 * it carries its own ring of ground columns rather than an NBT template. The
 * builder walks the ring column by column (the cursor), raising a segment at each
 * on whatever surface it finds there, so the wall steps with the terrain. The
 * ring is traced once, when the wall is first raised, and kept so the stone
 * upgrade can re-walk exactly the same line. Each column's natural ground is
 * captured alongside it, so seam-closing reads true ground and not a neighbour's
 * freshly raised wall.
 */
public final class WallProject {

  public static final Codec<WallProject> CODEC = RecordCodecBuilder.create(inst -> inst.group(
      Codec.LONG.listOf().fieldOf("ring").forGetter(w -> w.ring),
      Codec.LONG.listOf().fieldOf("gates").forGetter(w -> List.copyOf(w.gates)),
      Codec.INT.listOf().optionalFieldOf("ground", List.of()).forGetter(w -> w.ground),
      Codec.STRING.fieldOf("tier").forGetter(w -> w.tier.name()),
      Codec.INT.fieldOf("cursor").forGetter(w -> w.progress.cursor()),
      Codec.INT.listOf().optionalFieldOf("deferred", List.of()).forGetter(w -> w.progress.deferred())
  ).apply(inst, WallProject::fromCodec));

  private final List<Long> ring;
  private final Set<Long> gates;
  private final List<Integer> ground;
  private final WallTier tier;
  private WallProgress.State progress;

  public WallProject(List<Long> ring, Set<Long> gates, List<Integer> ground, WallTier tier) {
    this(ring, gates, ground, tier, 0, new ArrayList<>());
  }

  /** A wall recorded as already fully built, for the dev command that rings a village at once. */
  public static WallProject completed(List<Long> ring, Set<Long> gates, List<Integer> ground,
      WallTier tier) {
    return new WallProject(ring, gates, ground, tier, ring.size(), new ArrayList<>());
  }

  private WallProject(List<Long> ring, Set<Long> gates, List<Integer> ground, WallTier tier,
      int cursor, List<Integer> deferred) {
    this.ring = ring;
    this.gates = gates;
    this.ground = new ArrayList<>(ground);
    this.tier = tier;
    this.progress = new WallProgress.State(cursor, deferred);
  }

  private static WallProject fromCodec(List<Long> ring, List<Long> gates, List<Integer> ground,
      String tier, int cursor, List<Integer> deferred) {
    return new WallProject(new ArrayList<>(ring), new HashSet<>(gates), new ArrayList<>(ground),
        WallTier.valueOf(tier), cursor, new ArrayList<>(deferred));
  }

  /** True once every column on the ring has been raised. */
  public boolean isComplete() {
    return WallProgress.isComplete(this.progress, this.ring.size());
  }

  /** The next ring column to raise, packed as a y=0 BlockPos long. */
  public long nextColumn() {
    return this.ring.get(currentIndex());
  }

  /** Step the cursor on to the next column. */
  public void advance() {
    this.progress = WallProgress.advance(this.progress, this.ring.size());
  }

  /**
   * Postpones the current column and continues around the ring. Deferred
   * columns are retried after every ordinary column has had its turn.
   */
  public void defer() {
    this.progress = WallProgress.defer(this.progress, this.ring.size());
  }

  /** The ring index currently being worked, including a deferred retry. */
  public int currentIndex() {
    return WallProgress.currentIndex(this.progress, this.ring.size());
  }

  /** Corrects a saved vegetation-height reading without ever raising terrain. */
  public void lowerCurrentGround(int height) {
    if (hasGround()) {
      int index = currentIndex();
      this.ground.set(index, Math.min(this.ground.get(index), height));
    }
  }

  /** Whether this column is a gateway, its two ground courses given over to a door. */
  public boolean isGate(long column) {
    return this.gates.contains(column);
  }

  /** Whether the natural-ground profile is present (absent on walls saved before it existed). */
  public boolean hasGround() {
    return this.ground.size() == this.ring.size();
  }

  /** The natural ground height captured at ring index {@code i}. */
  public int groundAt(int i) {
    return this.ground.get(i);
  }

  /** The seam floor at ring index {@code i}: the lowest ground of it and its two neighbours. */
  public int seamFloor(int i) {
    return WallRaiser.seamFloor(this.ground, i);
  }

  public WallTier getTier() {
    return this.tier;
  }

  public List<Long> getRing() {
    return this.ring;
  }

  public Set<Long> getGates() {
    return this.gates;
  }

  public List<Integer> getGround() {
    return this.ground;
  }

  public int getCursor() {
    return this.progress.cursor();
  }

  public int size() {
    return this.ring.size();
  }
}
