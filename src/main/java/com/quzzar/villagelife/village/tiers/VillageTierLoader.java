package com.quzzar.villagelife.village.tiers;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.quzzar.villagelife.Villagelife;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * Loads the tier ladder from {@code data/<namespace>/villagelife/tiers/*.json}
 * into {@link VillageTiers}, so the progression ladder is datapack content
 * instead of hardcoded Java. Mirrors {@code BuildingDefinitionLoader}.
 */
@EventBusSubscriber(modid = Villagelife.MODID)
public class VillageTierLoader extends SimpleJsonResourceReloadListener {

  public VillageTierLoader() {
    super(new Gson(), "villagelife/tiers");
  }

  @SubscribeEvent
  public static void onAddReloadListeners(AddReloadListenerEvent event) {
    event.addListener(new VillageTierLoader());
  }

  @Override
  protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager resourceManager,
      ProfilerFiller profiler) {
    Map<String, VillageTier> loaded = new HashMap<>();
    jsons.forEach((id, json) -> VillageTier.Raw.CODEC.parse(JsonOps.INSTANCE, json)
        .resultOrPartial(error -> Villagelife.LOGGER.error("Invalid village tier {}: {}", id, error))
        .ifPresent(raw -> loaded.put(id.toString(),
            new VillageTier(id.toString(), raw.rank(), raw.minPopulation(), raw.idleCap()))));
    VillageTiers.reload(loaded);
    Villagelife.LOGGER.info("Loaded {} village tiers", loaded.size());
  }

}
