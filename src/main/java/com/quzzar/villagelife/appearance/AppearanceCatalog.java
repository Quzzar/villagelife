package com.quzzar.villagelife.appearance;

import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quzzar.villagelife.entities.Gender;
import com.quzzar.villagelife.village.Occupation;

/** Validated, data-driven catalogue of every semantic person-texture layer. */
public final class AppearanceCatalog {

  private final List<AppearanceAsset> assets;
  private final Map<String, AppearanceAsset> byId;

  private AppearanceCatalog(List<AppearanceAsset> assets) {
    this.assets = List.copyOf(assets);
    Map<String, AppearanceAsset> indexed = new HashMap<>();
    for (AppearanceAsset asset : assets) {
      if (indexed.put(asset.id(), asset) != null) {
        throw new IllegalArgumentException("Duplicate appearance asset id: " + asset.id());
      }
      validateAsset(asset);
    }
    this.byId = Map.copyOf(indexed);
    validateWardrobes();
  }

  public static AppearanceCatalog load(Reader reader) {
    JsonArray root = JsonParser.parseReader(reader).getAsJsonArray();
    List<AppearanceAsset> assets = new ArrayList<>();
    for (JsonElement element : root) {
      assets.add(parseAsset(element.getAsJsonObject()));
    }
    return new AppearanceCatalog(assets);
  }

  public List<AppearanceAsset> assets() {
    return assets;
  }

  public AppearanceAsset asset(String id) {
    AppearanceAsset asset = byId.get(id);
    if (asset == null) {
      throw new IllegalArgumentException("Unknown appearance asset: " + id);
    }
    return asset;
  }

  public List<AppearanceAsset> withPart(AppearancePart part) {
    return assets.stream().filter(asset -> asset.has(part)).toList();
  }

  private static AppearanceAsset parseAsset(JsonObject object) {
    String id = requiredString(object, "slug");
    Set<String> disabled = stringSet(object, "disabledParts");
    JsonObject files = object.getAsJsonObject("files");
    Set<AppearancePart> available = new HashSet<>();
    addPart(available, disabled, files, AppearancePart.SKIN, "skin", "skin.png");
    addPart(available, disabled, files, AppearancePart.CLOTHING, "clothing", "clothing.png");
    addPart(available, disabled, files, AppearancePart.HAIR, "hair", "hair.png");
    addPart(available, disabled, files, AppearancePart.EYE_LEFT, "eyeLeft", "eyes-left.png");
    addPart(available, disabled, files, AppearancePart.EYE_RIGHT, "eyeRight", "eyes-right.png");

    EnumMap<AppearancePart, PartGender> genders = new EnumMap<>(AppearancePart.class);
    JsonObject genderObject = object.getAsJsonObject("genderCategories");
    addGender(genders, available, genderObject, AppearancePart.SKIN, "skin");
    addGender(genders, available, genderObject, AppearancePart.CLOTHING, "clothing");
    addGender(genders, available, genderObject, AppearancePart.HAIR, "hair");
    addGender(genders, available, genderObject, AppearancePart.EYE_LEFT, "eyeLeft");
    addGender(genders, available, genderObject, AppearancePart.EYE_RIGHT, "eyeRight");

    JsonObject clothingProfile = object.has("clothingProfile")
        ? object.getAsJsonObject("clothingProfile")
        : new JsonObject();
    Set<Occupation> occupations = enumSet(clothingProfile, "occupations", Occupation.class);
    Set<LifeStage> lifeStages = new HashSet<>();
    if (clothingProfile.has("lifeStages")) {
      for (JsonElement stage : clothingProfile.getAsJsonArray("lifeStages")) {
        lifeStages.add(LifeStage.parse(stage.getAsString()));
      }
    }

    List<Texel> leftEyes = List.of();
    List<Texel> rightEyes = List.of();
    if (object.has("eyes")) {
      JsonObject eyes = object.getAsJsonObject("eyes");
      leftEyes = texels(eyes.getAsJsonArray("left"));
      rightEyes = texels(eyes.getAsJsonArray("right"));
    }

    Set<Integer> hairOcclusion = new HashSet<>();
    if (object.has("frontHairOcclusion")) {
      for (JsonElement packed : object.getAsJsonArray("frontHairOcclusion")) {
        hairOcclusion.add(packed.getAsInt());
      }
    }

    EnumMap<AppearancePart, Set<Integer>> pigmentColors = new EnumMap<>(AppearancePart.class);
    JsonObject pigmentObject = object.has("pigmentColors")
        ? object.getAsJsonObject("pigmentColors")
        : new JsonObject();
    addPigmentColors(pigmentColors, available, pigmentObject, AppearancePart.SKIN, "skin");
    addPigmentColors(pigmentColors, available, pigmentObject, AppearancePart.HAIR, "hair");
    addPigmentColors(pigmentColors, available, pigmentObject, AppearancePart.EYE_LEFT, "eyeLeft");
    addPigmentColors(pigmentColors, available, pigmentObject, AppearancePart.EYE_RIGHT, "eyeRight");

    return new AppearanceAsset(
        id,
        requiredString(object, "label"),
        BodyModel.parse(requiredString(object, "model")),
        available,
        genders,
        object.has("faceProfile") ? object.get("faceProfile").getAsString() : "",
        hairOcclusion,
        leftEyes,
        rightEyes,
        pigmentColors,
        occupations,
        lifeStages,
        object.has("headwearOccludesHair") && object.get("headwearOccludesHair").getAsBoolean());
  }

