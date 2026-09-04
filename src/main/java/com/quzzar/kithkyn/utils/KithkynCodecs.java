package com.quzzar.kithkyn.utils;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

public final class KithkynCodecs {

    private KithkynCodecs() {
    }

    /** Codec for any enum, serialized by its constant name. */
    public static <E extends Enum<E>> Codec<E> forEnum(Class<E> clazz) {
        return Codec.STRING.comapFlatMap(name -> {
            try {
                return DataResult.success(Enum.valueOf(clazz, name));
            } catch (IllegalArgumentException e) {
                return DataResult.error(() -> "Unknown " + clazz.getSimpleName() + " value: " + name);
            }
        }, Enum::name);
    }
}
