package com.quzzar.villagelife.village.bookkeeping;

import java.util.UUID;

import com.quzzar.villagelife.entities.MarriageStatus;
import com.quzzar.villagelife.village.Occupation;

public class HurtByPlayerBkEvent extends PersonBkEvent {
  
  private String damageType;
  private UUID playerDamagerUUID;

  public HurtByPlayerBkEvent(UUID personUUID, long location, Occupation occupation, MarriageStatus marriageStatus, String damageType, UUID playerDamagerUUID){
    super(personUUID, location, occupation, marriageStatus);

    this.damageType = damageType;
    this.playerDamagerUUID = playerDamagerUUID;

  }

  public String getDamageType(){
    return damageType;
  }

  public UUID getPlayerDamagerUUID(){
    return playerDamagerUUID;
  }

}
