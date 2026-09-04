package com.quzzar.kithkyn.entities.genetics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;

class StatBlockTest {

  @Test
  void savedGenomeRoundTripsEveryScoreAndCondition() {
    EnumMap<Stat, Integer> scores = new EnumMap<>(Stat.class);
    int score = Stat.MIN;
    for (Stat stat : Stat.values()) {
      scores.put(stat, score++);
    }
    StatBlock original = StatBlock.ofScores(scores, GeneticCondition.HETEROCHROMIA);

    StatBlock restored = StatBlock.load(original.save());

    for (Stat stat : Stat.values()) {
      assertEquals(original.get(stat), restored.get(stat), stat.name());
    }
    assertEquals(GeneticCondition.HETEROCHROMIA, restored.getCondition());
  }

  @Test
  void staleOrHandEditedGenomeLoadsWithSafeDefaults() {
    CompoundTag saved = new CompoundTag();
    saved.putInt(Stat.SIZE.getNbtKey(), 99);
    saved.putInt(Stat.STRENGTH.getNbtKey(), -99);
    saved.putString("Condition", "REMOVED_CONDITION");

    StatBlock restored = StatBlock.load(saved);

    assertEquals(Stat.MAX, restored.get(Stat.SIZE));
    assertEquals(Stat.MIN, restored.get(Stat.STRENGTH));
    for (Stat stat : Stat.values()) {
      if (stat != Stat.SIZE && stat != Stat.STRENGTH) {
        assertEquals(Stat.AVERAGE, restored.get(stat), stat.name());
      }
    }
    assertEquals(GeneticCondition.NONE, restored.getCondition());
  }

  @Test
  void parentalScoresDominateTheFreshPopulationComponent() {
    StatBlock highParents = uniformBlock(18, GeneticCondition.NONE);
    StatBlock lowParents = uniformBlock(3, GeneticCondition.NONE);

    for (int seed = 0; seed < 1_000; seed++) {
      StatBlock highChild = StatBlock.inherit(highParents, highParents, new Random(seed));
      StatBlock lowChild = StatBlock.inherit(lowParents, lowParents, new Random(seed));
      for (Stat stat : Stat.values()) {
        int highFloor = stat == Stat.SIZE ? 15 : 14;
        int lowCeiling = stat == Stat.SIZE ? 6 : 8;
        assertTrue(highChild.get(stat) >= highFloor);
        assertTrue(lowChild.get(stat) <= lowCeiling);
      }
    }
  }

  @Test
  void siblingsVaryWithoutEscapingTheLegalScoreRange() {
    StatBlock firstParent = uniformBlock(8, GeneticCondition.NONE);
    StatBlock secondParent = uniformBlock(14, GeneticCondition.NONE);
    Set<Integer> strengthScores = new HashSet<>();
    Set<Integer> sizeScores = new HashSet<>();

    for (int seed = 0; seed < 1_000; seed++) {
      StatBlock child = StatBlock.inherit(firstParent, secondParent, new Random(seed));
      strengthScores.add(child.get(Stat.STRENGTH));
      sizeScores.add(child.get(Stat.SIZE));
      for (Stat stat : Stat.values()) {
        assertTrue(child.get(stat) >= Stat.MIN && child.get(stat) <= Stat.MAX);
      }
    }

    assertTrue(strengthScores.size() >= 3);
    assertTrue(sizeScores.size() >= 2);
  }

  private static StatBlock uniformBlock(int score, GeneticCondition condition) {
    EnumMap<Stat, Integer> scores = new EnumMap<>(Stat.class);
    for (Stat stat : Stat.values()) {
      scores.put(stat, score);
    }
    return StatBlock.ofScores(scores, condition);
  }
}
