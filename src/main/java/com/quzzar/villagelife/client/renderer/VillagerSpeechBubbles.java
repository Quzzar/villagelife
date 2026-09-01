package com.quzzar.villagelife.client.renderer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/** Client-side, short-lived speech received for nearby villagers. */
public final class VillagerSpeechBubbles {

  private static final long DISPLAY_MS = 6_000L;
  private static final Map<Integer, Bubble> BUBBLES = new ConcurrentHashMap<>();

  private VillagerSpeechBubbles() {
  }

  public static void show(int entityId, String text) {
    BUBBLES.put(entityId, new Bubble(text, System.currentTimeMillis() + DISPLAY_MS));
  }

  @Nullable
  public static String visibleText(int entityId) {
    Bubble bubble = BUBBLES.get(entityId);
    if (bubble == null) {
      return null;
    }
    if (System.currentTimeMillis() >= bubble.expiresAtMs()) {
      BUBBLES.remove(entityId, bubble);
      return null;
    }
    return bubble.text();
  }

  private record Bubble(String text, long expiresAtMs) {
  }
}
