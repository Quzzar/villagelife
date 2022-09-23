package com.quzzar.villagelife.village.bookkeeping;

import java.io.Serializable;
import java.util.UUID;

public class BkEvent implements Serializable {
  
  private UUID uuid;
  private float impact;

  public BkEvent(){

    this.uuid = UUID.randomUUID();
    this.impact = 1.0F;

  }

  public UUID getEventID(){
    return uuid;
  }

  public float getImpact(){
    return impact;
  }
  public void setImpact(float impact){
    this.impact = impact;
  }

}