  private static void addPart(Set<AppearancePart> available, Set<String> disabled, JsonObject files,
      AppearancePart part, String manifestKey, String fileName) {
    if (files != null && files.has(fileName) && !disabled.contains(manifestKey)) {
      available.add(part);
    }
  }

  private static void addGender(EnumMap<AppearancePart, PartGender> genders, Set<AppearancePart> available,
      JsonObject object, AppearancePart part, String key) {
    if (available.contains(part)) {
      if (object == null || !object.has(key)) {
        throw new IllegalArgumentException("Missing gender category for " + key);
      }
      genders.put(part, PartGender.parse(object.get(key).getAsString()));
    }
  }

  private static void addPigmentColors(EnumMap<AppearancePart, Set<Integer>> colors,
      Set<AppearancePart> available, JsonObject object, AppearancePart part, String key) {
    if (!available.contains(part)) {
      return;
    }
    Set<Integer> palette = new HashSet<>();
    if (object.has(key)) {
      for (JsonElement element : object.getAsJsonArray(key)) {
        palette.add(parseRgb(element.getAsString()));
      }
    }
    colors.put(part, palette);
  }

  private static int parseRgb(String value) {
    if (!value.matches("#[0-9a-fA-F]{6}")) {
      throw new IllegalArgumentException("Invalid appearance pigment color: " + value);
    }
    return Integer.parseInt(value.substring(1), 16);
  }

  private static List<Texel> texels(JsonArray array) {
    List<Texel> texels = new ArrayList<>();
    for (JsonElement element : array) {
      JsonArray pair = element.getAsJsonArray();
      texels.add(new Texel(pair.get(0).getAsInt(), pair.get(1).getAsInt()));
    }
    return texels;
  }

  private static Set<String> stringSet(JsonObject object, String key) {
    Set<String> values = new HashSet<>();
    if (object.has(key)) {
      for (JsonElement element : object.getAsJsonArray(key)) {
        values.add(element.getAsString());
      }
    }
    return values;
  }

  private static <E extends Enum<E>> Set<E> enumSet(JsonObject object, String key, Class<E> enumType) {
    Set<E> values = new HashSet<>();
    if (object.has(key)) {
      for (JsonElement element : object.getAsJsonArray(key)) {
        values.add(Enum.valueOf(enumType, element.getAsString()));
      }
    }
    return values;
  }

