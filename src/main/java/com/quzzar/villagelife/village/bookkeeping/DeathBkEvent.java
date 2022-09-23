package com.quzzar.villagelife.village.bookkeeping;

import java.util.UUID;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.MarriageStatus;
import com.quzzar.villagelife.village.Occupation;

public class DeathBkEvent extends PersonBkEvent {
  
  private String deathType;
  private UUID playerKillerUUID;

  public DeathBkEvent(UUID personUUID, long location, Occupation occupation, MarriageStatus marriageStatus, String deathType, UUID playerKillerUUID){
    super(personUUID, location, occupation, marriageStatus);

    this.deathType = deathType;
    this.playerKillerUUID = playerKillerUUID;

  }

  public String getDeathType(){
    return deathType;
  }

  @Nullable
  public UUID getPlayerKillerUUID(){
    return playerKillerUUID;
  }

}
