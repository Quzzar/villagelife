package com.quzzar.kithkyn.entities.genetics;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Two diploid color loci packed into one integer. The first locus controls
 * pigment depth; the second controls warmth or hue. A child takes one allele
 * for each locus from each parent, so its expressed midpoint can sit between
 * both parents without blending texture geometry.
 */
public record PigmentGene(int packed) {

  private static final int MAXIMUM_ALLELE = 255;
  private static final int MUTATION_DENOMINATOR = 64;
  private static final int MAXIMUM_MUTATION_STEP = 16;

  public static PigmentGene roll(RandomSource random) {
    return ofAlleles(
        random.nextInt(MAXIMUM_ALLELE + 1),
        random.nextInt(MAXIMUM_ALLELE + 1),
        random.nextInt(MAXIMUM_ALLELE + 1),
        random.nextInt(MAXIMUM_ALLELE + 1));
  }

  public static PigmentGene fromSeed(int seed) {
    return new PigmentGene(mix(seed));
  }

  public static PigmentGene ofAlleles(int firstDepth, int secondDepth, int firstWarmth, int secondWarmth) {
    validateAllele(firstDepth);
    validateAllele(secondDepth);
    validateAllele(firstWarmth);
    validateAllele(secondWarmth);
    return new PigmentGene(
        firstDepth
            | secondDepth << 8
            | firstWarmth << 16
            | secondWarmth << 24);
  }

  public static PigmentGene homozygous(int depth, int warmth) {
    return ofAlleles(depth, depth, warmth, warmth);
  }

  /** Recombine one randomly selected allele per locus from each parent. */
  public static PigmentGene inherit(PigmentGene first, PigmentGene second, RandomSource random) {
    return ofAlleles(
        inheritedAllele(first.firstDepth(), first.secondDepth(), random),
        inheritedAllele(second.firstDepth(), second.secondDepth(), random),
        inheritedAllele(first.firstWarmth(), first.secondWarmth(), random),
        inheritedAllele(second.firstWarmth(), second.secondWarmth(), random));
  }

  public int firstDepth() {
    return allele(0);
  }

  public int secondDepth() {
    return allele(8);
  }

  public int firstWarmth() {
    return allele(16);
  }

  public int secondWarmth() {
    return allele(24);
  }

  /** Expressed pigment depth in the inclusive range 0..1. */
  public float depth() {
    return phenotype(firstDepth(), secondDepth());
  }

  /** Expressed warmth or hue in the inclusive range 0..1. */
  public float warmth() {
    return phenotype(firstWarmth(), secondWarmth());
  }

  private int allele(int shift) {
    return packed >>> shift & MAXIMUM_ALLELE;
  }

  private static float phenotype(int first, int second) {
    return (first + second) / (2.0F * MAXIMUM_ALLELE);
  }

  private static int inheritedAllele(int first, int second, RandomSource random) {
    int inherited = random.nextBoolean() ? first : second;
    if (random.nextInt(MUTATION_DENOMINATOR) == 0) {
      inherited += random.nextInt(MAXIMUM_MUTATION_STEP * 2 + 1) - MAXIMUM_MUTATION_STEP;
    }
    return Mth.clamp(inherited, 0, MAXIMUM_ALLELE);
  }

  private static void validateAllele(int allele) {
    if (allele < 0 || allele > MAXIMUM_ALLELE) {
      throw new IllegalArgumentException("Pigment allele is outside 0..255: " + allele);
    }
  }

  private static int mix(int value) {
    value ^= value >>> 16;
    value *= 0x7FEB352D;
    value ^= value >>> 15;
    value *= 0x846CA68B;
    return value ^ value >>> 16;
  }
}
