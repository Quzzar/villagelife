package com.quzzar.kithkyn.village.bookkeeping;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * Logged when the village wants an item it cannot supply: a construction cost
 * the planner can't afford, or a villager's essential gather coming up empty.
 * Feeds the attractiveness score while its impact decays.
 */
public class NoResourceBookkeepingEvent extends BookkeepingEvent {

  public static final String KIND = "no_resource";

  public static final MapCodec<NoResourceBookkeepingEvent> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
      UUIDUtil.CODEC.fieldOf("id").forGetter(BookkeepingEvent::getEventID),
      Codec.FLOAT.fieldOf("impact").forGetter(BookkeepingEvent::getImpact),
      BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(NoResourceBookkeepingEvent::getMissingItem),
      Codec.INT.fieldOf("missing_count").forGetter(NoResourceBookkeepingEvent::getMissingCount)
  ).apply(inst, NoResourceBookkeepingEvent::new));

  private Item missingItem;
  private int missingCount;

  public NoResourceBookkeepingEvent(Item missingItem, int missingCount) {
    super();

    this.missingItem = missingItem;
    this.missingCount = missingCount;
  }

  private NoResourceBookkeepingEvent(UUID eventId, float impact, Item missingItem, int missingCount) {
    super(eventId, impact);

    this.missingItem = missingItem;
    this.missingCount = missingCount;
  }

  @Override
  public String kind() {
    return KIND;
  }

  public Item getMissingItem() {
    return missingItem;
  }

  public int getMissingCount() {
    return missingCount;
  }

}
