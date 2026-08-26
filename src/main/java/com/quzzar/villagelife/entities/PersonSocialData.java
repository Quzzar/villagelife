package com.quzzar.villagelife.entities;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * Server-side social state for a person, stored as a data attachment: favorite
 * items, per-player relationships, and the five virtues. None of this needs to
 * be synced to clients; screens that want a value get it handed over explicitly.
 */
public record PersonSocialData(Map<String, Double> favoriteItems, Map<UUID, Integer> relationships,
        Map<String, Float> virtues) {

    public static final PersonSocialData EMPTY = new PersonSocialData(Map.of(), Map.of(), Map.of());

    public static final Codec<PersonSocialData> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("favorite_items", Map.of())
                    .forGetter(PersonSocialData::favoriteItems),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT).optionalFieldOf("relationships", Map.of())
                    .forGetter(PersonSocialData::relationships),
            Codec.unboundedMap(Codec.STRING, Codec.FLOAT).optionalFieldOf("virtues", Map.of())
                    .forGetter(PersonSocialData::virtues)
    ).apply(inst, PersonSocialData::new));

    public float getVirtue(Virtue virtue) {
        return virtues.getOrDefault(virtue.name(), 0F);
    }

    public PersonSocialData withVirtue(Virtue virtue, float value) {
        Map<String, Float> updated = new HashMap<>(virtues);
        updated.put(virtue.name(), value);
        return new PersonSocialData(favoriteItems, relationships, Map.copyOf(updated));
    }

    public PersonSocialData withFavoriteItems(Map<String, Double> favoriteItems) {
        return new PersonSocialData(Map.copyOf(favoriteItems), relationships, virtues);
    }

    public PersonSocialData withRelationships(Map<UUID, Integer> relationships) {
        return new PersonSocialData(favoriteItems, Map.copyOf(relationships), virtues);
    }

}
