package com.quzzar.villagelife.village.bookkeeping;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.quzzar.villagelife.Villagelife;

public class InternalBookkeeper implements Serializable {
  
  private final float FORGET_RATE = 0.99F;
  private final float MIN_IMPACT = 0.01F;

  private HashMap<UUID, BkEvent> eventLog = new HashMap<>();

  public InternalBookkeeper() {



  }

  // Currently every 10 seconds
  public void update(){

    // Events slowly lose impact until they're removed from map
    for(Iterator<Map.Entry<UUID, BkEvent>> it = eventLog.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<UUID, BkEvent> entry = it.next();
      if(entry.getValue().getImpact() < MIN_IMPACT){
        it.remove();
      } else {
        entry.getValue().setImpact(entry.getValue().getImpact()*FORGET_RATE);
      }
    }


    //Villagelife.LOGGER.debug(eventLog.size()+" Events");

  }


  public void addEvent(BkEvent event){

    eventLog.put(event.getEventID(), event);

  }


}
