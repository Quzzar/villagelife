package com.quzzar.villagelife.appearance;

import com.quzzar.villagelife.entities.Gender;
import com.quzzar.villagelife.entities.genetics.AppearanceGenes;
import com.quzzar.villagelife.entities.genetics.GeneticCondition;
import com.quzzar.villagelife.village.Occupation;

/** Stable entity facts from which the client derives one complete skin recipe. */
public record AppearanceInputs(
    int seed,
    AppearanceGenes genes,
    Gender gender,
    Occupation occupation,
    LifeStage lifeStage,
    GeneticCondition condition) {
}
