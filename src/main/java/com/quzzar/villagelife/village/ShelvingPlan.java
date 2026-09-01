package com.quzzar.villagelife.village;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;

/**
 * How a village has decided to shelve its storehouse: a partition of the
 * storehouse's numbered slots into named categories, each owning a contiguous
 * run of slots and the items that live there. The storehouse's slots are
 * numbered across its chests in order (chest one first), and a valid plan covers
 * every slot exactly once, so "which slots hold what" is total and unambiguous.
 *
 * <p>The {@link QuartermasterPlanner} builds it from an LLM dialogue that keeps
 * proposing until the partition validates; the quartermaster's tidy pass lays
 * the shelves out to match it, with no model in the loop.
 *
 * <p>Persisted in the brain's {@code strategy} tag, the same durable per-village
 * store {@code VillageGoal} uses.
 */
public final class ShelvingPlan {

  private static final String KEY_ROOT = "shelving";
  private static final String KEY_TOTAL = "total_slots";
  private static final String KEY_CATEGORIES = "categories";
  private static final String KEY_NAME = "name";
  private static final String KEY_FIRST = "first_slot";
  private static final String KEY_COUNT = "slot_count";
  private static final String KEY_ITEMS = "items";

  /**
   * One shelf: a named group holding {@code itemIds} in the {@code slotCount}
   * slots starting at {@code firstSlot} (a 0-based global slot index).
   */
  public record Category(String name, List<String> itemIds, int firstSlot, int slotCount) {
  }

  private final List<Category> categories;
  private final int totalSlots;
  private final Map<String, Integer> itemToCategory;

  public ShelvingPlan(List<Category> categories, int totalSlots) {
    this.categories = List.copyOf(categories);
    this.totalSlots = totalSlots;
    Map<String, Integer> index = new LinkedHashMap<>();
    for (int i = 0; i < this.categories.size(); i++) {
      for (String id : this.categories.get(i).itemIds()) {
        index.putIfAbsent(id, i); // a contested item stays with the first category to claim it
      }
    }
    this.itemToCategory = index;
  }

  public List<Category> categories() {
    return categories;
  }

  public int totalSlots() {
    return totalSlots;
  }

  /** The category an item belongs to, or null when the plan never placed it. */
  @Nullable
  public Category categoryFor(Item item) {
    Integer at = itemToCategory.get(idOf(item));
    return at != null ? categories.get(at) : null;
  }

  /** Whether a category already accounts for this item. */
  public boolean covers(Item item) {
    return itemToCategory.containsKey(idOf(item));
  }

  private static String idOf(Item item) {
    return BuiltInRegistries.ITEM.getKey(item).toString();
  }

  // ---- persistence: brain strategy tag ----

  /** Writes this plan into the village's durable strategy store, replacing any prior one. */
  public static void store(Village village, ShelvingPlan plan) {
    ListTag list = new ListTag();
    for (Category category : plan.categories) {
      CompoundTag tag = new CompoundTag();
      tag.putString(KEY_NAME, category.name());
      tag.putInt(KEY_FIRST, category.firstSlot());
      tag.putInt(KEY_COUNT, category.slotCount());
      ListTag items = new ListTag();
      for (String id : category.itemIds()) {
        items.add(StringTag.valueOf(id));
      }
      tag.put(KEY_ITEMS, items);
      list.add(tag);
    }
    CompoundTag root = new CompoundTag();
    root.putInt(KEY_TOTAL, plan.totalSlots);
    root.put(KEY_CATEGORIES, list);
    village.getBrain().getStrategy().put(KEY_ROOT, root);
  }

  /** The village's stored plan, or null when it has never planned its shelves. */
  @Nullable
  public static ShelvingPlan load(Village village) {
    CompoundTag strategy = village.getBrain().getStrategy();
    if (!strategy.contains(KEY_ROOT)) {
      return null;
    }
    CompoundTag root = strategy.getCompound(KEY_ROOT);
    ListTag list = root.getList(KEY_CATEGORIES, Tag.TAG_COMPOUND);
    List<Category> categories = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      CompoundTag tag = list.getCompound(i);
      ListTag items = tag.getList(KEY_ITEMS, Tag.TAG_STRING);
      List<String> ids = new ArrayList<>();
      for (int j = 0; j < items.size(); j++) {
        ids.add(items.getString(j));
      }
      categories.add(new Category(tag.getString(KEY_NAME), ids, tag.getInt(KEY_FIRST), tag.getInt(KEY_COUNT)));
    }
    return categories.isEmpty() ? null : new ShelvingPlan(categories, root.getInt(KEY_TOTAL));
  }

  public static void clear(Village village) {
    village.getBrain().getStrategy().remove(KEY_ROOT);
  }
}
