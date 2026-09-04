package com.quzzar.kithkyn.appearance;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.quzzar.kithkyn.village.Occupation;

/** Metadata for one source pack whose semantic layers can be selected independently. */
public record AppearanceAsset(
    String id,
    String label,
    BodyModel model,
    Set<AppearancePart> availableParts,
    Map<AppearancePart, PartGender> genders,
    String faceProfile,
    Set<Integer> frontHairOcclusion,
    List<Texel> leftEyeTexels,
    List<Texel> rightEyeTexels,
    Map<AppearancePart, Set<Integer>> pigmentColors,
    Set<Occupation> occupations,
    Set<LifeStage> lifeStages,
    boolean headwearOccludesHair) {

  public AppearanceAsset {
    availableParts = Set.copyOf(availableParts);
    genders = Map.copyOf(genders);
    frontHairOcclusion = Set.copyOf(frontHairOcclusion);
    leftEyeTexels = List.copyOf(leftEyeTexels);
    rightEyeTexels = List.copyOf(rightEyeTexels);
    pigmentColors = pigmentColors.entrySet().stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(
            Map.Entry::getKey,
            entry -> Set.copyOf(entry.getValue())));
    occupations = Set.copyOf(occupations);
    lifeStages = Set.copyOf(lifeStages);
  }

  public boolean has(AppearancePart part) {
    return availableParts.contains(part);
  }

  public PartGender genderOf(AppearancePart part) {
    PartGender gender = genders.get(part);
    if (gender == null) {
      throw new IllegalStateException(id + " has no gender category for " + part);
    }
    return gender;
  }

  public boolean eyeFitsHair(AppearancePart eye, AppearanceAsset hair) {
    List<Texel> texels = switch (eye) {
      case EYE_LEFT -> leftEyeTexels;
      case EYE_RIGHT -> rightEyeTexels;
      default -> throw new IllegalArgumentException("Not an eye part: " + eye);
    };
    return texels.stream().noneMatch(texel -> hair.frontHairOcclusion.contains(texel.packedIndex()));
  }

  public Set<Integer> pigmentColors(AppearancePart part) {
    Set<Integer> colors = pigmentColors.get(part);
    if (colors == null) {
      throw new IllegalStateException(id + " has no pigment palette for " + part);
    }
    return colors;
  }

  /**
   * Translation-independent eye shape. Left and right masks from different
   * sources may mix only when this key matches.
   */
  public String eyeGeometryKey(AppearancePart eye) {
    List<Texel> texels = switch (eye) {
      case EYE_LEFT -> leftEyeTexels;
      case EYE_RIGHT -> rightEyeTexels;
      default -> throw new IllegalArgumentException("Not an eye part: " + eye);
    };
    if (texels.isEmpty()) {
      return "";
    }
    int minimumX = texels.stream().mapToInt(Texel::x).min().orElseThrow();
    int minimumY = texels.stream().mapToInt(Texel::y).min().orElseThrow();
    return texels.stream()
        .map(texel -> (texel.x() - minimumX) + ":" + (texel.y() - minimumY))
        .sorted()
        .reduce((left, right) -> left + "," + right)
        .orElse("");
  }

  public String texturePath(AppearancePart part) {
    String fileName = switch (part) {
      case SKIN -> "skin.png";
      case CLOTHING -> "clothing.png";
      case HAIR -> "hair.png";
      case EYE_LEFT -> "eyes-left.png";
      case EYE_RIGHT -> "eyes-right.png";
    };
    return "textures/entity/person/parts/" + id + "/" + fileName;
  }
}
