package com.quzzar.kithkyn.appearance;

import java.util.List;

import com.quzzar.kithkyn.entities.genetics.PigmentGene;

/** Curated natural-color ranges projected from the two expressed pigment loci. */
public final class PigmentPalette {

  private static final int SKIN_LIGHT_COOL = 0xF3D8CE;
  private static final int SKIN_LIGHT_WARM = 0xF4C69A;
  private static final int SKIN_DARK_COOL = 0x6E4B43;
  private static final int SKIN_DARK_WARM = 0x7A4A32;
  private static final int SKIN_SHADOW = 0x3E2923;

  private static final int HAIR_LIGHT_COOL = 0xD8C38B;
  private static final int HAIR_LIGHT_WARM = 0xD89B52;
  private static final int HAIR_DARK_COOL = 0x1C1A1B;
  private static final int HAIR_DARK_WARM = 0x3B211D;
  private static final int HAIR_SHADOW = 0x100C0C;

  private static final int EYE_LIGHT_COOL = 0x6D9FBC;
  private static final int EYE_LIGHT_WARM = 0x6FA06F;
  private static final int EYE_DARK_COOL = 0x5A4A3C;
  private static final int EYE_DARK_WARM = 0x70461F;
  private static final int EYE_SHADOW = 0x171A18;

  private PigmentPalette() {
  }

  public static PigmentColor skin(PigmentGene gene) {
    int base = range(gene, SKIN_LIGHT_COOL, SKIN_LIGHT_WARM, SKIN_DARK_COOL, SKIN_DARK_WARM);
    return new PigmentColor(base, mix(base, SKIN_SHADOW, 0.22F));
  }

  public static PigmentColor hair(PigmentGene gene) {
    int base = range(gene, HAIR_LIGHT_COOL, HAIR_LIGHT_WARM, HAIR_DARK_COOL, HAIR_DARK_WARM);
    return new PigmentColor(base, mix(base, HAIR_SHADOW, 0.32F));
  }

  public static PigmentColor eyes(PigmentGene gene) {
    int base = range(gene, EYE_LIGHT_COOL, EYE_LIGHT_WARM, EYE_DARK_COOL, EYE_DARK_WARM);
    return new PigmentColor(base, mix(base, EYE_SHADOW, 0.38F));
  }

  /** Make the rare second iris visibly different when two nearby genes round alike. */
  public static PigmentColor ensureDistinctEyes(PigmentColor first, PigmentColor second) {
    if (distanceSquared(first.baseRgb(), second.baseRgb()) >= 28 * 28) {
      return second;
    }
    List<PigmentColor> anchors = List.of(
        eyes(PigmentGene.homozygous(0, 0)),
        eyes(PigmentGene.homozygous(0, 255)),
        eyes(PigmentGene.homozygous(255, 0)),
        eyes(PigmentGene.homozygous(255, 255)));
    return anchors.stream()
        .max((left, right) -> Integer.compare(
            distanceSquared(first.baseRgb(), left.baseRgb()),
            distanceSquared(first.baseRgb(), right.baseRgb())))
        .orElseThrow();
  }

  private static int range(PigmentGene gene, int lightCool, int lightWarm, int darkCool, int darkWarm) {
    int light = mix(lightCool, lightWarm, gene.warmth());
    int dark = mix(darkCool, darkWarm, gene.warmth());
    return mix(light, dark, gene.depth());
  }

  private static int mix(int first, int second, float amount) {
    int red = mixChannel(first >>> 16 & 0xFF, second >>> 16 & 0xFF, amount);
    int green = mixChannel(first >>> 8 & 0xFF, second >>> 8 & 0xFF, amount);
    int blue = mixChannel(first & 0xFF, second & 0xFF, amount);
    return red << 16 | green << 8 | blue;
  }

  private static int mixChannel(int first, int second, float amount) {
    return Math.round(first + (second - first) * amount);
  }

  private static int distanceSquared(int first, int second) {
    int red = (first >>> 16 & 0xFF) - (second >>> 16 & 0xFF);
    int green = (first >>> 8 & 0xFF) - (second >>> 8 & 0xFF);
    int blue = (first & 0xFF) - (second & 0xFF);
    return red * red + green * green + blue * blue;
  }
}
