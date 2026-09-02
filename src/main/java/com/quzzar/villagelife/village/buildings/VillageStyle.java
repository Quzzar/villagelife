package com.quzzar.villagelife.village.buildings;

import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.Tags;

/**
 * The regional family a village builds in (docs/buildings.md, "Regional
 * variants and biomes"): chosen once, at founding, from the biome the camp
 * stands in, and kept for the village's life. Every later building takes this
 * family's variant, so a village reads as one place rather than a sampler.
 *
 * The biome is read through the conventional biome tags rather than vanilla
 * ids, so a modded desert that tags itself {@code c:is_desert} founds a desert
 * camp instead of defaulting to plains. The order below settles biomes that
 * carry more than one tag: a snowy taiga is snowy before it is taiga.
 */
public enum VillageStyle {
  PLAINS, TAIGA, SNOWY, DESERT, SAVANNA;

  /** The token this style takes in a building id, {@code house_<style>_1}. */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The style for an id token, or null when no such style exists. */
  @Nullable
  public static VillageStyle parse(String id) {
    for (VillageStyle style : values()) {
      if (style.id().equalsIgnoreCase(id)) {
        return style;
      }
    }
    return null;
  }

  /** The style for an id token, plains for anything unknown or blank. */
  public static VillageStyle fromId(String id) {
    VillageStyle style = parse(id);
    return style != null ? style : PLAINS;
  }

  /** Which family a camp founded in this biome builds in. */
  public static VillageStyle fromBiome(Holder<Biome> biome) {
    if (biome.is(Tags.Biomes.IS_DESERT) || biome.is(Tags.Biomes.IS_BADLANDS) || biome.is(Tags.Biomes.IS_SANDY)) {
      return DESERT;
    }
    if (biome.is(Tags.Biomes.IS_SNOWY) || biome.is(Tags.Biomes.IS_ICY)) {
      return SNOWY;
    }
    if (biome.is(Tags.Biomes.IS_SAVANNA) || biome.is(Tags.Biomes.IS_JUNGLE)) {
      return SAVANNA;
    }
    if (biome.is(Tags.Biomes.IS_TAIGA) || biome.is(Tags.Biomes.IS_CONIFEROUS_TREE)
        || biome.is(Tags.Biomes.IS_MOUNTAIN)) {
      return TAIGA;
    }
    return PLAINS;
  }
}
