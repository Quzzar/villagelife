package com.quzzar.villagelife.entities.genetics;

import java.util.EnumMap;
import java.util.Objects;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * A person's rolled genetics: one 3-18 score per {@link Stat} plus at most one
 * {@link GeneticCondition}. This is the stored genetic truth; Minecraft
 * attribute modifiers are always derived from it by {@link StatProjection} and
 * never stored here. Scores are immutable after rolling.
 *
 * First-generation villagers roll 3d6 per stat (bell curve around 10.5).
 * Inheritance (children rolling near their parents' averages) layers on later
 * with the family system; the projection side is already agnostic to where the
 * scores came from.
 */
public final class StatBlock {

  private static final String CONDITION_KEY = "Condition";

  private final EnumMap<Stat, Integer> scores;
  private final GeneticCondition condition;

  private StatBlock(EnumMap<Stat, Integer> scores, GeneticCondition condition) {
    this.scores = scores;
    this.condition = condition;
  }

  /** Rolls a fresh first-generation stat block: 3d6 per stat, rare condition roll. */
  public static StatBlock roll(RandomSource random) {
    EnumMap<Stat, Integer> scores = new EnumMap<>(Stat.class);
    for (Stat stat : Stat.values()) {
      int rolled = 0;
      for (int die = 0; die < 3; die++) {
        rolled += 1 + random.nextInt(6);
      }
      scores.put(stat, rolled);
    }
    return new StatBlock(scores, GeneticCondition.roll(random));
  }

  public int get(Stat stat) {
    return scores.get(stat);
  }

  public GeneticCondition getCondition() {
    return condition;
  }

  /** Returns the same genetic scores with a different rare condition. */
  public StatBlock withCondition(GeneticCondition replacement) {
    return new StatBlock(new EnumMap<>(scores), Objects.requireNonNull(replacement));
  }

  public CompoundTag save() {
    CompoundTag tag = new CompoundTag();
    for (Stat stat : Stat.values()) {
      tag.putInt(stat.getNbtKey(), scores.get(stat));
    }
    if (condition != GeneticCondition.NONE) {
      tag.putString(CONDITION_KEY, condition.name());
    }
    return tag;
  }

  /**
   * Loads a stat block from NBT. Stats added after the entity was saved fall
   * back to {@link Stat#AVERAGE} instead of failing, and every value is
   * clamped to the legal range, so hand-edited or stale data degrades safely.
   */
  public static StatBlock load(CompoundTag tag) {
    EnumMap<Stat, Integer> scores = new EnumMap<>(Stat.class);
    for (Stat stat : Stat.values()) {
      int value = tag.contains(stat.getNbtKey()) ? tag.getInt(stat.getNbtKey()) : Stat.AVERAGE;
      scores.put(stat, Mth.clamp(value, Stat.MIN, Stat.MAX));
    }
    return new StatBlock(scores, GeneticCondition.byName(tag.getString(CONDITION_KEY)));
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder("StatBlock[");
    for (Stat stat : Stat.values()) {
      builder.append(stat.getNbtKey()).append('=').append(scores.get(stat)).append(' ');
    }
    builder.append(condition).append(']');
    return builder.toString();
  }
}
