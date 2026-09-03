package com.quzzar.villagelife.appearance;

/** Two flat RGB shades used to recolor one semantic pixel-art material. */
public record PigmentColor(int baseRgb, int shadowRgb) {

  public PigmentColor {
    validateRgb(baseRgb);
    validateRgb(shadowRgb);
  }

  private static void validateRgb(int rgb) {
    if ((rgb & 0xFF000000) != 0) {
      throw new IllegalArgumentException("Pigment color must be a 24-bit RGB value");
    }
  }
}
