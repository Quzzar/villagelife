package com.quzzar.villagelife.chat;

/** Final cleanup applied to prose produced by a villager. */
public final class VillagerText {

  private VillagerText() {
  }

  /**
   * Enforces punctuation rules after generation. The prompt asks the model not
   * to use em dashes, while this boundary guarantees they never reach chat,
   * speech bubbles, transcripts, or conversation memories.
   */
  public static String clean(String text) {
    return text.replace('\u2014', ';');
  }
}
