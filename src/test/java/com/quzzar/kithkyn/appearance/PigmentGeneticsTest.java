package com.quzzar.kithkyn.appearance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.quzzar.kithkyn.entities.genetics.PigmentGene;

class PigmentGeneticsTest {

  @Test
  void childOfLightAndDarkHomozygousParentsExpressesAnIntermediatePigment() {
    PigmentGene light = PigmentGene.homozygous(24, 96);
    PigmentGene dark = PigmentGene.homozygous(232, 160);

    PigmentGene child = PigmentGene.ofAlleles(
        light.firstDepth(),
        dark.firstDepth(),
        light.firstWarmth(),
        dark.firstWarmth());

    assertTrue(child.depth() > light.depth() && child.depth() < dark.depth());
    assertTrue(child.warmth() > light.warmth() && child.warmth() < dark.warmth());
  }

  @Test
  void hairRangeProgressesFromBlondeThroughBrownToBlack() {
    PigmentColor blonde = PigmentPalette.hair(PigmentGene.homozygous(24, 96));
    PigmentColor brown = PigmentPalette.hair(PigmentGene.homozygous(128, 96));
    PigmentColor black = PigmentPalette.hair(PigmentGene.homozygous(232, 96));

    assertTrue(luminance(blonde.baseRgb()) > luminance(brown.baseRgb()));
    assertTrue(luminance(brown.baseRgb()) > luminance(black.baseRgb()));
    assertNotEquals(blonde.shadowRgb(), blonde.baseRgb());
    assertNotEquals(brown.shadowRgb(), brown.baseRgb());
    assertNotEquals(black.shadowRgb(), black.baseRgb());
  }

  @Test
  void warmthChangesHueWithoutChangingTextureStructure() {
    PigmentColor ashHair = PigmentPalette.hair(PigmentGene.homozygous(96, 0));
    PigmentColor warmHair = PigmentPalette.hair(PigmentGene.homozygous(96, 255));
    PigmentColor blueEye = PigmentPalette.eyes(PigmentGene.homozygous(24, 0));
    PigmentColor greenEye = PigmentPalette.eyes(PigmentGene.homozygous(24, 255));

    assertNotEquals(ashHair, warmHair);
    assertNotEquals(blueEye, greenEye);
  }

  @Test
  void eyeContrastFallbackMakesHeterochromiaVisible() {
    PigmentColor eye = PigmentPalette.eyes(PigmentGene.homozygous(128, 128));
    PigmentColor distinct = PigmentPalette.ensureDistinctEyes(eye, eye);

    assertTrue(colorDistance(eye.baseRgb(), distinct.baseRgb()) >= 28.0D);
    assertEquals(distinct, PigmentPalette.ensureDistinctEyes(eye, distinct));
  }

  private static int luminance(int rgb) {
    int red = rgb >>> 16 & 0xFF;
    int green = rgb >>> 8 & 0xFF;
    int blue = rgb & 0xFF;
    return red * 54 + green * 183 + blue * 19;
  }

  private static double colorDistance(int first, int second) {
    int red = (first >>> 16 & 0xFF) - (second >>> 16 & 0xFF);
    int green = (first >>> 8 & 0xFF) - (second >>> 8 & 0xFF);
    int blue = (first & 0xFF) - (second & 0xFF);
    return Math.sqrt(red * red + green * green + blue * blue);
  }
}