  private static String requiredString(JsonObject object, String key) {
    if (!object.has(key) || object.get(key).getAsString().isBlank()) {
      throw new IllegalArgumentException("Appearance asset is missing " + key);
    }
    return object.get(key).getAsString();
  }

  private static void validateAsset(AppearanceAsset asset) {
    boolean hasEitherEye = asset.has(AppearancePart.EYE_LEFT) || asset.has(AppearancePart.EYE_RIGHT);
    if (hasEitherEye
        && (!asset.has(AppearancePart.EYE_LEFT) || !asset.has(AppearancePart.EYE_RIGHT))) {
      throw new IllegalArgumentException(asset.id() + " must provide both eye sides");
    }
    if (hasEitherEye) {
      if (asset.faceProfile().isBlank() || asset.leftEyeTexels().isEmpty() || asset.rightEyeTexels().isEmpty()) {
        throw new IllegalArgumentException(asset.id() + " has incomplete eye compatibility metadata");
      }
      if (!asset.eyeGeometryKey(AppearancePart.EYE_LEFT)
          .equals(asset.eyeGeometryKey(AppearancePart.EYE_RIGHT))) {
        throw new IllegalArgumentException(asset.id() + " has mismatched bilateral eye geometry");
      }
    }
    for (AppearancePart part : List.of(
        AppearancePart.SKIN,
        AppearancePart.HAIR,
        AppearancePart.EYE_LEFT,
        AppearancePart.EYE_RIGHT)) {
      if (asset.has(part) && asset.pigmentColors(part).isEmpty()) {
        throw new IllegalArgumentException(asset.id() + " has no pigment colors for " + part);
      }
    }
    if (asset.has(AppearancePart.CLOTHING) && asset.lifeStages().isEmpty()) {
      throw new IllegalArgumentException(asset.id() + " clothing has no life stage");
    }
  }

  private void validateWardrobes() {
    for (Occupation occupation : Occupation.values()) {
      List<AppearanceAsset> wardrobes = assets.stream()
          .filter(asset -> asset.has(AppearancePart.CLOTHING))
          .filter(asset -> asset.lifeStages().contains(LifeStage.ADULT))
          .filter(asset -> asset.occupations().contains(occupation))
          .toList();
      if (occupation == Occupation.WANDERING_MERCHANT) {
        validateWanderingMerchantWardrobe(wardrobes);
        continue;
      }
      validateGenderCoverage(occupation.name(), wardrobes, 3);
    }
    List<AppearanceAsset> childWardrobes = assets.stream()
        .filter(asset -> asset.has(AppearancePart.CLOTHING))
        .filter(asset -> asset.lifeStages().contains(LifeStage.CHILD))
        .toList();
    validateGenderCoverage("CHILD", childWardrobes, 3);
  }

  private static void validateGenderCoverage(String label, List<AppearanceAsset> wardrobes, int required) {
    for (Gender expression : List.of(Gender.MALE, Gender.FEMALE)) {
      long count = wardrobes.stream()
          .filter(asset -> asset.genderOf(AppearancePart.CLOTHING).fits(expression))
          .count();
      if (count < required) {
        throw new IllegalArgumentException(
            label + " has only " + count + " " + expression + "-compatible wardrobes; requires " + required);
      }
    }
  }

  private static void validateWanderingMerchantWardrobe(List<AppearanceAsset> merchantWardrobes) {
    if (merchantWardrobes.size() != 1
        || !merchantWardrobes.getFirst().id().equals("trader-guy-3-clothing")
        || merchantWardrobes.getFirst().genderOf(AppearancePart.CLOTHING) != PartGender.NONBINARY
        || !merchantWardrobes.getFirst().headwearOccludesHair()) {
      throw new IllegalArgumentException(
          "Wandering merchants require the single shared, hooded Trader Guy 3 wardrobe");
    }
  }
}
