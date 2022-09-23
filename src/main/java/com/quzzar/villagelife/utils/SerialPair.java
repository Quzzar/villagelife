package com.quzzar.villagelife.utils;

import com.mojang.datafixers.kinds.App;
import com.mojang.datafixers.util.Pair;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class SerialPair<F, S> implements App<Pair.Mu<S>, F>, Serializable  {

    private final F first;
    private final S second;

    public SerialPair(final F first, final S second) {
        this.first = first;
        this.second = second;
    }

    public F getFirst() {
        return first;
    }

    public S getSecond() {
        return second;
    }

    public SerialPair<S, F> swap() {
        return of(second, first);
    }

    @Override
    public String toString() {
        return "(" + first + ", " + second + ")";
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof SerialPair<?, ?>)) {
            return false;
        }
        final SerialPair<?, ?> other = (SerialPair<?, ?>) obj;
        return Objects.equals(first, other.first) && Objects.equals(second, other.second);
    }

    @Override
    public int hashCode() {
        return com.google.common.base.Objects.hashCode(first, second);
    }

    public <F2> SerialPair<F2, S> mapFirst(final Function<? super F, ? extends F2> function) {
        return of(function.apply(first), second);
    }

    public <S2> SerialPair<F, S2> mapSecond(final Function<? super S, ? extends S2> function) {
        return of(first, function.apply(second));
    }

    public static <F, S> SerialPair<F, S> of(final F first, final S second) {
        return new SerialPair<>(first, second);
    }

    public static <F, S> Collector<SerialPair<F, S>, ?, Map<F, S>> toMap() {
        return Collectors.toMap(SerialPair::getFirst, SerialPair::getSecond);
    }
}