package com.quzzar.kithkyn.savedata;

import com.quzzar.kithkyn.Kithkyn;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Who put each placed block where, for one level: one compact set of packed
 * positions for players, one for villages.
 *
 * The ownership rule is the simple one it sounds like: a block a player set
 * down is the player's, a block a village set down (a stamped building, a
 * builder's wall, the campfire) is the village's, and a block neither placed
 * is nobody's - a natural log a worker may fell. Both sides are recorded as
 * the placement happens and dropped when the block is broken.
 *
 * Deliberately NOT recorded: things the village plants to grow, like the
 * saplings a lumberjack stands replants. A planted tree is meant to be cut,
 * so marking its ground village-placed would make the village's own wood
 * supply untouchable.
 *
 * Compactness: positions are {@code long} keys in primitive
 * {@link LongOpenHashSet}s (eight bytes each, no per-entry object), saved as
 * single {@link LongArrayTag}s. Break-pruning keeps build-and-tear churn from
 * accumulating. A whole village is tens of kilobytes.
 *
 * Stored per level, because {@link BlockPos#asLong} does not encode the
 * dimension. Access through {@link #get}.
 */
public final class PlacedBlockStore extends SavedData {

  private static final String DATA_NAME = Kithkyn.MODID + "~placed_blocks";

  public static final SavedData.Factory<PlacedBlockStore> FACTORY = new SavedData.Factory<>(
      PlacedBlockStore::new, PlacedBlockStore::load, null);

  private final LongOpenHashSet playerPlaced = new LongOpenHashSet();
  private final LongOpenHashSet villagePlaced = new LongOpenHashSet();

  public PlacedBlockStore() {
  }

  /** The store for this level, created on first access and persisted with it. */
  public static PlacedBlockStore get(ServerLevel level) {
    return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
  }

  public static PlacedBlockStore load(CompoundTag tag, HolderLookup.Provider registries) {
    PlacedBlockStore data = new PlacedBlockStore();
    for (long packed : tag.getLongArray("Placed")) {
      data.playerPlaced.add(packed);
    }
    for (long packed : tag.getLongArray("VillagePlaced")) {
      data.villagePlaced.add(packed);
    }
    return data;
  }

  @Override
  public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    tag.put("Placed", new LongArrayTag(playerPlaced.toLongArray()));
    tag.put("VillagePlaced", new LongArrayTag(villagePlaced.toLongArray()));
    return tag;
  }

  /** Record that a player placed the block at this position. */
  public void markPlayerPlaced(BlockPos pos) {
    if (playerPlaced.add(pos.asLong())) {
      setDirty();
    }
  }

  /** Record that a village placed the block at this position. */
  public void markVillagePlaced(BlockPos pos) {
    if (villagePlaced.add(pos.asLong())) {
      setDirty();
    }
  }

  /** Forget this position entirely: the block is gone, whoever placed it. */
  public void clearPlaced(BlockPos pos) {
    long packed = pos.asLong();
    boolean removed = playerPlaced.remove(packed);
    removed |= villagePlaced.remove(packed);
    if (removed) {
      setDirty();
    }
  }

  /** Whether a player placed the block here and it has not since been broken. */
  public boolean isPlayerPlaced(BlockPos pos) {
    return playerPlaced.contains(pos.asLong());
  }

  /** Whether a village placed the block here and it has not since been broken. */
  public boolean isVillagePlaced(BlockPos pos) {
    return villagePlaced.contains(pos.asLong());
  }

}
