package com.quzzar.kithkyn.economy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * What things are worth, in emeralds (see docs/economy.md).
 *
 * Only RAW MATERIALS are authored, in datapack JSON. Everything else is
 * derived at runtime by decomposing recipes down to those raws, so a large
 * modpack prices itself without anyone writing a table: almost every item is
 * craftable from something already valued. Items that are neither authored,
 * derivable, nor covered by a tag fallback are simply not tradeable.
 *
 * Derivation takes the CHEAPEST recipe path, so crafting can never mint value
 * (buy the parts, craft, sell for more). Results are memoized per server, and
 * the walk is depth-capped and cycle-guarded, because recipe graphs loop
 * (nine ingots make a block, a block makes nine ingots).
 */
public final class ItemValues {

  /** How deep the recipe walk goes before giving up on an item. */
  private static final int MAX_DEPTH = 6;

  private static volatile Map<Item, Double> authored = Map.of();
  private static volatile Map<TagKey<Item>, Double> tagFallbacks = Map.of();

  /** Cleared whenever datapacks reload or the server changes. */
  private static final Map<Item, Optional<Double>> DERIVED = new ConcurrentHashMap<>();
  /** Recipes by output item, rebuilt lazily per server. */
  private static volatile Map<Item, List<RecipeHolder<?>>> recipesByOutput = null;
  private static volatile MinecraftServer indexedFor = null;

  private ItemValues() {
  }

  static void reload(Map<Item, Double> values, Map<TagKey<Item>, Double> tags) {
    authored = Map.copyOf(values);
    tagFallbacks = Map.copyOf(tags);
    invalidate();
  }

  public static void invalidate() {
    DERIVED.clear();
    recipesByOutput = null;
    indexedFor = null;
  }

  public static int authoredCount() {
    return authored.size();
  }

  /**
   * The item's worth in emeralds, or empty when nothing can value it (which
   * means it cannot be traded at all). Server thread or a thread holding a
   * live server reference; the recipe index is built on first use.
   */
  public static Optional<Double> valueOf(Item item, MinecraftServer server) {
    Double direct = authored.get(item);
    if (direct != null) {
      return Optional.of(direct);
    }
    Optional<Double> memo = DERIVED.get(item);
    if (memo != null) {
      return memo;
    }
    Optional<Double> computed = derive(item, server, new HashSet<>(), 0);
    if (computed.isEmpty()) {
      computed = tagFallback(item);
    }
    DERIVED.put(item, computed);
    return computed;
  }

  public static Optional<Double> valueOf(ItemStack stack, MinecraftServer server) {
    return valueOf(stack.getItem(), server).map(value -> value * stack.getCount());
  }

  /** A tag the pack priced, for modded raws nothing can craft (ores, drops). */
  private static Optional<Double> tagFallback(Item item) {
    double best = Double.MAX_VALUE;
    for (Map.Entry<TagKey<Item>, Double> entry : tagFallbacks.entrySet()) {
      if (item.builtInRegistryHolder().is(entry.getKey()) && entry.getValue() < best) {
        best = entry.getValue();
      }
    }
    return best == Double.MAX_VALUE ? Optional.empty() : Optional.of(best);
  }

  /** Cheapest recipe path down to authored raws. */
  private static Optional<Double> derive(Item item, MinecraftServer server, Set<Item> visiting, int depth) {
    Double direct = authored.get(item);
    if (direct != null) {
      return Optional.of(direct);
    }
    if (depth >= MAX_DEPTH || !visiting.add(item)) {
      return Optional.empty(); // too deep, or a cycle back to something in progress
    }
    try {
      double best = Double.MAX_VALUE;
      for (RecipeHolder<?> holder : recipesFor(item, server)) {
        Optional<Double> cost = costOf(holder, server, visiting, depth);
        if (cost.isPresent() && cost.get() < best) {
          best = cost.get();
        }
      }
      return best == Double.MAX_VALUE ? Optional.empty() : Optional.of(best);
    } finally {
      visiting.remove(item);
    }
  }

  /** Sum of the cheapest valued option per ingredient, divided by the yield. */
  private static Optional<Double> costOf(RecipeHolder<?> holder, MinecraftServer server, Set<Item> visiting,
      int depth) {
    Recipe<?> recipe = holder.value();
    HolderLookup.Provider registries = server.registryAccess();
    ItemStack result = recipe.getResultItem(registries);
    if (result.isEmpty()) {
      return Optional.empty();
    }
    double total = 0;
    for (Ingredient ingredient : recipe.getIngredients()) {
      if (ingredient.isEmpty()) {
        continue;
      }
      double cheapest = Double.MAX_VALUE;
      for (ItemStack option : ingredient.getItems()) {
        Optional<Double> value = derive(option.getItem(), server, visiting, depth + 1);
        if (value.isPresent() && value.get() < cheapest) {
          cheapest = value.get();
        }
      }
      if (cheapest == Double.MAX_VALUE) {
        return Optional.empty(); // an ingredient nothing can value poisons the recipe
      }
      total += cheapest;
    }
    return Optional.of(total / Math.max(1, result.getCount()));
  }

  private static List<RecipeHolder<?>> recipesFor(Item item, MinecraftServer server) {
    Map<Item, List<RecipeHolder<?>>> index = recipesByOutput;
    if (index == null || indexedFor != server) {
      index = buildIndex(server);
      recipesByOutput = index;
      indexedFor = server;
    }
    return index.getOrDefault(item, List.of());
  }

  private static Map<Item, List<RecipeHolder<?>>> buildIndex(MinecraftServer server) {
    Map<Item, List<RecipeHolder<?>>> index = new HashMap<>();
    HolderLookup.Provider registries = server.registryAccess();
    for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
      ItemStack result;
      try {
        result = holder.value().getResultItem(registries);
      } catch (Exception e) {
        continue; // dynamic or special recipes have no fixed result
      }
      if (result != null && !result.isEmpty()) {
        index.computeIfAbsent(result.getItem(), key -> new ArrayList<>()).add(holder);
      }
    }
    return index;
  }

  /** Parses "namespace:path" into an item tag key, for the fallback table. */
  static TagKey<Item> tagKey(String id) {
    return TagKey.create(Registries.ITEM, ResourceLocation.parse(id));
  }

  public static Optional<Item> item(String id) {
    ResourceLocation location = ResourceLocation.tryParse(id);
    if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) {
      return Optional.empty();
    }
    return Optional.of(BuiltInRegistries.ITEM.get(location));
  }
}
