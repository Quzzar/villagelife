package com.quzzar.villagelife.entities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;

import com.quzzar.villagelife.entities.UndertakingData.Milestone;
import com.quzzar.villagelife.entities.UndertakingData.Origin;
import com.quzzar.villagelife.entities.UndertakingData.State;
import com.quzzar.villagelife.entities.UndertakingData.Undertaking;
import com.quzzar.villagelife.entities.UndertakingData.Valence;

/**
 * Takes an op the model PROPOSED and decides whether the villager's undertakings
 * change (docs/undertakings.md). The model never edits the store directly: it
 * asks, and this validates and applies, the same discipline as the give and
 * opinion tools. An invented op, an open with no summary, or a resolve with no
 * open matter to resolve is dropped, not honoured.
 *
 * The apply is a pure function over {@link UndertakingData} so it can be tested
 * without a world (see the selftest). The standing effect of a resolved amends,
 * which needs the entity, is layered on at the call site once the chat tool is
 * wired; the state machine itself lives here and stands alone.
 */
public final class UndertakingService {

    private UndertakingService() {
    }

    /** The parsed proposal from a chat reply's optional {@code undertaking} field. */
    public record Op(String op, String summary, String valence, String note, String step) {

        /** Reads an op out of the JSON the model emitted, or empty if it is unusable. */
        public static Optional<Op> parse(JsonObject json) {
            if (json == null || !json.has("op")) {
                return Optional.empty();
            }
            String op = str(json, "op").toLowerCase();
            if (!op.equals("open") && !op.equals("advance") && !op.equals("resolve")) {
                return Optional.empty(); // an op the model invented
            }
            return Optional.of(new Op(op, str(json, "summary"), str(json, "valence"),
                    str(json, "note"), str(json, "step")));
        }

        private static String str(JsonObject json, String key) {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString().trim() : "";
        }
    }

    /** What an apply did, so a caller and a harness can both see the outcome. */
    public record Result(UndertakingData data, boolean changed, String action) {
        static Result unchanged(UndertakingData data, String why) {
            return new Result(data, false, "dropped: " + why);
        }
    }

    /**
     * Applies one op. {@code withPlayer} says whether this exchange is with the
     * player (an open then belongs to them, and advance/resolve prefer their
     * matter); a self-goal advance passes false.
     */
    public static Result apply(UndertakingData data, Op op, UUID player, boolean withPlayer, long dayTime) {
        return switch (op.op()) {
            case "open" -> open(data, op, player, withPlayer, dayTime);
            case "advance" -> advance(data, op, player, withPlayer, dayTime);
            case "resolve" -> resolve(data, op, player, withPlayer, dayTime);
            default -> Result.unchanged(data, "unknown op");
        };
    }

    private static Result open(UndertakingData data, Op op, UUID player, boolean withPlayer, long dayTime) {
        if (op.summary().isBlank()) {
            return Result.unchanged(data, "open with no summary");
        }
        Valence valence = op.valence().toLowerCase().startsWith("neg") ? Valence.NEGATIVE : Valence.POSITIVE;
        Undertaking created = new Undertaking(
                UUID.randomUUID(), valence, State.OPEN,
                withPlayer ? Origin.PLAYER : Origin.SELF,
                op.summary(),
                withPlayer ? Optional.of(player) : Optional.empty(),
                List.of(),
                op.note().isBlank() ? Optional.empty() : Optional.of(op.note()),
                Optional.empty(), dayTime, dayTime);
        return new Result(data.with(created), true, "opened: " + op.summary());
    }

    private static Result advance(UndertakingData data, Op op, UUID player, boolean withPlayer, long dayTime) {
        Optional<Undertaking> target = pick(data, player, withPlayer);
        if (target.isEmpty()) {
            return Result.unchanged(data, "advance with no open matter");
        }
        Undertaking u = target.get();
        List<Milestone> steps = u.steps();
        Optional<String> note = u.progressNote();
        if (!op.step().isBlank()) {
            steps = markOrAdd(steps, op.step());
        } else if (!op.note().isBlank()) {
            note = Optional.of(op.note());
        } else {
            return Result.unchanged(data, "advance with nothing to record");
        }
        Undertaking moved = new Undertaking(u.id(), u.valence(), State.ACTIVE, u.origin(),
                u.summary(), u.withWhom(), steps, note, u.resolution(), u.openedDay(), dayTime);
        return new Result(data.with(moved), true, "advanced: " + u.summary());
    }

    private static Result resolve(UndertakingData data, Op op, UUID player, boolean withPlayer, long dayTime) {
        Optional<Undertaking> target = pick(data, player, withPlayer);
        if (target.isEmpty()) {
            return Result.unchanged(data, "resolve with no open matter");
        }
        Undertaking u = target.get();
        String resolution = !op.note().isBlank() ? op.note()
                : !op.summary().isBlank() ? op.summary() : "Seen through.";
        Undertaking done = new Undertaking(u.id(), u.valence(), State.RESOLVED, u.origin(),
                u.summary(), u.withWhom(), u.steps(), u.progressNote(),
                Optional.of(resolution), u.openedDay(), dayTime);
        return new Result(data.with(done), true, "resolved: " + u.summary());
    }

    /**
     * Which open undertaking an advance or resolve means. The model does not
     * name an id, so the server chooses: the most recently touched open matter
     * with this player, or failing that the most recent open matter at all (a
     * self-goal the villager is advancing on their own).
     */
    private static Optional<Undertaking> pick(UndertakingData data, UUID player, boolean withPlayer) {
        List<Undertaking> open = new ArrayList<>(data.allOpen());
        if (open.isEmpty()) {
            return Optional.empty();
        }
        open.sort(Comparator.comparingLong(Undertaking::updatedDay).reversed());
        if (withPlayer) {
            for (Undertaking u : open) {
                if (u.withWhom().map(player::equals).orElse(false)) {
                    return Optional.of(u);
                }
            }
        }
        return Optional.of(open.get(0));
    }

    private static List<Milestone> markOrAdd(List<Milestone> steps, String stepText) {
        List<Milestone> out = new ArrayList<>();
        boolean matched = false;
        for (Milestone m : steps) {
            if (!matched && m.text().equalsIgnoreCase(stepText)) {
                out.add(new Milestone(m.text(), true));
                matched = true;
            } else {
                out.add(m);
            }
        }
        if (!matched) {
            out.add(new Milestone(stepText, true)); // a step the model named as reached
        }
        return out;
    }
}
