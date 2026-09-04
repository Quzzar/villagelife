package com.quzzar.kithkyn.appearance;

import com.quzzar.kithkyn.entities.Gender;
import com.quzzar.kithkyn.entities.genetics.AppearanceGenes;
import com.quzzar.kithkyn.entities.genetics.GeneticCondition;
import com.quzzar.kithkyn.village.Occupation;

/** Stable entity facts from which the client derives one complete skin recipe. */
public record AppearanceInputs(
    int seed,
    AppearanceGenes genes,
    Gender gender,
    Occupation occupation,
    LifeStage lifeStage,
    GeneticCondition condition) {
}
