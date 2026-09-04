package com.quzzar.villagelife.entities.genetics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.util.RandomSource;

class AppearanceGenesTest {

  @Test
  void savedAppearanceGenomeRoundTripsEveryChannelAndAllele() {
    AppearanceGenes original = new AppearanceGenes(
        101,
        202,
        303,
        404,
        PigmentGene.ofAlleles(12, 34, 56, 78),
        PigmentGene.ofAlleles(90, 123, 145, 167),
        PigmentGene.ofAlleles(189, 201, 223, 245),
        PigmentGene.ofAlleles(254, 232, 210, 188));

    AppearanceGenes restored = AppearanceGenes.load(original.save());

    assertEquals(original, restored);
  }

  @Test
  void inheritedPigmentAllelesComeFromTheCorrectParentWithinMutationBounds() {
    PigmentGene firstPigment = PigmentGene.ofAlleles(40, 80, 60, 100);
    PigmentGene secondPigment = PigmentGene.ofAlleles(160, 200, 180, 220);
    AppearanceGenes first = appearanceWithPigment(11, firstPigment);
    AppearanceGenes second = appearanceWithPigment(22, secondPigment);

    for (int seed = 0; seed < 4_096; seed++) {
      AppearanceGenes child = AppearanceGenes.inherit(first, second, RandomSource.create(seed));

      assertInheritedPigment(child.skinPigment(), firstPigment, secondPigment);
      assertInheritedPigment(child.hairPigment(), firstPigment, secondPigment);
      assertInheritedPigment(child.eyePigment(), firstPigment, secondPigment);
      assertInheritedPigment(child.alternateEyePigment(), firstPigment, secondPigment);
    }
  }

  private static AppearanceGenes appearanceWithPigment(int structure, PigmentGene pigment) {
    return new AppearanceGenes(
        structure,
        structure,
        structure,
        structure,
        pigment,
        pigment,
        pigment,
        pigment);
  }

  private static void assertInheritedPigment(
      PigmentGene child,
      PigmentGene first,
      PigmentGene second) {
    assertWithinMutationRange(child.firstDepth(), first.firstDepth(), first.secondDepth());
    assertWithinMutationRange(child.secondDepth(), second.firstDepth(), second.secondDepth());
    assertWithinMutationRange(child.firstWarmth(), first.firstWarmth(), first.secondWarmth());
    assertWithinMutationRange(child.secondWarmth(), second.firstWarmth(), second.secondWarmth());
  }

  private static void assertWithinMutationRange(int actual, int firstAllele, int secondAllele) {
    int distance = Math.min(Math.abs(actual - firstAllele), Math.abs(actual - secondAllele));
    assertTrue(distance <= 16,
        () -> actual + " is more than one mutation step from " + firstAllele + " or " + secondAllele);
  }
}
