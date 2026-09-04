package com.quzzar.kithkyn.village.bookkeeping;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;

public class InternalBookkeeper {

  public static final Codec<InternalBookkeeper> CODEC = BookkeepingEvent.DISPATCH_CODEC.listOf().xmap(events -> {
    InternalBookkeeper bookkeeper = new InternalBookkeeper();
    events.forEach(bookkeeper::addEvent);
    return bookkeeper;
  }, bookkeeper -> List.copyOf(bookkeeper.eventLog.values()));

  private final float FORGET_RATE = 0.99F;
  private final float MIN_IMPACT = 0.01F;

  private HashMap<UUID, BookkeepingEvent> eventLog = new HashMap<>();

  public InternalBookkeeper() {

  }

  // Currently every 10 seconds
  public void update() {

    // Events slowly lose impact until they're removed from map
    for (Iterator<Map.Entry<UUID, BookkeepingEvent>> it = eventLog.entrySet().iterator(); it.hasNext();) {
      Map.Entry<UUID, BookkeepingEvent> entry = it.next();
      if (entry.getValue().getImpact() < MIN_IMPACT) {
        it.remove();
      } else {
        entry.getValue().setImpact(entry.getValue().getImpact() * FORGET_RATE);
      }
    }

  }

  public void addEvent(BookkeepingEvent event) {

    eventLog.put(event.getEventID(), event);

  }

  /** Sum of the decaying impact of all live events of the given type. */
  public float totalImpact(Class<? extends BookkeepingEvent> type) {
    float sum = 0F;
    for (BookkeepingEvent event : eventLog.values()) {
      if (type.isInstance(event)) {
        sum += event.getImpact();
      }
    }
    return sum;
  }

}
