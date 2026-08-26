package com.quzzar.villagelife.village.buildings;

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
 * Loads building definitions from {@code data/<namespace>/villagelife/buildings/*.json}
 * into the {@link Buildings} registry, so buildings are datapack content
 * instead of hardcoded Java.
 */
@EventBusSubscriber(modid = Villagelife.MODID)
public class BuildingDefinitionLoader extends SimpleJsonResourceReloadListener {

    public BuildingDefinitionLoader() {
        super(new Gson(), "villagelife/buildings");
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new BuildingDefinitionLoader());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, BuildingInfo> loaded = new HashMap<>();
        jsons.forEach((id, json) -> BuildingInfo.CODEC.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(error -> Villagelife.LOGGER.error("Invalid building definition {}: {}", id, error))
                .ifPresent(info -> {
                    String problem = info.validate();
                    if (problem != null) {
                        Villagelife.LOGGER.error("Rejected building definition {} ({})", id, problem);
                        return;
                    }
                    BuildingInfo previous = loaded.put(info.getName(), info);
                    if (previous != null) {
                        Villagelife.LOGGER.warn("Duplicate building definition for '{}' (from {})", info.getName(), id);
                    }
                }));
        Buildings.reload(loaded);
        Villagelife.LOGGER.info("Loaded {} village building definitions", loaded.size());
    }

}
