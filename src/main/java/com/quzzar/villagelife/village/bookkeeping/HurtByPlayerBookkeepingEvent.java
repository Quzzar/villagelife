package com.quzzar.villagelife.village.bookkeeping;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.quzzar.villagelife.entities.MarriageStatus;
import com.quzzar.villagelife.utils.VillagelifeCodecs;
import com.quzzar.villagelife.village.Occupation;

import net.minecraft.core.UUIDUtil;

public class HurtByPlayerBookkeepingEvent extends PersonBookkeepingEvent {

  public static final String KIND = "hurt_by_player";

  public static final MapCodec<HurtByPlayerBookkeepingEvent> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
      UUIDUtil.CODEC.fieldOf("id").forGetter(BookkeepingEvent::getEventID),
      Codec.FLOAT.fieldOf("impact").forGetter(BookkeepingEvent::getImpact),
      UUIDUtil.CODEC.fieldOf("person").forGetter(PersonBookkeepingEvent::getPersonUUID),
      Codec.LONG.fieldOf("location").forGetter(PersonBookkeepingEvent::getPersonLocation),
      VillagelifeCodecs.forEnum(Occupation.class).fieldOf("occupation").forGetter(PersonBookkeepingEvent::getPersonOccupation),
      VillagelifeCodecs.forEnum(MarriageStatus.class).fieldOf("marriage_status").forGetter(PersonBookkeepingEvent::getPersonMarriageStatus),
      Codec.STRING.fieldOf("damage_type").forGetter(HurtByPlayerBookkeepingEvent::getDamageType),
      UUIDUtil.CODEC.fieldOf("damager").forGetter(HurtByPlayerBookkeepingEvent::getPlayerDamagerUUID)
  ).apply(inst, HurtByPlayerBookkeepingEvent::new));

  private String damageType;
  private UUID playerDamagerUUID;

  public HurtByPlayerBookkeepingEvent(UUID personUUID, long location, Occupation occupation, MarriageStatus marriageStatus,
      String damageType, UUID playerDamagerUUID) {
    super(personUUID, location, occupation, marriageStatus);

    this.damageType = damageType;
    this.playerDamagerUUID = playerDamagerUUID;
  }

  private HurtByPlayerBookkeepingEvent(UUID eventId, float impact, UUID personUUID, long location, Occupation occupation,
      MarriageStatus marriageStatus, String damageType, UUID playerDamagerUUID) {
    super(eventId, impact, personUUID, location, occupation, marriageStatus);

    this.damageType = damageType;
    this.playerDamagerUUID = playerDamagerUUID;
  }

  @Override
  public String kind() {
    return KIND;
  }

  public String getDamageType() {
    return damageType;
  }

  public UUID getPlayerDamagerUUID() {
    return playerDamagerUUID;
  }

}
