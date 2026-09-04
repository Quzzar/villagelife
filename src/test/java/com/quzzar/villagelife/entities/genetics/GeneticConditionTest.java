package com.quzzar.villagelife.entities.genetics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GeneticConditionTest {

  @Test
  void achondroplasiaModelUsesDominantLiveBirthProbabilities() {
    assertEquals(0.01F,
        GeneticCondition.DWARFISM.childChance(GeneticCondition.NONE, GeneticCondition.NONE));
    assertEquals(0.50F,
        GeneticCondition.DWARFISM.childChance(GeneticCondition.DWARFISM, GeneticCondition.NONE));
    assertEquals(2.0F / 3.0F,
        GeneticCondition.DWARFISM.childChance(GeneticCondition.DWARFISM, GeneticCondition.DWARFISM));
  }

  @Test
  void familialPituitaryModelCombinesDominantTransmissionAndPenetrance() {
    assertEquals(0.01F,
        GeneticCondition.GIGANTISM.childChance(GeneticCondition.NONE, GeneticCondition.NONE));
    assertEquals(0.125F,
        GeneticCondition.GIGANTISM.childChance(GeneticCondition.GIGANTISM, GeneticCondition.NONE));
    assertEquals(0.1875F,
        GeneticCondition.GIGANTISM.childChance(GeneticCondition.GIGANTISM, GeneticCondition.GIGANTISM));
  }

  @Test
  void familialCongenitalHeterochromiaUsesDominantProbabilities() {
    assertEquals(0.01F,
        GeneticCondition.HETEROCHROMIA.childChance(GeneticCondition.NONE, GeneticCondition.NONE));
    assertEquals(0.50F,
        GeneticCondition.HETEROCHROMIA.childChance(GeneticCondition.HETEROCHROMIA, GeneticCondition.NONE));
    assertEquals(0.75F,
        GeneticCondition.HETEROCHROMIA.childChance(
            GeneticCondition.HETEROCHROMIA, GeneticCondition.HETEROCHROMIA));
  }
}
