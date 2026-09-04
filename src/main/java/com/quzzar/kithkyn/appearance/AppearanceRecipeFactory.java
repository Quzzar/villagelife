package com.quzzar.kithkyn.appearance;

import java.util.List;

import com.quzzar.kithkyn.entities.Gender;
import com.quzzar.kithkyn.entities.genetics.GeneticCondition;

/** Pure deterministic selection of compatible semantic layers from appearance genes. */
public final class AppearanceRecipeFactory {

  private static final long SKIN_SALT = 0x243F6A8885A308D3L;
  private static final long HAIR_SALT = 0x13198A2E03707344L;
  private static final long EYES_SALT = 0xA4093822299F31D0L;
  private static final long ALTERNATE_EYES_SALT = 0x082EFA98EC4E6C89L;
  private static final long CLOTHING_SALT = 0x452821E638D01377L;

  private AppearanceRecipeFactory() {
  }

  public static SkinRecipe create(AppearanceCatalog catalog, AppearanceInputs inputs) {
    Gender expression = expressionFor(inputs);
    BodyModel model = modelFor(inputs);
    boolean heterochromia = inputs.condition() == GeneticCondition.HETEROCHROMIA;

    List<AppearanceAsset> skins = catalog.withPart(AppearancePart.SKIN).stream()
        .filter(asset -> asset.model() == model)
        .filter(asset -> fits(asset, AppearancePart.SKIN, expression))
        .filter(asset -> canBuildFace(catalog, asset, expression, heterochromia))
        .toList();
    AppearanceAsset skin = pick(skins, inputs.genes().skin(), SKIN_SALT, "skin");

    List<AppearanceAsset> hairs = compatibleHair(catalog, skin, expression).stream()
        .filter(hair -> hasCompatibleEyes(catalog, skin, hair, expression, heterochromia))
        .toList();
    AppearanceAsset hair = pick(hairs, inputs.genes().hair(), HAIR_SALT, "hair");

    List<AppearanceAsset> compatibleEyePairs = compatibleEyes(catalog, skin, hair, expression);
    List<AppearanceAsset> eyePairs = compatibleEyePairs;
    if (heterochromia) {
      eyePairs = compatibleEyePairs.stream()
          .filter(primary -> compatibleEyePairs.stream().anyMatch(secondary -> eyesCanMix(primary, secondary)))
          .toList();
    }
    AppearanceAsset primaryEyes = pick(eyePairs, inputs.genes().eyes(), EYES_SALT, "eyes");
    AppearanceAsset rightEyes = primaryEyes;
    if (heterochromia) {
      List<AppearanceAsset> alternatives = eyePairs.stream()
          .filter(candidate -> eyesCanMix(primaryEyes, candidate))
          .toList();
      rightEyes = pick(alternatives, inputs.genes().alternateEyes(), ALTERNATE_EYES_SALT,
          "heterochromia eye");
    }

    List<AppearanceAsset> clothing = catalog.withPart(AppearancePart.CLOTHING).stream()
        .filter(asset -> asset.lifeStages().contains(inputs.lifeStage()))
        .filter(asset -> inputs.lifeStage() == LifeStage.CHILD
            || asset.occupations().contains(inputs.occupation()))
        .filter(asset -> fits(asset, AppearancePart.CLOTHING, expression))
        .toList();
    int clothingGene = inputs.seed() ^ Integer.rotateLeft(inputs.occupation().ordinal() + 1, 13)
        ^ Integer.rotateLeft(inputs.lifeStage().ordinal() + 1, 24);
    AppearanceAsset garment = pick(clothing, clothingGene, CLOTHING_SALT, "clothing");

    PigmentColor leftEyePigment = PigmentPalette.eyes(inputs.genes().eyePigment());
    PigmentColor rightEyePigment = leftEyePigment;
    if (heterochromia) {
      rightEyePigment = PigmentPalette.ensureDistinctEyes(
          leftEyePigment,
          PigmentPalette.eyes(inputs.genes().alternateEyePigment()));
    }

    return new SkinRecipe(
        model,
        expression,
        skin.id(),
        garment.id(),
        primaryEyes.id(),
        rightEyes.id(),
        hair.id(),
        PigmentPalette.skin(inputs.genes().skinPigment()),
        PigmentPalette.hair(inputs.genes().hairPigment()),
        leftEyePigment,
        rightEyePigment,
        garment.headwearOccludesHair());
  }

