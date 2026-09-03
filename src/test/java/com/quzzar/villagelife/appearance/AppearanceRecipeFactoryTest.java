package com.quzzar.villagelife.appearance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.quzzar.villagelife.entities.Gender;
import com.quzzar.villagelife.entities.genetics.AppearanceGenes;
import com.quzzar.villagelife.entities.genetics.GeneticCondition;
import com.quzzar.villagelife.village.Occupation;

class AppearanceRecipeFactoryTest {

  private static AppearanceCatalog catalog;

  @BeforeAll
  static void loadCatalog() throws IOException {
    try (InputStream stream = resource("assets/villagelife/appearance/catalog.json");
        InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      catalog = AppearanceCatalog.load(reader);
    }
  }

  @Test
  void everyAdultGenderAndOccupationBuildsValidDeterministicRecipes() {
    for (Gender gender : Gender.values()) {
      for (Occupation occupation : Occupation.values()) {
        for (GeneticCondition condition : GeneticCondition.values()) {
          if (condition == GeneticCondition.HETEROCHROMIA) {
            continue;
          }
          for (int seed = 0; seed < 48; seed++) {
            AppearanceInputs inputs = inputs(seed, gender, occupation, LifeStage.ADULT, condition);
            SkinRecipe first = assertDoesNotThrow(
                () -> AppearanceRecipeFactory.create(catalog, inputs),
                gender + " " + occupation + " " + condition + " seed=" + seed);
            SkinRecipe second = AppearanceRecipeFactory.create(catalog, inputs);
            assertEquals(first, second);
            assertValid(first, inputs);
            assertEquals(first.leftEye(), first.rightEye());
            assertEquals(first.leftEyePigment(), first.rightEyePigment());
            if (occupation == Occupation.WANDERING_MERCHANT) {
              assertEquals("trader-guy-3-clothing", first.clothing());
              assertTrue(first.headwearOccludesHair());
            }
          }
        }
      }
    }
  }

  @Test
  void heterochromiaKeepsEyeGeometryAndChangesTheRightEyeSource() {
    for (Gender gender : Gender.values()) {
      for (int seed = 0; seed < 128; seed++) {
        AppearanceInputs inputs = inputs(seed, gender, Occupation.WANDERER, LifeStage.ADULT,
            GeneticCondition.HETEROCHROMIA);
        SkinRecipe recipe = AppearanceRecipeFactory.create(catalog, inputs);
        assertValid(recipe, inputs);
        assertNotEquals(recipe.leftEye(), recipe.rightEye());
        assertTrue(colorDistance(recipe.leftEyePigment().baseRgb(), recipe.rightEyePigment().baseRgb()) >= 28.0D);
        AppearanceAsset left = catalog.asset(recipe.leftEye());
        AppearanceAsset right = catalog.asset(recipe.rightEye());
        assertEquals(left.faceProfile(), right.faceProfile());
        assertEquals(
            left.eyeGeometryKey(AppearancePart.EYE_LEFT),
            right.eyeGeometryKey(AppearancePart.EYE_RIGHT));
      }
    }
  }

  @Test
  void occupationChangesOnlyTheWardrobe() {
    for (Gender gender : Gender.values()) {
      AppearanceInputs baselineInputs = inputs(7729, gender, Occupation.WANDERER, LifeStage.ADULT,
          GeneticCondition.NONE);
      SkinRecipe baseline = AppearanceRecipeFactory.create(catalog, baselineInputs);
      for (Occupation occupation : Occupation.values()) {
        SkinRecipe changed = AppearanceRecipeFactory.create(
            catalog,
            inputs(7729, gender, occupation, LifeStage.ADULT, GeneticCondition.NONE));
        assertEquals(baseline.model(), changed.model());
        assertEquals(baseline.expression(), changed.expression());
        assertEquals(baseline.skin(), changed.skin());
        assertEquals(baseline.hair(), changed.hair());
        assertEquals(baseline.leftEye(), changed.leftEye());
        assertEquals(baseline.rightEye(), changed.rightEye());
        assertEquals(baseline.skinPigment(), changed.skinPigment());
        assertEquals(baseline.hairPigment(), changed.hairPigment());
        assertEquals(baseline.leftEyePigment(), changed.leftEyePigment());
        assertEquals(baseline.rightEyePigment(), changed.rightEyePigment());
      }
    }
  }

