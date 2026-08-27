package com.quzzar.villagelife.entities;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * The matters a villager is seeing through (docs/undertakings.md): objectives
 * with a lifecycle, good or bad, held on the person as
 * {@code villagelife:undertakings}. This is the persistence and the model; the
 * chat tool that creates them and the item-detection that advances them are
 * wired separately, so the record can be built and tested before the LLM seam
 * is agreed.
 *
 * The whole design rests on one division of labour: the model may PROPOSE an
 * op (open, advance, resolve), but the SERVER owns every count. "Bring ten
 * wheat" advances when the game sees wheat arrive, never when the model claims
 * it did, because a small model cannot be trusted to hold a tally.
 */
public record UndertakingData(List<Undertaking> undertakings) {

    /** Open matters kept per villager; resolved ones trim oldest-first past this. */
    public static final int MAX_OPEN = 16;
    public static final int MAX_RESOLVED = 16;

    public static final UndertakingData EMPTY = new UndertakingData(List.of());

    /** How the villager feels about the matter, independent of whether it is done. */
    public enum Valence { POSITIVE, NEGATIVE }

    /** Where a matter is in its life. RESOLVED and ABANDONED are terminal. */
    public enum State { OPEN, ACTIVE, RESOLVED, ABANDONED }

    /** Who raised it: the villager themselves, the world, or the player's doing. */
    public enum Origin { SELF, EVENT, PLAYER }

    /** One ordered progress marker on a milestoned undertaking. */
    public record Milestone(String text, boolean reached) {
        public static final Codec<Milestone> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.STRING.fieldOf("text").forGetter(Milestone::text),
                Codec.BOOL.optionalFieldOf("reached", false).forGetter(Milestone::reached)
        ).apply(inst, Milestone::new));
    }

    /**
     * @param id        stable handle, so an advance or resolve names the right matter
     * @param valence   how the villager feels about it
     * @param state     where it is in its life
     * @param origin    who raised it
     * @param summary   the villager's own one sentence
     * @param withWhom  the player or villager it concerns, if any
     * @param steps     ordered milestones; empty for a stepless matter
     * @param progress  index of the last reached step, or a 0..100 count when stepless
     * @param target    stepless goal amount (10 wheat, 60 emeralds); 0 when milestoned
     * @param resolution one sentence, filled only when it ends
     * @param openedDay  level day-time when it began
     * @param updatedDay level day-time it last moved
     */
    public record Undertaking(
            UUID id,
            Valence valence,
            State state,
            Origin origin,
            String summary,
            Optional<UUID> withWhom,
            List<Milestone> steps,
            int progress,
            int target,
            Optional<String> resolution,
            long openedDay,
            long updatedDay) {

        public static final Codec<Undertaking> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(Undertaking::id),
                Codec.STRING.xmap(Valence::valueOf, Valence::name).fieldOf("valence").forGetter(Undertaking::valence),
                Codec.STRING.xmap(State::valueOf, State::name).fieldOf("state").forGetter(Undertaking::state),
                Codec.STRING.xmap(Origin::valueOf, Origin::name).fieldOf("origin").forGetter(Undertaking::origin),
                Codec.STRING.fieldOf("summary").forGetter(Undertaking::summary),
                UUIDUtil.STRING_CODEC.optionalFieldOf("with").forGetter(Undertaking::withWhom),
                Milestone.CODEC.listOf().optionalFieldOf("steps", List.of()).forGetter(Undertaking::steps),
                Codec.INT.optionalFieldOf("progress", 0).forGetter(Undertaking::progress),
                Codec.INT.optionalFieldOf("target", 0).forGetter(Undertaking::target),
                Codec.STRING.optionalFieldOf("resolution").forGetter(Undertaking::resolution),
                Codec.LONG.fieldOf("opened_day").forGetter(Undertaking::openedDay),
                Codec.LONG.fieldOf("updated_day").forGetter(Undertaking::updatedDay)
        ).apply(inst, Undertaking::new));

        public boolean isOpen() {
            return state == State.OPEN || state == State.ACTIVE;
        }

        /** 0..1 for a progress bar, whether the matter is stepless or milestoned. */
        public float fraction() {
            if (!steps.isEmpty()) {
                long reached = steps.stream().filter(Milestone::reached).count();
                return steps.isEmpty() ? 0F : (float) reached / steps.size();
            }
            return target <= 0 ? (state == State.RESOLVED ? 1F : 0F)
                    : Math.min(1F, (float) progress / target);
        }
    }

    public static final Codec<UndertakingData> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Undertaking.CODEC.listOf().fieldOf("undertakings").forGetter(UndertakingData::undertakings)
    ).apply(inst, UndertakingData::new));

    /** This player's open matters with the villager, for the chat briefing. */
    public List<Undertaking> openWith(UUID player) {
        List<Undertaking> out = new ArrayList<>();
        for (Undertaking u : undertakings) {
            if (u.isOpen() && u.withWhom().map(player::equals).orElse(false)) {
                out.add(u);
            }
        }
        return out;
    }

    /** Everything the villager is currently seeing through, for "what's on your plate?". */
    public List<Undertaking> allOpen() {
        return undertakings.stream().filter(Undertaking::isOpen).toList();
    }

    public Optional<Undertaking> byId(UUID id) {
        return undertakings.stream().filter(u -> u.id().equals(id)).findFirst();
    }

    /** Adds a matter and trims to the caps, open and resolved counted separately. */
    public UndertakingData with(Undertaking added) {
        List<Undertaking> updated = new ArrayList<>(undertakings);
        updated.removeIf(u -> u.id().equals(added.id())); // replace in place on update
        updated.add(added);
        trim(updated, true, MAX_OPEN);
        trim(updated, false, MAX_RESOLVED);
        return new UndertakingData(List.copyOf(updated));
    }

    /** Keeps at most {@code max} of the open (or terminal) matters, oldest dropped first. */
    private static void trim(List<Undertaking> list, boolean open, int max) {
        List<Undertaking> kind = list.stream().filter(u -> u.isOpen() == open).toList();
        if (kind.size() <= max) {
            return;
        }
        List<Undertaking> byAge = new ArrayList<>(kind);
        byAge.sort((a, b) -> Long.compare(a.updatedDay(), b.updatedDay()));
        for (int i = 0; i < byAge.size() - max; i++) {
            list.remove(byAge.get(i));
        }
    }
}
