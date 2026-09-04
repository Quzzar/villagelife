package com.quzzar.kithkyn.village.bookkeeping;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

public class BookkeepingEvent {

  public static final MapCodec<BookkeepingEvent> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
      UUIDUtil.CODEC.fieldOf("id").forGetter(BookkeepingEvent::getEventID),
      Codec.FLOAT.fieldOf("impact").forGetter(BookkeepingEvent::getImpact)
  ).apply(inst, BookkeepingEvent::new));

  /** Polymorphic codec over every event kind, dispatched on a "kind" string. */
  public static final Codec<BookkeepingEvent> DISPATCH_CODEC = Codec.STRING.dispatch("kind", BookkeepingEvent::kind, kind -> switch (kind) {
    case DeathBookkeepingEvent.KIND -> DeathBookkeepingEvent.CODEC;
    case HurtByPlayerBookkeepingEvent.KIND -> HurtByPlayerBookkeepingEvent.CODEC;
    case NoResourceBookkeepingEvent.KIND -> NoResourceBookkeepingEvent.CODEC;
    case TheftBookkeepingEvent.KIND -> TheftBookkeepingEvent.CODEC;
    case PersonBookkeepingEvent.KIND -> PersonBookkeepingEvent.CODEC;
    default -> CODEC;
  });

  public static final String KIND = "generic";

  private UUID uuid;
  private float impact;

  public BookkeepingEvent() {
    this.uuid = UUID.randomUUID();
    this.impact = 1.0F;
  }

  protected BookkeepingEvent(UUID uuid, float impact) {
    this.uuid = uuid;
    this.impact = impact;
  }

  public String kind() {
    return KIND;
  }

  public UUID getEventID() {
    return uuid;
  }

  public float getImpact() {
    return impact;
  }

  public void setImpact(float impact) {
    this.impact = impact;
  }

}
