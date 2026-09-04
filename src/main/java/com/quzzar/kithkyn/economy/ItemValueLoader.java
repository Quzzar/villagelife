package com.quzzar.kithkyn.economy;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.quzzar.kithkyn.Kithkyn;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * Loads emerald values from {@code data/<namespace>/kithkyn/item_values/*.json}.
 * Every file is merged, so a modpack adds its own file rather than editing ours.
 *
 * <pre>
 * {
 *   "values": { "minecraft:oak_log": 0.03 },
 *   "tag_values": { "c:ingots": 0.9 }
 * }
 * </pre>
 *
 * Only raw materials belong here: crafted items are derived from recipes by
 * {@link ItemValues}. Tag values catch modded raws nothing can craft.
 */
@EventBusSubscriber(modid = Kithkyn.MODID)
public class ItemValueLoader extends SimpleJsonResourceReloadListener {

  private record Raw(Map<String, Double> values, Map<String, Double> tagValues) {
    static final Codec<Raw> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("values", Map.of()).forGetter(Raw::values),
        Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("tag_values", Map.of())
            .forGetter(Raw::tagValues)
    ).apply(inst, Raw::new));
  }

  public ItemValueLoader() {
    super(new Gson(), "kithkyn/item_values");
  }

  @SubscribeEvent
  public static void onAddReloadListeners(AddReloadListenerEvent event) {
    event.addListener(new ItemValueLoader());
  }

  @Override
  protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager resourceManager,
      ProfilerFiller profiler) {
    Map<Item, Double> values = new HashMap<>();
    Map<TagKey<Item>, Double> tagValues = new HashMap<>();

    jsons.forEach((id, json) -> Raw.CODEC.parse(JsonOps.INSTANCE, json)
        .resultOrPartial(error -> Kithkyn.LOGGER.error("Invalid item value file {}: {}", id, error))
        .ifPresent(raw -> {
          raw.values().forEach((itemId, value) -> ItemValues.item(itemId).ifPresentOrElse(
              item -> values.put(item, value),
              () -> Kithkyn.LOGGER.warn("Item value file {} prices unknown item {}", id, itemId)));
          raw.tagValues().forEach((tagId, value) -> tagValues.put(ItemValues.tagKey(tagId), value));
        }));

    ItemValues.reload(values, tagValues);
    Kithkyn.LOGGER.info("Loaded {} item values and {} tag fallbacks", values.size(), tagValues.size());
  }
}