  public static Gender expressionFor(AppearanceInputs inputs) {
    if (inputs.gender() != Gender.NONBINARY) {
      return inputs.gender();
    }
    return switch (Math.floorMod(mix32(inputs.seed() ^ 0x9E3779B9), 3)) {
      case 0 -> Gender.NONBINARY;
      case 1 -> Gender.MALE;
      default -> Gender.FEMALE;
    };
  }

  public static BodyModel modelFor(AppearanceInputs inputs) {
    return switch (expressionFor(inputs)) {
      case MALE -> BodyModel.WIDE;
      case FEMALE -> BodyModel.SLIM;
      case NONBINARY -> (inputs.genes().skin() & 1) == 0 ? BodyModel.WIDE : BodyModel.SLIM;
    };
  }

  private static boolean canBuildFace(AppearanceCatalog catalog, AppearanceAsset skin, Gender expression,
      boolean heterochromia) {
    return compatibleHair(catalog, skin, expression).stream()
        .anyMatch(hair -> hasCompatibleEyes(catalog, skin, hair, expression, heterochromia));
  }

  private static List<AppearanceAsset> compatibleHair(AppearanceCatalog catalog, AppearanceAsset skin,
      Gender expression) {
    return catalog.withPart(AppearancePart.HAIR).stream()
        .filter(asset -> asset.faceProfile().equals(skin.faceProfile()))
        .filter(asset -> fits(asset, AppearancePart.HAIR, expression))
        .toList();
  }

  private static boolean hasCompatibleEyes(AppearanceCatalog catalog, AppearanceAsset skin,
      AppearanceAsset hair, Gender expression, boolean heterochromia) {
    List<AppearanceAsset> candidates = compatibleEyes(catalog, skin, hair, expression);
    if (!heterochromia) {
      return !candidates.isEmpty();
    }
    return candidates.stream().anyMatch(primary -> candidates.stream()
        .anyMatch(secondary -> eyesCanMix(primary, secondary)));
  }

  private static List<AppearanceAsset> compatibleEyes(AppearanceCatalog catalog, AppearanceAsset skin,
      AppearanceAsset hair, Gender expression) {
    return catalog.assets().stream()
        .filter(asset -> asset.has(AppearancePart.EYE_LEFT) && asset.has(AppearancePart.EYE_RIGHT))
        .filter(asset -> asset.faceProfile().equals(skin.faceProfile()))
        .filter(asset -> fits(asset, AppearancePart.EYE_LEFT, expression))
        .filter(asset -> fits(asset, AppearancePart.EYE_RIGHT, expression))
        .filter(asset -> asset.eyeFitsHair(AppearancePart.EYE_LEFT, hair))
        .filter(asset -> asset.eyeFitsHair(AppearancePart.EYE_RIGHT, hair))
        .toList();
  }

  private static boolean eyesCanMix(AppearanceAsset primary, AppearanceAsset secondary) {
    return !primary.id().equals(secondary.id())
        && primary.eyeGeometryKey(AppearancePart.EYE_RIGHT)
            .equals(secondary.eyeGeometryKey(AppearancePart.EYE_RIGHT));
  }

  private static boolean fits(AppearanceAsset asset, AppearancePart part, Gender expression) {
    return asset.genderOf(part).fits(expression);
  }

  private static AppearanceAsset pick(List<AppearanceAsset> candidates, int gene, long salt, String kind) {
    if (candidates.isEmpty()) {
      throw new IllegalStateException("No compatible " + kind + " assets");
    }
    AppearanceAsset selected = null;
    long selectedScore = 0L;
    for (AppearanceAsset candidate : candidates) {
      long score = score(candidate.id(), gene, salt);
      if (selected == null || Long.compareUnsigned(score, selectedScore) > 0) {
        selected = candidate;
        selectedScore = score;
      }
    }
    return selected;
  }

  private static long score(String id, int gene, long salt) {
    long value = salt ^ Integer.toUnsignedLong(gene);
    for (int index = 0; index < id.length(); index++) {
      value ^= id.charAt(index);
      value *= 0x100000001B3L;
    }
    return mix64(value);
  }

  private static int mix32(int value) {
    value ^= value >>> 16;
    value *= 0x7FEB352D;
    value ^= value >>> 15;
    value *= 0x846CA68B;
    return value ^ value >>> 16;
  }

  private static long mix64(long value) {
    value ^= value >>> 30;
    value *= 0xBF58476D1CE4E5B9L;
    value ^= value >>> 27;
    value *= 0x94D049BB133111EBL;
    return value ^ value >>> 31;
  }
}
