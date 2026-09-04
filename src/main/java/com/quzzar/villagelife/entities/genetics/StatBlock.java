package com.quzzar.villagelife.entities.genetics;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

import net.minecraft.nbt.CompoundTag;

/**
 * A person's rolled genetics: one 3-18 score per {@link Stat} plus at most one
 * {@link GeneticCondition}. This is the stored genetic truth; Minecraft
 * attribute modifiers are always derived from it by {@link StatProjection} and
 * never stored here. Scores are immutable after rolling.
 *
 * First-generation villagers roll 3d6 per stat (bell curve around 10.5).
 * Children blend their parents' midpoint with a smaller fresh 3d6 component,
 * so family resemblance is strong without making siblings identical.
 */
public final class StatBlock {

  private static final String CONDITION_KEY = "Condition";
  private static final double SIZE_PARENT_WEIGHT = 0.80D;
  private static final double DEFAULT_PARENT_WEIGHT = 0.70D;

  private final EnumMap<Stat, Integer> scores;
  private final GeneticCondition condition;

  private StatBlock(EnumMap<Stat, Integer> scores, GeneticCondition condition) {
    this.scores = scores;
    this.condition = condition;
  }

  /** Rolls a fresh first-generation stat block: 3d6 per stat, rare condition roll. */
  public static StatBlock roll(RandomGenerator random) {
    EnumMap<Stat, Integer> scores = new EnumMap<>(Stat.class);
    for (Stat stat : Stat.values()) {
      scores.put(stat, rollScore(random));
    }
    return new StatBlock(scores, GeneticCondition.roll(random));
  }

  /**
   * Produces a child's mechanical genome. Size is strongly parent-centered to
   * mirror human height's high heritability; the other scores retain a little
   * more non-parental variation. The fresh 3d6 term also prevents family lines
   * from drifting permanently toward the score limits.
   */
  public static StatBlock inherit(StatBlock first, StatBlock second, RandomGenerator random) {
    Objects.requireNonNull(first);
    Objects.requireNonNull(second);
    Objects.requireNonNull(random);

    EnumMap<Stat, Integer> scores = new EnumMap<>(Stat.class);
    for (Stat stat : Stat.values()) {
      double parentWeight = stat == Stat.SIZE ? SIZE_PARENT_WEIGHT : DEFAULT_PARENT_WEIGHT;
      double parentMidpoint = (first.get(stat) + second.get(stat)) / 2.0D;
      double inherited = parentMidpoint * parentWeight + rollScore(random) * (1.0D - parentWeight);
      scores.put(stat, clampScore((int) Math.round(inherited)));
    }
    return new StatBlock(scores,
        GeneticCondition.rollChild(first.getCondition(), second.getCondition(), random));
  }

  private static int rollScore(RandomGenerator random) {
    int rolled = 0;
    for (int die = 0; die < 3; die++) {
      rolled += 1 + random.nextInt(6);
    }
    return rolled;
  }

  public int get(Stat stat) {
    return scores.get(stat);
  }

  public GeneticCondition getCondition() {
    return condition;
  }

  /** Creates a validated block from an explicit complete score map. */
  static StatBlock ofScores(Map<Stat, Integer> input, GeneticCondition condition) {
    EnumMap<Stat, Integer> scores = new EnumMap<>(Stat.class);
    for (Stat stat : Stat.values()) {
      int score = Objects.requireNonNull(input.get(stat), "Missing score for " + stat);
      scores.put(stat, clampScore(score));
    }
    return new StatBlock(scores, Objects.requireNonNull(condition));
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
      scores.put(stat, clampScore(value));
    }
    return ofScores(scores, GeneticCondition.byName(tag.getString(CONDITION_KEY)));
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

  private static int clampScore(int score) {
    return Math.max(Stat.MIN, Math.min(Stat.MAX, score));
  }
}
