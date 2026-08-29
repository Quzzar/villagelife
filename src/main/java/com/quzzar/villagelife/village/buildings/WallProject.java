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
 * upgrade can re-walk exactly the same line.
 */
public final class WallProject {

  public static final Codec<WallProject> CODEC = RecordCodecBuilder.create(inst -> inst.group(
      Codec.LONG.listOf().fieldOf("ring").forGetter(w -> w.ring),
      Codec.LONG.listOf().fieldOf("gates").forGetter(w -> List.copyOf(w.gates)),
      Codec.STRING.fieldOf("tier").forGetter(w -> w.tier.name()),
      Codec.INT.fieldOf("cursor").forGetter(w -> w.cursor)
  ).apply(inst, WallProject::fromCodec));

  private final List<Long> ring;
  private final Set<Long> gates;
  private final WallTier tier;
  private int cursor;

  public WallProject(List<Long> ring, Set<Long> gates, WallTier tier) {
    this(ring, gates, tier, 0);
  }

  /** A wall recorded as already fully built, for the dev command that rings a village at once. */
  public static WallProject completed(List<Long> ring, Set<Long> gates, WallTier tier) {
    return new WallProject(ring, gates, tier, ring.size());
  }

  private WallProject(List<Long> ring, Set<Long> gates, WallTier tier, int cursor) {
    this.ring = ring;
    this.gates = gates;
    this.tier = tier;
    this.cursor = cursor;
  }

  private static WallProject fromCodec(List<Long> ring, List<Long> gates, String tier, int cursor) {
    return new WallProject(new ArrayList<>(ring), new HashSet<>(gates),
        WallTier.valueOf(tier), cursor);
  }

  /** True once every column on the ring has been raised. */
  public boolean isComplete() {
    return this.cursor >= this.ring.size();
  }

  /** The next ring column to raise, packed as a y=0 BlockPos long. */
  public long nextColumn() {
    return this.ring.get(this.cursor);
  }

  /** Step the cursor on to the next column. */
  public void advance() {
    this.cursor++;
  }

  /** Whether this column is a gateway, left open below rather than walled solid. */
  public boolean isGate(long column) {
    return this.gates.contains(column);
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

  public int getCursor() {
    return this.cursor;
  }

  public int size() {
    return this.ring.size();
  }
}
