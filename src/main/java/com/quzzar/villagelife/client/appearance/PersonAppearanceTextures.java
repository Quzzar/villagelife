package com.quzzar.villagelife.client.appearance;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.blaze3d.platform.NativeImage;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.appearance.AppearanceAsset;
import com.quzzar.villagelife.appearance.AppearanceCatalog;
import com.quzzar.villagelife.appearance.AppearanceInputs;
import com.quzzar.villagelife.appearance.AppearancePart;
import com.quzzar.villagelife.appearance.AppearanceRecipeFactory;
import com.quzzar.villagelife.appearance.BodyModel;
import com.quzzar.villagelife.appearance.LifeStage;
import com.quzzar.villagelife.appearance.PigmentColor;
import com.quzzar.villagelife.appearance.SkinRecipe;
import com.quzzar.villagelife.entities.AgeStage;
import com.quzzar.villagelife.entities.Gender;
import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Occupation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/** Loads semantic layers, bakes exact 64x64 textures, and owns their bounded GPU cache. */
public final class PersonAppearanceTextures implements ResourceManagerReloadListener {

  public static final PersonAppearanceTextures INSTANCE = new PersonAppearanceTextures();

  private static final int TEXTURE_SIZE = 64;
  private static final int MAXIMUM_CACHED_TEXTURES = 128;
  private static final ResourceLocation CATALOG = ResourceLocation.fromNamespaceAndPath(
      Villagelife.MODID, "appearance/catalog.json");

  private final LinkedHashMap<SkinRecipe, ResourceLocation> textures = new LinkedHashMap<>(32, 0.75F, true);
  private AppearanceCatalog catalog;
  private boolean loggedFailure;

  private PersonAppearanceTextures() {
  }

  public ResourceLocation textureFor(Person person) {
    try {
      SkinRecipe recipe = recipeFor(person);
      ResourceLocation cached = textures.get(recipe);
      if (cached != null) {
        return cached;
      }
      ResourceLocation baked = bake(recipe);
      textures.put(recipe, baked);
      evictOldestTextures();
      return baked;
    } catch (IOException | RuntimeException exception) {
      if (!loggedFailure) {
        Villagelife.LOGGER.error("Could not compose person appearance; using the default player texture", exception);
        loggedFailure = true;
      }
      return DefaultPlayerSkin.getDefaultTexture();
    }
  }

  public BodyModel modelFor(Person person) {
    return AppearanceRecipeFactory.modelFor(inputs(person));
  }

  public SkinRecipe recipeFor(Person person) throws IOException {
    return AppearanceRecipeFactory.create(catalog(), inputs(person));
  }

  private AppearanceInputs inputs(Person person) {
    Gender gender = person instanceof RealPerson realPerson ? realPerson.getGender() : Gender.NONBINARY;
    Occupation occupation = person instanceof RealPerson realPerson
        ? realPerson.getOccupation()
        : Occupation.WANDERER;
    if (person instanceof RealPerson realPerson && realPerson.isWanderingMerchant()) {
      occupation = Occupation.WANDERING_MERCHANT;
    }
    return new AppearanceInputs(
        person.getAppearanceSeed(),
        person.getAppearanceGenes(),
        gender,
        occupation,
        wardrobeStage(person, occupation),
        person.getGeneticCondition());
  }

  private static LifeStage wardrobeStage(Person person, Occupation occupation) {
    AgeStage stage = person.getLifeStage();
    boolean childWardrobe = stage.usesChildWardrobe(!occupation.isIdle());
    return childWardrobe ? LifeStage.CHILD : LifeStage.ADULT;
  }

  private AppearanceCatalog catalog() throws IOException {
    if (catalog == null) {
      ResourceManager resources = Minecraft.getInstance().getResourceManager();
      Resource resource = resources.getResource(CATALOG)
          .orElseThrow(() -> new IOException("Missing appearance catalogue " + CATALOG));
      try (Reader reader = resource.openAsReader()) {
        catalog = AppearanceCatalog.load(reader);
      }
    }
    return catalog;
  }

  private ResourceLocation bake(SkinRecipe recipe) throws IOException {
    ResourceManager resources = Minecraft.getInstance().getResourceManager();
    AppearanceCatalog activeCatalog = catalog();
    AppearanceAsset skinAsset = activeCatalog.asset(recipe.skin());
    AppearanceAsset clothingAsset = activeCatalog.asset(recipe.clothing());
    AppearanceAsset leftEyeAsset = activeCatalog.asset(recipe.leftEye());
    AppearanceAsset rightEyeAsset = activeCatalog.asset(recipe.rightEye());
    AppearanceAsset hairAsset = activeCatalog.asset(recipe.hair());

    NativeImage output = new NativeImage(TEXTURE_SIZE, TEXTURE_SIZE, true);
    try (NativeImage skin = loadLayer(resources, skinAsset, AppearancePart.SKIN);
        NativeImage clothing = loadLayer(resources, clothingAsset, AppearancePart.CLOTHING);
        NativeImage leftEye = loadLayer(resources, leftEyeAsset, AppearancePart.EYE_LEFT);
        NativeImage rightEye = loadLayer(resources, rightEyeAsset, AppearancePart.EYE_RIGHT);
        NativeImage hair = loadLayer(resources, hairAsset, AppearancePart.HAIR)) {
      copyOpaque(output, skin, skinAsset.pigmentColors(AppearancePart.SKIN), recipe.skinPigment(), null, false);
      copyOpaque(output, clothing, Set.of(), null, null, false);
      copyOpaque(output, leftEye, leftEyeAsset.pigmentColors(AppearancePart.EYE_LEFT),
          recipe.leftEyePigment(), null, false);
      copyOpaque(output, rightEye, rightEyeAsset.pigmentColors(AppearancePart.EYE_RIGHT),
          recipe.rightEyePigment(), null, false);
      copyOpaque(output, hair, hairAsset.pigmentColors(AppearancePart.HAIR), recipe.hairPigment(),
          clothing, recipe.headwearOccludesHair());
    } catch (IOException | RuntimeException exception) {
      output.close();
      throw exception;
    }

    DynamicTexture texture = new DynamicTexture(output);
    texture.setFilter(false, false);
    ResourceLocation location = dynamicLocation(recipe);
    Minecraft.getInstance().getTextureManager().register(location, texture);
    return location;
  }