  @Test
  void sharedAuditReportsARecipeContractViolation() {
    AppearanceInputs inputs = inputs(
        7729,
        Gender.NONBINARY,
        Occupation.WANDERER,
        LifeStage.ADULT,
        GeneticCondition.NONE);
    SkinRecipe valid = AppearanceRecipeFactory.create(catalog, inputs);
    SkinRecipe invalid = new SkinRecipe(
        valid.model(),
        valid.expression(),
        valid.skin(),
        valid.clothing(),
        valid.leftEye(),
        valid.rightEye(),
        valid.hair(),
        valid.skinPigment(),
        valid.hairPigment(),
        valid.leftEyePigment(),
        valid.rightEyePigment(),
        !valid.headwearOccludesHair());

    assertTrue(AppearanceRecipeAudit.validate(catalog, inputs, invalid).stream()
        .anyMatch(failure -> failure.contains("headwear occlusion")));
  }

  @Test
  void childRecipesUseOnlyChildWardrobes() {
    for (Gender gender : Gender.values()) {
      for (int seed = 0; seed < 96; seed++) {
        AppearanceInputs inputs = inputs(seed, gender, Occupation.GUARD, LifeStage.CHILD,
            GeneticCondition.NONE);
        SkinRecipe recipe = AppearanceRecipeFactory.create(catalog, inputs);
        assertValid(recipe, inputs);
        AppearanceAsset clothing = catalog.asset(recipe.clothing());
        assertTrue(clothing.lifeStages().contains(LifeStage.CHILD));
        assertFalse(clothing.lifeStages().contains(LifeStage.ADULT));
      }
    }
  }

  @Test
  void everyShippedLayerIsAnExactHardEdged64PixelTexture() throws IOException {
    assertEquals(65, catalog.assets().size());
    for (AppearanceAsset asset : catalog.assets()) {
      for (AppearancePart part : AppearancePart.values()) {
        if (!asset.has(part)) {
          continue;
        }
        String path = "assets/villagelife/" + asset.texturePath(part);
        try (InputStream stream = resource(path)) {
          BufferedImage image = ImageIO.read(stream);
          assertNotNull(image, path);
          assertEquals(64, image.getWidth(), path);
          assertEquals(64, image.getHeight(), path);
          Set<Integer> opaqueColors = new HashSet<>();
          for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
              int argb = image.getRGB(x, y);
              int alpha = argb >>> 24;
              assertTrue(alpha == 0 || alpha == 255, path + " has partial alpha at " + x + "," + y);
              if (alpha == 255) {
                opaqueColors.add(argb & 0xFFFFFF);
              }
            }
          }
          if (part != AppearancePart.CLOTHING) {
            assertFalse(asset.pigmentColors(part).isEmpty(), path);
            assertTrue(opaqueColors.containsAll(asset.pigmentColors(part)), path);
          }
        }
      }
    }
  }

  private static AppearanceInputs inputs(int seed, Gender gender, Occupation occupation,
      LifeStage lifeStage, GeneticCondition condition) {
    return new AppearanceInputs(
        seed,
        AppearanceGenes.fromLegacySeed(seed * 31 + 17),
        gender,
        occupation,
        lifeStage,
        condition);
  }

  private static void assertValid(SkinRecipe recipe, AppearanceInputs inputs) {
    List<String> failures = AppearanceRecipeAudit.validate(catalog, inputs, recipe);
    assertTrue(failures.isEmpty(), () -> String.join("; ", failures));
  }

  private static InputStream resource(String path) {
    InputStream stream = AppearanceRecipeFactoryTest.class.getClassLoader().getResourceAsStream(path);
    assertNotNull(stream, path);
    return stream;
  }

  private static double colorDistance(int first, int second) {
    int red = (first >>> 16 & 0xFF) - (second >>> 16 & 0xFF);
    int green = (first >>> 8 & 0xFF) - (second >>> 8 & 0xFF);
    int blue = (first & 0xFF) - (second & 0xFF);
    return Math.sqrt(red * red + green * green + blue * blue);
  }
}
