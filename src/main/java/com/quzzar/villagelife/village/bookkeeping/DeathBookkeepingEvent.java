package com.quzzar.villagelife.village.bookkeeping;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.quzzar.villagelife.entities.MarriageStatus;
import com.quzzar.villagelife.utils.VillagelifeCodecs;
import com.quzzar.villagelife.village.Occupation;

import net.minecraft.core.UUIDUtil;

public class DeathBookkeepingEvent extends PersonBookkeepingEvent {

  public static final String KIND = "death";

  public static final MapCodec<DeathBookkeepingEvent> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
      UUIDUtil.CODEC.fieldOf("id").forGetter(BookkeepingEvent::getEventID),
      Codec.FLOAT.fieldOf("impact").forGetter(BookkeepingEvent::getImpact),
      UUIDUtil.CODEC.fieldOf("person").forGetter(PersonBookkeepingEvent::getPersonUUID),
      Codec.LONG.fieldOf("location").forGetter(PersonBookkeepingEvent::getPersonLocation),
      VillagelifeCodecs.forEnum(Occupation.class).fieldOf("occupation").forGetter(PersonBookkeepingEvent::getPersonOccupation),
      VillagelifeCodecs.forEnum(MarriageStatus.class).fieldOf("marriage_status").forGetter(PersonBookkeepingEvent::getPersonMarriageStatus),
      Codec.STRING.fieldOf("death_type").forGetter(DeathBookkeepingEvent::getDeathType),
      UUIDUtil.CODEC.optionalFieldOf("killer").forGetter(e -> Optional.ofNullable(e.getPlayerKillerUUID()))
  ).apply(inst, (id, impact, person, location, occupation, marriage, deathType, killer) ->
      new DeathBookkeepingEvent(id, impact, person, location, occupation, marriage, deathType, killer.orElse(null))));

  private String deathType;
  private UUID playerKillerUUID;

  public DeathBookkeepingEvent(UUID personUUID, long location, Occupation occupation, MarriageStatus marriageStatus,
      String deathType, UUID playerKillerUUID) {
    super(personUUID, location, occupation, marriageStatus);

    this.deathType = deathType;
    this.playerKillerUUID = playerKillerUUID;
  }

  private DeathBookkeepingEvent(UUID eventId, float impact, UUID personUUID, long location, Occupation occupation,
      MarriageStatus marriageStatus, String deathType, @Nullable UUID playerKillerUUID) {
    super(eventId, impact, personUUID, location, occupation, marriageStatus);

    this.deathType = deathType;
    this.playerKillerUUID = playerKillerUUID;
  }

  @Override
  public String kind() {
    return KIND;
  }

  public String getDeathType() {
    return deathType;
  }

  @Nullable
  public UUID getPlayerKillerUUID() {
    return playerKillerUUID;
  }

}
