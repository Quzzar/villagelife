package com.quzzar.villagelife.relationships;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.util.Mth;

/**
 * One pair's mutual opinion (persona map issue #5): a shared base value in
 * -100..100 plus a small per-person lean, so two villagers' views of each
 * other are roughly aligned BY SHAPE - one generation produces the whole
 * pair. The pair is stored once under a canonical unordered key; only
 * non-neutral pairs are persisted at all (absence means neutral).
 */
public record RelationshipPair(UUID personA, UUID personB, int value, int leanA, int leanB,
        boolean asymmetric, String flavor) {

    /** Leans stay small unless the model deliberately marks the pair asymmetric. */
    public static final int LEAN_LIMIT = 15;
    public static final int ASYMMETRIC_LEAN_LIMIT = 40;
    /** Pairs with |value| below this are considered neutral and never stored. */
    public static final int NEUTRAL_BAND = 10;

    public static final Codec<RelationshipPair> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("a").forGetter(RelationshipPair::personA),
            UUIDUtil.CODEC.fieldOf("b").forGetter(RelationshipPair::personB),
            Codec.INT.fieldOf("value").forGetter(RelationshipPair::value),
            Codec.INT.optionalFieldOf("lean_a", 0).forGetter(RelationshipPair::leanA),
            Codec.INT.optionalFieldOf("lean_b", 0).forGetter(RelationshipPair::leanB),
            Codec.BOOL.optionalFieldOf("asymmetric", false).forGetter(RelationshipPair::asymmetric),
            Codec.STRING.optionalFieldOf("flavor", "").forGetter(RelationshipPair::flavor)
    ).apply(inst, RelationshipPair::new));

    /** Canonical storage key for an unordered pair. */
    public static String keyOf(UUID first, UUID second) {
        return first.compareTo(second) <= 0 ? first + ":" + second : second + ":" + first;
    }

    public String key() {
        return keyOf(personA, personB);
    }

    public boolean involves(UUID person) {
        return personA.equals(person) || personB.equals(person);
    }

    public UUID other(UUID person) {
        return personA.equals(person) ? personB : personA;
    }

    /** The effective opinion this person holds about the other: value + their lean. */
    public int opinionOf(UUID person) {
        int lean = personA.equals(person) ? leanA : leanB;
        return Mth.clamp(value + lean, -100, 100);
    }

    /** Builds a pair with leans clamped per the asymmetry rule and canonical ordering. */
    public static RelationshipPair create(UUID a, UUID b, int value, int leanA, int leanB,
            boolean asymmetric, String flavor) {
        int limit = asymmetric ? ASYMMETRIC_LEAN_LIMIT : LEAN_LIMIT;
        if (a.compareTo(b) > 0) {
            UUID swapId = a; a = b; b = swapId;
            int swapLean = leanA; leanA = leanB; leanB = swapLean;
        }
        return new RelationshipPair(a, b, Mth.clamp(value, -100, 100),
                Mth.clamp(leanA, -limit, limit), Mth.clamp(leanB, -limit, limit), asymmetric, flavor);
    }
}
