package com.quzzar.kithkyn.appearance;

import java.util.ArrayList;
import java.util.List;

import com.quzzar.kithkyn.entities.genetics.GeneticCondition;

/** Shared invariant checks used by automated tests and live development audits. */
public final class AppearanceRecipeAudit {

  private static final int MINIMUM_HETEROCHROMIA_DISTANCE_SQUARED = 28 * 28;

  private AppearanceRecipeAudit() {
  }

  /** Returns every contract violation in a generated recipe; an empty list means it is valid. */
  public static List<String> validate(
      AppearanceCatalog catalog,
      AppearanceInputs inputs,
      SkinRecipe recipe) {
    List<String> failures = new ArrayList<>();
    AppearanceAsset skin = catalog.asset(recipe.skin());
    AppearanceAsset clothing = catalog.asset(recipe.clothing());
    AppearanceAsset leftEye = catalog.asset(recipe.leftEye());
    AppearanceAsset rightEye = catalog.asset(recipe.rightEye());
    AppearanceAsset hair = catalog.asset(recipe.hair());

    check(failures, skin.has(AppearancePart.SKIN), "skin asset has no skin layer");
    check(failures, clothing.has(AppearancePart.CLOTHING), "clothing asset has no clothing layer");
    check(failures, leftEye.has(AppearancePart.EYE_LEFT), "left-eye asset has no left eye");
    check(failures, rightEye.has(AppearancePart.EYE_RIGHT), "right-eye asset has no right eye");
    check(failures, hair.has(AppearancePart.HAIR), "hair asset has no hair layer");
    check(failures, recipe.model() == skin.model(), "skin geometry does not match recipe geometry");
    check(failures, skin.faceProfile().equals(hair.faceProfile()), "hair face profile does not match skin");
    check(failures, skin.faceProfile().equals(leftEye.faceProfile()), "left-eye profile does not match skin");
    check(failures, skin.faceProfile().equals(rightEye.faceProfile()), "right-eye profile does not match skin");
    check(failures, skin.genderOf(AppearancePart.SKIN).fits(recipe.expression()), "skin breaks gender rule");
    check(failures, clothing.genderOf(AppearancePart.CLOTHING).fits(recipe.expression()),
        "clothing breaks gender rule");
    check(failures, hair.genderOf(AppearancePart.HAIR).fits(recipe.expression()), "hair breaks gender rule");
    check(failures, leftEye.genderOf(AppearancePart.EYE_LEFT).fits(recipe.expression()),
        "left eye breaks gender rule");
    check(failures, rightEye.genderOf(AppearancePart.EYE_RIGHT).fits(recipe.expression()),
        "right eye breaks gender rule");
    check(failures, leftEye.eyeFitsHair(AppearancePart.EYE_LEFT, hair), "hair occludes the left eye");
    check(failures, rightEye.eyeFitsHair(AppearancePart.EYE_RIGHT, hair), "hair occludes the right eye");
    check(failures, clothing.lifeStages().contains(inputs.lifeStage()), "clothing breaks life-stage rule");
    if (inputs.lifeStage() == LifeStage.ADULT) {
      check(failures, clothing.occupations().contains(inputs.occupation()), "clothing breaks occupation rule");
    }
    check(failures, recipe.headwearOccludesHair() == clothing.headwearOccludesHair(),
        "headwear occlusion flag does not match clothing");

    boolean heterochromia = inputs.condition() == GeneticCondition.HETEROCHROMIA;
    if (heterochromia) {
      check(failures, !recipe.leftEye().equals(recipe.rightEye()),
          "heterochromia did not select a second eye source");
      check(failures,
          leftEye.eyeGeometryKey(AppearancePart.EYE_LEFT)
              .equals(rightEye.eyeGeometryKey(AppearancePart.EYE_RIGHT)),
          "heterochromia eye geometry differs");
      check(failures,
          distanceSquared(recipe.leftEyePigment().baseRgb(), recipe.rightEyePigment().baseRgb())
              >= MINIMUM_HETEROCHROMIA_DISTANCE_SQUARED,
          "heterochromia colors are not visibly distinct");
    } else {
      check(failures, recipe.leftEye().equals(recipe.rightEye()), "ordinary eyes use different sources");
      check(failures, recipe.leftEyePigment().equals(recipe.rightEyePigment()),
          "ordinary eyes use different pigments");
    }

    checkPigment(failures, "skin", recipe.skinPigment());
    checkPigment(failures, "hair", recipe.hairPigment());
    checkPigment(failures, "left eye", recipe.leftEyePigment());
    checkPigment(failures, "right eye", recipe.rightEyePigment());
    return List.copyOf(failures);
  }

  private static void checkPigment(List<String> failures, String label, PigmentColor pigment) {
    check(failures, pigment.baseRgb() != pigment.shadowRgb(), label + " base and shadow colors are identical");
  }

  private static void check(List<String> failures, boolean valid, String failure) {
    if (!valid) {
      failures.add(failure);
    }
  }

  private static int distanceSquared(int first, int second) {
    int red = (first >>> 16 & 0xFF) - (second >>> 16 & 0xFF);
    int green = (first >>> 8 & 0xFF) - (second >>> 8 & 0xFF);
    int blue = (first & 0xFF) - (second & 0xFF);
    return red * red + green * green + blue * blue;
  }
}
