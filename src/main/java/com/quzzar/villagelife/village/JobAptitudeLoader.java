package com.quzzar.villagelife.village;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.genetics.Stat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * Loads aptitude profiles from {@code data/<namespace>/villagelife/aptitude/*.json}
 * into {@link JobAptitudes}. Mirrors {@link com.quzzar.villagelife.village.tiers.VillageTierLoader}.
 * File shape: {@code {"occupation": "miner", "weights": {"strength": 3, "constitution": 2}}}.
 */
@EventBusSubscriber(modid = Villagelife.MODID)
public class JobAptitudeLoader extends SimpleJsonResourceReloadListener {

  private record Raw(String occupation, Map<String, Integer> weights) {
    static final Codec<Raw> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        Codec.STRING.fieldOf("occupation").forGetter(Raw::occupation),
        Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("weights").forGetter(Raw::weights)
    ).apply(inst, Raw::new));
  }

  public JobAptitudeLoader() {
    super(new Gson(), "villagelife/aptitude");
  }

  @SubscribeEvent
  public static void onAddReloadListeners(AddReloadListenerEvent event) {
    event.addListener(new JobAptitudeLoader());
  }

  @Override
  protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager resourceManager,
      ProfilerFiller profiler) {
    Map<Occupation, Map<Stat, Integer>> loaded = new HashMap<>();
    jsons.forEach((id, json) -> Raw.CODEC.parse(JsonOps.INSTANCE, json)
        .resultOrPartial(error -> Villagelife.LOGGER.error("Invalid aptitude profile {}: {}", id, error))
        .ifPresent(raw -> {
          try {
            Occupation occupation = Occupation.valueOf(raw.occupation().toUpperCase(Locale.ROOT));
            Map<Stat, Integer> weights = new EnumMap<>(Stat.class);
            raw.weights().forEach((statName, weight) ->
                weights.put(Stat.valueOf(statName.toUpperCase(Locale.ROOT)), weight));
            loaded.put(occupation, weights);
          } catch (IllegalArgumentException e) {
            Villagelife.LOGGER.error("Aptitude profile {} names an unknown occupation or stat: {}", id,
                e.getMessage());
          }
        }));
    JobAptitudes.reload(loaded);
    Villagelife.LOGGER.info("Loaded {} job aptitude profiles", loaded.size());
  }
}
