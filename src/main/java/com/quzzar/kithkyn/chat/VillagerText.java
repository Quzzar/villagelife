package com.quzzar.kithkyn.chat;

/** Final cleanup applied to prose produced by a villager. */
public final class VillagerText {

  private VillagerText() {
  }

  /**
   * Enforces punctuation rules after generation. The prompt asks the model not
   * to use em dashes, while this boundary guarantees they never reach chat,
   * speech bubbles, transcripts, or conversation memories.
   *
   * The swap respects spacing: "a \u2014 b", "a\u2014b", and "a \u2014b" all become "a; b",
   * with the semicolon hugging the word before it and a single space after,
   * never a floating " ; ".
   */
  public static String clean(String text) {
    String out = text.replaceAll("\\s*\u2014+\\s*", "; ");
    out = out.replaceAll("^;\\s*", ""); // a line that opened with a dash
    out = out.replaceAll(";\\s*$", ";"); // one that trailed off with one
    return out;
  }
}
