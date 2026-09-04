package com.quzzar.kithkyn.economy;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * A tally of tradeable goods, item to count. It is used two ways, which is why
 * it is one type rather than two:
 *
 * <ul>
 *   <li>the daily cache a loaded village keeps of its own sellable stock, so a
 *       wandering merchant can be seeded from a village whose chunks are no
 *       longer loaded (reading them would force-load, which the economy
 *       forbids, docs/economy.md);</li>
 *   <li>the wandering merchant's own virtual ledger: a mutable copy of that
 *       cache that drifts as it trades, without ever touching the village
 *       again.</li>
 * </ul>
 *
 * <p>This type holds only the quantities and (de)serialises them. Prices are
 * derived from the counts elsewhere ({@link VillagePricing}); a merchant's
 * supply and demand read from its own copy, so selling it a stack sags that
 * good's price and buying it out lifts it.
 */
public final class EconomySnapshot {

  private final Map<Item, Integer> counts;

  private EconomySnapshot(Map<Item, Integer> counts) {
    this.counts = counts;
  }

  /** An empty tally, the starting point a village has before its first capture. */
  public static EconomySnapshot empty() {
    return new EconomySnapshot(new LinkedHashMap<>());
  }

  /**
   * Captures a stock tally (e.g. {@code Village.stockTally()}) as a snapshot,
   * copying it so later mutation of either side does not leak into the other.
   */
  public static EconomySnapshot capture(Map<Item, Integer> tally) {
    Map<Item, Integer> copy = new LinkedHashMap<>();
    for (Map.Entry<Item, Integer> entry : tally.entrySet()) {
      if (entry.getValue() != null && entry.getValue() > 0 && entry.getKey() != Items.AIR) {
        copy.put(entry.getKey(), entry.getValue());
      }
    }
    return new EconomySnapshot(copy);
  }

  /** An independent copy, so a merchant's drift never touches the village's cache. */
  public EconomySnapshot copy() {
    return new EconomySnapshot(new LinkedHashMap<>(this.counts));
  }

  public boolean isEmpty() {
    return this.counts.isEmpty();
  }

  /** How many of an item this tally holds, zero if none. */
  public int count(Item item) {
    return this.counts.getOrDefault(item, 0);
  }

  /** The goods this tally lists, in insertion order. */
  public java.util.Set<Item> items() {
    return java.util.Collections.unmodifiableSet(this.counts.keySet());
  }

  /**
   * Adds to the count of an item (a merchant taking goods off a player). Used to
   * drift the merchant's ledger; the village's own cache is never mutated.
   */
  public void add(Item item, int amount) {
    if (amount <= 0 || item == Items.AIR) {
      return;
    }
    this.counts.merge(item, amount, Integer::sum);
  }

  /**
   * Removes from the count of an item (a merchant selling to a player), never
   * below zero. Returns how many were actually removed.
   */
  public int remove(Item item, int amount) {
    if (amount <= 0) {
      return 0;
    }
    int held = count(item);
    int taken = Math.min(held, amount);
    if (taken <= 0) {
      return 0;
    }
    int left = held - taken;
    if (left > 0) {
      this.counts.put(item, left);
    } else {
      this.counts.remove(item);
    }
    return taken;
  }

  /** Serialises to NBT: a list of {@code {Id, Count}} entries. */
  public CompoundTag save() {
    ListTag list = new ListTag();
    for (Map.Entry<Item, Integer> entry : this.counts.entrySet()) {
      CompoundTag row = new CompoundTag();
      row.putString("Id", BuiltInRegistries.ITEM.getKey(entry.getKey()).toString());
      row.putInt("Count", entry.getValue());
      list.add(row);
    }
    CompoundTag tag = new CompoundTag();
    tag.put("Goods", list);
    return tag;
  }

  /** Reads back a tally saved by {@link #save()}; unknown items are dropped. */
  public static EconomySnapshot load(CompoundTag tag) {
    Map<Item, Integer> counts = new LinkedHashMap<>();
    ListTag list = tag.getList("Goods", Tag.TAG_COMPOUND);
    for (int i = 0; i < list.size(); i++) {
      CompoundTag row = list.getCompound(i);
      Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(row.getString("Id")));
      int count = row.getInt("Count");
      if (item != Items.AIR && count > 0) {
        counts.put(item, count);
      }
    }
    return new EconomySnapshot(counts);
  }
}
