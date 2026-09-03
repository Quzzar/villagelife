package com.quzzar.villagelife.appearance;

import com.quzzar.villagelife.entities.Gender;

/** Gender-expression category attached to one independently selectable layer. */
public enum PartGender {
  MALE,
  FEMALE,
  NONBINARY;

  static PartGender parse(String value) {
    return switch (value) {
      case "male" -> MALE;
      case "female" -> FEMALE;
      case "nonbinary" -> NONBINARY;
      default -> throw new IllegalArgumentException("Unknown part gender: " + value);
    };
  }

  /**
   * Shared pieces fit every expression. Definitive masculine and feminine
   * pieces only fit their matching expression, including for a non-binary
   * person whose complete recipe has chosen that expression.
   */
  public boolean fits(Gender expression) {
    return this == NONBINARY || name().equals(expression.name());
  }
}
