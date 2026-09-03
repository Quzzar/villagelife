package com.quzzar.villagelife.appearance;

/** Player-model geometry used to interpret the arm texels of a skin sheet. */
public enum BodyModel {
  WIDE,
  SLIM,
  UNIVERSAL;

  static BodyModel parse(String value) {
    return switch (value) {
      case "wide" -> WIDE;
      case "slim" -> SLIM;
      case "universal" -> UNIVERSAL;
      default -> throw new IllegalArgumentException("Unknown body model: " + value);
    };
  }
}
