package com.quzzar.kithkyn.savedata;

import com.quzzar.kithkyn.Kithkyn;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The height each graded column stood at before a village first moved it,
 * for one level: the reference that keeps grading to the surface of the land
 * rather than its shape (docs/site-selection.md).
 *
 * A builder grading the ground between buildings moves a column one block at
 * a time toward a walkable slope. The height recorded here is both the stable
 * input to every new survey and the budget reference for every move: no column
 * ever stands more than the levelling budget above or below where it started.
 * Without it, interrupting and replanning could move a locally reasonable
 * target one step at a time into a pit or plateau.
 *
 * Only columns that have actually been touched are recorded, as packed
 * positions whose Y is the original height, so a whole village is tens of
 * kilobytes. Records are never dropped: the height a column had before the
 * village came is a fact about the world, and it stays true. Stored per level
 * for the same reason {@link PlacedBlockStore} is, and accessed through
 * {@link #get}.
 */
public final class GradedColumnStore extends SavedData {

  private static final String DATA_NAME = Kithkyn.MODID + "~graded_columns";

  public static final SavedData.Factory<GradedColumnStore> FACTORY = new SavedData.Factory<>(
      GradedColumnStore::new, GradedColumnStore::load, null);

  /** Column key ({@code x, 0, z} packed) to the surface height before grading. */
  private final Long2IntOpenHashMap originalHeights = new Long2IntOpenHashMap();

  public GradedColumnStore() {
  }

  /** The store for this level, created on first access and persisted with it. */
  public static GradedColumnStore get(ServerLevel level) {
    return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
  }

  public static GradedColumnStore load(CompoundTag tag, HolderLookup.Provider registries) {
    GradedColumnStore data = new GradedColumnStore();
    for (long packed : tag.getLongArray("Graded")) {
      BlockPos pos = BlockPos.of(packed);
      data.originalHeights.put(columnKey(pos.getX(), pos.getZ()), pos.getY());
    }
    return data;
  }

  @Override
  public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    long[] packed = new long[originalHeights.size()];
    int i = 0;
    for (Long2IntMap.Entry entry : originalHeights.long2IntEntrySet()) {
      BlockPos column = BlockPos.of(entry.getLongKey());
      packed[i++] = BlockPos.asLong(column.getX(), entry.getIntValue(), column.getZ());
    }
    tag.put("Graded", new LongArrayTag(packed));
    return tag;
  }

  /**
   * The height this column stood at before any grading, or {@code current}
   * when it has never been graded.
   */
  public int originalHeight(int x, int z, int current) {
    return originalHeights.getOrDefault(columnKey(x, z), current);
  }

  /**
   * Records the height a column stands at just before its first grading move.
   * Later moves leave the record alone: the original is the original.
   */
  public void recordBeforeGrading(int x, int z, int height) {
    long key = columnKey(x, z);
    if (!originalHeights.containsKey(key)) {
      originalHeights.put(key, height);
      setDirty();
    }
  }

  private static long columnKey(int x, int z) {
    return BlockPos.asLong(x, 0, z);
  }

}
