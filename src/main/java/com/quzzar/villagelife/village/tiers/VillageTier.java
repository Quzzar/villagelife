package com.quzzar.villagelife.village.tiers;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One rung of the village progression ladder (docs/village-tiers.md). A tier is
 * a read-out of emergent growth, never a gate: it classifies a village by
 * population and carries the campfire idle cap — it unlocks nothing.
 *
 * @param id            the datapack file id, e.g. "villagelife:camp"
 * @param rank          position on the ladder; 0 is the starting tier
 * @param minPopulation minimum population that classifies a village at this tier
 * @param idleCap       campfire reservoir size at this tier
 */
public record VillageTier(String id, int rank, int minPopulation, int idleCap) {

  /** The JSON body; the id comes from the datapack file id, not the body. */
  public record Raw(int rank, int minPopulation, int idleCap) {
    public static final Codec<Raw> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        Codec.INT.fieldOf("rank").forGetter(Raw::rank),
        Codec.INT.fieldOf("population").forGetter(Raw::minPopulation),
        Codec.INT.optionalFieldOf("idle_cap", 4).forGetter(Raw::idleCap)
    ).apply(inst, Raw::new));
  }

  public String translationKey() {
    int colon = id.indexOf(':');
    return "villagelife.tier." + (colon >= 0 ? id.substring(colon + 1) : id);
  }

}
