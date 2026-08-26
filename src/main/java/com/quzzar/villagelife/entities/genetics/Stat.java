package com.quzzar.villagelife.entities.genetics;

/**
 * The heritable stats every person carries. The six D&D-style ability scores
 * plus two physical traits (size, eyesight) that use the same 3-18 scale and
 * feed the same projection matrix. See docs/genetics-and-attributes.md.
 *
 * Mental stats (INTELLIGENCE, WISDOM, CHARISMA) deliberately have few or no
 * physical projections yet: they exist for social systems, dialogue, and the
 * LLM persona, and get numeric consumers only when a system actually reads
 * them. (Pathfinder 2e precedent: WIS maps to Perception, which is why WIS
 * contributes to follow range alongside eyesight.)
 */
public enum Stat {
  STRENGTH("Strength"),
  DEXTERITY("Dexterity"),
  CONSTITUTION("Constitution"),
  INTELLIGENCE("Intelligence"),
  WISDOM("Wisdom"),
  CHARISMA("Charisma"),
  SIZE("Size"),
  EYESIGHT("Eyesight");

  /** Baseline score: contributions are proportional to (score - AVERAGE). */
  public static final int AVERAGE = 10;
  public static final int MIN = 3;
  public static final int MAX = 18;

  private final String nbtKey;

  private Stat(String nbtKey) {
    this.nbtKey = nbtKey;
  }

  public String getNbtKey() {
    return nbtKey;
  }
}
