package com.quzzar.kithkyn.village.bookkeeping;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.quzzar.kithkyn.entities.MarriageStatus;
import com.quzzar.kithkyn.utils.KithkynCodecs;
import com.quzzar.kithkyn.village.Occupation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

public class PersonBookkeepingEvent extends BookkeepingEvent {

  public static final String KIND = "person";

  public static final MapCodec<PersonBookkeepingEvent> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
      UUIDUtil.CODEC.fieldOf("id").forGetter(BookkeepingEvent::getEventID),
      Codec.FLOAT.fieldOf("impact").forGetter(BookkeepingEvent::getImpact),
      UUIDUtil.CODEC.fieldOf("person").forGetter(PersonBookkeepingEvent::getPersonUUID),
      Codec.LONG.fieldOf("location").forGetter(PersonBookkeepingEvent::getPersonLocation),
      KithkynCodecs.forEnum(Occupation.class).fieldOf("occupation").forGetter(PersonBookkeepingEvent::getPersonOccupation),
      KithkynCodecs.forEnum(MarriageStatus.class).fieldOf("marriage_status").forGetter(PersonBookkeepingEvent::getPersonMarriageStatus)
  ).apply(inst, PersonBookkeepingEvent::new));

  private UUID uuid;
  private long location;
  private Occupation occupation;
  private MarriageStatus marriageStatus;

  public PersonBookkeepingEvent(UUID uuid, long location, Occupation occupation, MarriageStatus marriageStatus) {
    super();

    this.uuid = uuid;
    this.location = location;
    this.occupation = occupation;
    this.marriageStatus = marriageStatus;
  }

  protected PersonBookkeepingEvent(UUID eventId, float impact, UUID uuid, long location, Occupation occupation,
      MarriageStatus marriageStatus) {
    super(eventId, impact);

    this.uuid = uuid;
    this.location = location;
    this.occupation = occupation;
    this.marriageStatus = marriageStatus;
  }

  @Override
  public String kind() {
    return KIND;
  }

  public UUID getPersonUUID() {
    return uuid;
  }

  public long getPersonLocation() {
    return location;
  }

  public BlockPos getPersonBlockPos() {
    return BlockPos.of(location);
  }

  public Occupation getPersonOccupation() {
    return occupation;
  }

  public MarriageStatus getPersonMarriageStatus() {
    return marriageStatus;
  }

}
