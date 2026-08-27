package com.quzzar.villagelife.village.buildings;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * A capability a building grants only when a condition holds
 * (docs/building-spec.md, decided on #55).
 *
 * Conditions name capabilities and supplies, never building ids: a church
 * needs LEARNING to enable enchanting, and does not care which building
 * provides it or whether a datapack adds a third route to it later.
 *
 * The two kinds differ in when they can change. A capability requirement is
 * static: it moves only when buildings do. A supply requirement is dynamic,
 * checked against what the village actually holds, so an inn with no ale
 * grants nothing this minute and grants again when the brewery catches up.
 */
public record Grant(String capability, List<String> requiresCapability, List<Item> requiresSupply) {

  public static final Codec<Grant> CODEC = RecordCodecBuilder.create(inst -> inst.group(
      Codec.STRING.fieldOf("capability").forGetter(Grant::capability),
      Codec.STRING.listOf().optionalFieldOf("requires_capability", List.of()).forGetter(Grant::requiresCapability),
      BuiltInRegistries.ITEM.byNameCodec().listOf().optionalFieldOf("requires_supply", List.of())
          .forGetter(Grant::requiresSupply)
  ).apply(inst, Grant::new));

  /** True when nothing gates this grant, so it lands in the first resolution pass. */
  public boolean isUnconditional() {
    return requiresCapability.isEmpty() && requiresSupply.isEmpty();
  }

}
