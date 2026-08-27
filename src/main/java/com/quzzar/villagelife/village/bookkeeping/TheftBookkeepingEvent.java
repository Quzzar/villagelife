package com.quzzar.villagelife.village.bookkeeping;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * Someone took from the village, and was seen doing it (decided on #64).
 *
 * Killing and assault already have their own entries in the books; this is the
 * third and lightest kind of harm a person can do a village, and it is here
 * rather than folded into the others because a village that has been robbed is
 * not a village that has been attacked, and it should be able to say so.
 */
public class TheftBookkeepingEvent extends BookkeepingEvent {

  public static final String KIND = "theft";

  public static final MapCodec<TheftBookkeepingEvent> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
      UUIDUtil.CODEC.fieldOf("id").forGetter(BookkeepingEvent::getEventID),
      Codec.FLOAT.fieldOf("impact").forGetter(BookkeepingEvent::getImpact),
      UUIDUtil.CODEC.fieldOf("thief").forGetter(TheftBookkeepingEvent::getThiefUUID),
      Codec.STRING.fieldOf("what").forGetter(TheftBookkeepingEvent::getWhat)
  ).apply(inst, TheftBookkeepingEvent::new));

  private final UUID thiefUUID;
  private final String what;

  public TheftBookkeepingEvent(UUID thiefUUID, String what) {
    this.thiefUUID = thiefUUID;
    this.what = what;
  }

  private TheftBookkeepingEvent(UUID eventId, float impact, UUID thiefUUID, String what) {
    super(eventId, impact);
    this.thiefUUID = thiefUUID;
    this.what = what;
  }

  @Override
  public String kind() {
    return KIND;
  }

  public UUID getThiefUUID() {
    return thiefUUID;
  }

  /** What was taken, in words, for the village to be able to complain about it. */
  public String getWhat() {
    return what;
  }

}