  private NativeImage loadLayer(ResourceManager resources, AppearanceAsset asset, AppearancePart part)
      throws IOException {
    ResourceLocation location = ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, asset.texturePath(part));
    Resource resource = resources.getResource(location)
        .orElseThrow(() -> new IOException("Missing " + part + " layer " + location));
    NativeImage image = NativeImage.read(resource.open());
    if (image.getWidth() != TEXTURE_SIZE || image.getHeight() != TEXTURE_SIZE) {
      image.close();
      throw new IOException(location + " must be exactly 64x64");
    }
    return image;
  }

  private void copyOpaque(NativeImage destination, NativeImage source, Set<Integer> sourcePigments,
      PigmentColor targetPigment, NativeImage headwear, boolean occludeHair) {
    int pigmentMidpoint = pigmentMidpoint(sourcePigments);
    for (int y = 0; y < TEXTURE_SIZE; y++) {
      for (int x = 0; x < TEXTURE_SIZE; x++) {
        int pixel = source.getPixelRGBA(x, y);
        if (!isOpaque(pixel)) {
          continue;
        }
        if (occludeHair && isHeadUv(x, y) && headwear != null
            && isOpaque(headwear.getPixelRGBA(x, y))) {
          continue;
        }
        int sourceRgb = rgbFromAbgr(pixel);
        if (targetPigment != null && sourcePigments.contains(sourceRgb)) {
          int targetRgb = sourcePigments.size() == 1 || luminance(sourceRgb) >= pigmentMidpoint
              ? targetPigment.baseRgb()
              : targetPigment.shadowRgb();
          pixel = abgrWithRgb(pixel, targetRgb);
        }
        destination.setPixelRGBA(x, y, pixel);
      }
    }
  }

  private static int pigmentMidpoint(Set<Integer> colors) {
    if (colors.isEmpty()) {
      return 0;
    }
    return (int) Math.round(colors.stream().mapToInt(PersonAppearanceTextures::luminance).average().orElse(0.0D));
  }

  private static int luminance(int rgb) {
    int red = rgb >>> 16 & 0xFF;
    int green = rgb >>> 8 & 0xFF;
    int blue = rgb & 0xFF;
    return (red * 54 + green * 183 + blue * 19) >>> 8;
  }

  private static int rgbFromAbgr(int abgr) {
    int red = abgr & 0xFF;
    int green = abgr >>> 8 & 0xFF;
    int blue = abgr >>> 16 & 0xFF;
    return red << 16 | green << 8 | blue;
  }

  private static int abgrWithRgb(int abgr, int rgb) {
    int red = rgb >>> 16 & 0xFF;
    int green = rgb >>> 8 & 0xFF;
    int blue = rgb & 0xFF;
    return abgr & 0xFF000000 | blue << 16 | green << 8 | red;
  }

  private static boolean isOpaque(int abgr) {
    return (abgr >>> 24) != 0;
  }

  private static boolean isHeadUv(int x, int y) {
    if (y < 8) {
      return (x >= 8 && x < 24) || (x >= 40 && x < 56);
    }
    return y < 16;
  }

  private ResourceLocation dynamicLocation(SkinRecipe recipe) {
    UUID fingerprint = UUID.nameUUIDFromBytes(recipe.toString().getBytes(StandardCharsets.UTF_8));
    return ResourceLocation.fromNamespaceAndPath(
        Villagelife.MODID,
        "dynamic/person/" + fingerprint.toString().replace("-", ""));
  }

  private void evictOldestTextures() {
    Iterator<Map.Entry<SkinRecipe, ResourceLocation>> iterator = textures.entrySet().iterator();
    while (textures.size() > MAXIMUM_CACHED_TEXTURES && iterator.hasNext()) {
      ResourceLocation location = iterator.next().getValue();
      iterator.remove();
      Minecraft.getInstance().getTextureManager().release(location);
    }
  }

  @Override
  public void onResourceManagerReload(ResourceManager resourceManager) {
    for (ResourceLocation location : textures.values()) {
      Minecraft.getInstance().getTextureManager().release(location);
    }
    textures.clear();
    catalog = null;
    loggedFailure = false;
  }
}
