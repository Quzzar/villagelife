package com.quzzar.kithkyn.appearance;

import com.quzzar.kithkyn.entities.Gender;

/** Exact semantic layers and body geometry used to bake one player texture. */
public record SkinRecipe(
    BodyModel model,
    Gender expression,
    String skin,
    String clothing,
    String leftEye,
    String rightEye,
    String hair,
    PigmentColor skinPigment,
    PigmentColor hairPigment,
    PigmentColor leftEyePigment,
    PigmentColor rightEyePigment,
    boolean headwearOccludesHair) {
}
