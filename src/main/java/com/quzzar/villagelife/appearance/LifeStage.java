package com.quzzar.villagelife.appearance;

/** The visual life stage that selects adult or child wardrobes. */
public enum LifeStage {
  ADULT,
  CHILD;

  static LifeStage parse(String value) {
    return switch (value) {
      case "adult" -> ADULT;
      case "child" -> CHILD;
      default -> throw new IllegalArgumentException("Unknown life stage: " + value);
    };
  }
}
