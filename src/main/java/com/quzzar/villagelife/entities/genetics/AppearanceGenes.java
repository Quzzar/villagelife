package com.quzzar.villagelife.entities.genetics;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

/**
 * Independently inheritable appearance channels. They select semantic assets,
 * never clothing: a child can inherit each visible family resemblance from a
 * different parent while their current work still decides what they wear.
 */
public record AppearanceGenes(
    int skin,
    int hair,
    int eyes,
    int alternateEyes,
    PigmentGene skinPigment,
    PigmentGene hairPigment,
    PigmentGene eyePigment,
    PigmentGene alternateEyePigment) {

  private static final int MUTATION_DENOMINATOR = 32;

  public static AppearanceGenes roll(RandomSource random) {
    return new AppearanceGenes(
        random.nextInt(),
        random.nextInt(),
        random.nextInt(),
        random.nextInt(),
        PigmentGene.roll(random),
        PigmentGene.roll(random),
        PigmentGene.roll(random),
        PigmentGene.roll(random));
  }

  /** Stable upgrade path for people saved before independent appearance genes existed. */
  public static AppearanceGenes fromLegacySeed(int seed) {
    int skin = mix(seed ^ 0x13579BDF);
    int hair = mix(seed ^ 0x2468ACE0);
    int eyes = mix(seed ^ 0x6A09E667);
    int alternateEyes = mix(seed ^ 0xBB67AE85);
    return new AppearanceGenes(
        skin,
        hair,
        eyes,
        alternateEyes,
        PigmentGene.fromSeed(mix(seed ^ 0x3C6EF372)),
        PigmentGene.fromSeed(mix(seed ^ 0xA54FF53A)),
        PigmentGene.fromSeed(mix(seed ^ 0x510E527F)),
        PigmentGene.fromSeed(mix(seed ^ 0x9B05688C)));
  }

  /**
   * Recombines the parents one channel at a time. A small mutation chance keeps
   * family lines from collapsing into a finite set of founder combinations.
   */
  public static AppearanceGenes inherit(AppearanceGenes first, AppearanceGenes second, RandomSource random) {
    return new AppearanceGenes(
        inheritGene(first.skin, second.skin, random),
        inheritGene(first.hair, second.hair, random),
        inheritGene(first.eyes, second.eyes, random),
        inheritGene(first.alternateEyes, second.alternateEyes, random),
        PigmentGene.inherit(first.skinPigment, second.skinPigment, random),
        PigmentGene.inherit(first.hairPigment, second.hairPigment, random),
        PigmentGene.inherit(first.eyePigment, second.eyePigment, random),
        PigmentGene.inherit(first.alternateEyePigment, second.alternateEyePigment, random));
  }

  private static int inheritGene(int first, int second, RandomSource random) {
    if (random.nextInt(MUTATION_DENOMINATOR) == 0) {
      return random.nextInt();
    }
    return random.nextBoolean() ? first : second;
  }

  private static int mix(int value) {
    value ^= value >>> 16;
    value *= 0x7FEB352D;
    value ^= value >>> 15;
    value *= 0x846CA68B;
    return value ^ value >>> 16;
  }

  public CompoundTag save() {
    CompoundTag tag = new CompoundTag();
    tag.putInt("Skin", skin);
    tag.putInt("Hair", hair);
    tag.putInt("Eyes", eyes);
    tag.putInt("AlternateEyes", alternateEyes);
    tag.putInt("SkinPigment", skinPigment.packed());
    tag.putInt("HairPigment", hairPigment.packed());
    tag.putInt("EyePigment", eyePigment.packed());
    tag.putInt("AlternateEyePigment", alternateEyePigment.packed());
    return tag;
  }

  public static AppearanceGenes load(CompoundTag tag) {
    int skin = tag.getInt("Skin");
    int hair = tag.getInt("Hair");
    int eyes = tag.getInt("Eyes");
    int alternateEyes = tag.getInt("AlternateEyes");
    return new AppearanceGenes(
        skin,
        hair,
        eyes,
        alternateEyes,
        loadPigment(tag, "SkinPigment", skin ^ 0x3C6EF372),
        loadPigment(tag, "HairPigment", hair ^ 0xA54FF53A),
        loadPigment(tag, "EyePigment", eyes ^ 0x510E527F),
        loadPigment(tag, "AlternateEyePigment", alternateEyes ^ 0x9B05688C));
  }

  private static PigmentGene loadPigment(CompoundTag tag, String key, int fallbackSeed) {
    return tag.contains(key)
        ? new PigmentGene(tag.getInt(key))
        : PigmentGene.fromSeed(fallbackSeed);
  }
}
