package com.quzzar.kithkyn.persona;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A villager's AI-generated persona, stored as the {@code kithkyn:persona}
 * data attachment. Under the generate-before-spawn lifecycle (persona map
 * issue #4) a spawned villager always carries a non-empty persona; {@link #EMPTY}
 * exists only as the attachment default for entities that predate the system.
 */
public record PersonaData(String blurb, String quirk, String model, long generationMs, int promptVersion) {

    public static final PersonaData EMPTY = new PersonaData("", "", "", 0L, 0);

    public static final Codec<PersonaData> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.optionalFieldOf("blurb", "").forGetter(PersonaData::blurb),
            Codec.STRING.optionalFieldOf("quirk", "").forGetter(PersonaData::quirk),
            Codec.STRING.optionalFieldOf("model", "").forGetter(PersonaData::model),
            Codec.LONG.optionalFieldOf("generation_ms", 0L).forGetter(PersonaData::generationMs),
            Codec.INT.optionalFieldOf("prompt_version", 0).forGetter(PersonaData::promptVersion)
    ).apply(inst, PersonaData::new));

    public boolean isEmpty() {
        return blurb.isBlank();
    }
}
