package com.quzzar.villagelife.village.bookkeeping;

import java.util.UUID;

import com.quzzar.villagelife.entities.MarriageStatus;
import com.quzzar.villagelife.village.Occupation;

import net.minecraft.core.BlockPos;

public class PersonBkEvent extends BkEvent {
  
  private UUID uuid;
  private long location;
  private Occupation occupation;
  private MarriageStatus marriageStatus;

  public PersonBkEvent(UUID uuid, long location, Occupation occupation, MarriageStatus marriageStatus){
    super();

    this.uuid = uuid;
    this.location = location;
    this.occupation = occupation;
    this.marriageStatus = marriageStatus;

  }

  public UUID getPersonUUID(){
    return uuid;
  }

  public long getPersonLocation(){
    return location;
  }
  public BlockPos getPersonBlockPos(){
    return BlockPos.of(location);
  }

  public Occupation getPersonOccupation(){
    return occupation;
  }

  public MarriageStatus getPersonMarriageStatus(){
    return marriageStatus;
  }

}
