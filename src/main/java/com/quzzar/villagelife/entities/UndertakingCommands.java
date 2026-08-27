package com.quzzar.villagelife.entities;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.UndertakingData.State;
import com.quzzar.villagelife.entities.UndertakingData.Undertaking;
import com.quzzar.villagelife.entities.UndertakingData.Valence;
import com.quzzar.villagelife.entities.UndertakingService.Op;
import com.quzzar.villagelife.llm.LlmService;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Two harnesses for the undertaking system (docs/undertakings.md), because the
 * feature is only ever driven through the model and Aaron's whole concern is
 * whether the model USES it:
 *
 * <ul>
 *   <li>{@code /vldev undertaking selftest} — the apply state machine, checked
 *       without the model: open then advance then resolve, and every drop path
 *       (invented op, open with no summary, resolve with nothing open). Pure and
 *       deterministic, so a regression here is caught instantly.
 *   <li>{@code /vldev undertaking audit} — the model, checked against scripted
 *       turns. Some turns SHOULD produce an op and some deliberately should not,
 *       and it reports PRECISION (of the ops it emitted, how many were wanted)
 *       ahead of recall, because over-emission — an undertaking on a turn about
 *       the weather — is the failure that bites, the same way the give tool once
 *       handed out diamonds unprompted.
 * </ul>
 */
public final class UndertakingCommands {

    private UndertakingCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> branch() {
        return Commands.literal("undertaking")
                .then(Commands.literal("selftest").executes(c -> selftest(c.getSource())))
                .then(Commands.literal("audit").executes(c -> audit(c.getSource())));
    }

    // ---- the deterministic apply state machine -----------------------------

    private static int selftest(CommandSourceStack source) {
        UUID player = UUID.randomUUID();
        int[] pass = {0};
        int[] fail = {0};

        // open → a matter exists, OPEN, owned by the player.
        UndertakingData d = UndertakingData.EMPTY;
        var r1 = UndertakingService.apply(d,
                new Op("open", "Bring me ten wheat for the grain you took", "negative", "", ""),
                player, true, 100L);
        d = r1.data();
        check(source, pass, fail, "open creates one OPEN matter",
                r1.changed() && d.allOpen().size() == 1
                        && d.allOpen().get(0).state() == State.OPEN
                        && d.allOpen().get(0).valence() == Valence.NEGATIVE);

        // advance → same matter, ACTIVE, note recorded, still one matter.
        var r2 = UndertakingService.apply(d,
                new Op("advance", "", "", "Four of the ten brought", ""), player, true, 101L);
        d = r2.data();
        check(source, pass, fail, "advance moves it to ACTIVE and keeps one matter",
                r2.changed() && d.allOpen().size() == 1
                        && d.allOpen().get(0).state() == State.ACTIVE
                        && d.allOpen().get(0).progressNote().orElse("").contains("Four"));

        // resolve → gone from open, has a resolution.
        var r3 = UndertakingService.apply(d,
                new Op("resolve", "", "", "Paid in full", ""), player, true, 105L);
        UndertakingData resolved = r3.data();
        check(source, pass, fail, "resolve closes it and records a resolution",
                r3.changed() && resolved.allOpen().isEmpty()
                        && resolved.undertakings().get(0).state() == State.RESOLVED
                        && resolved.undertakings().get(0).resolution().isPresent());

        // drop: an op the model invented.
        var bad = UndertakingService.apply(d, new Op("obliterate", "x", "negative", "", ""), player, true, 106L);
        check(source, pass, fail, "invented op is dropped", !bad.changed());

        // drop: open with no summary.
        var noSummary = UndertakingService.apply(UndertakingData.EMPTY,
                new Op("open", "", "positive", "", ""), player, true, 107L);
        check(source, pass, fail, "open with no summary is dropped", !noSummary.changed());

        // drop: resolve with nothing open.
        var noMatter = UndertakingService.apply(UndertakingData.EMPTY,
                new Op("resolve", "", "", "done", ""), player, true, 108L);
        check(source, pass, fail, "resolve with nothing open is dropped", !noMatter.changed());

        // milestone advance: a self-goal marks a step reached.
        UndertakingData house = UndertakingData.EMPTY.with(new Undertaking(
                UUID.randomUUID(), Valence.POSITIVE, State.ACTIVE, UndertakingData.Origin.SELF,
                "Save up for a proper house", Optional.empty(),
                List.of(new UndertakingData.Milestone("Coin set aside", true),
                        new UndertakingData.Milestone("Timber cut", true)),
                Optional.empty(), Optional.empty(), 50L, 60L));
        var step = UndertakingService.apply(house,
                new Op("advance", "", "", "", "Frame raised"), player, false, 61L);
        check(source, pass, fail, "milestone advance marks a step reached",
                step.changed() && step.data().allOpen().get(0).steps().stream()
                        .anyMatch(m -> m.text().equals("Frame raised") && m.reached()));

        source.sendSuccess(() -> Component.literal(String.format(
                "Undertaking selftest: %d passed, %d failed", pass[0], fail[0])), false);
        return fail[0] == 0 ? 1 : 0;
    }

    private static void check(CommandSourceStack source, int[] pass, int[] fail, String what, boolean ok) {
        if (ok) {
            pass[0]++;
        } else {
            fail[0]++;
        }
        source.sendSuccess(() -> Component.literal((ok ? "  PASS " : "  FAIL ") + what), false);
    }

    // ---- the model-trigger precision/recall audit --------------------------

    /**
     * One scripted turn: the situation the villager is in, what the player says,
     * and whether a well-behaved model SHOULD emit an op. {@code expected} is the
     * op wanted, or empty string for "no op, this turn is not about a matter".
     */
    private record Case(String context, String playerLine, String expected) {
    }

    /** The measurement prompt. Deliberately its OWN prompt, not production RULES:
     *  this measures the MODEL's capability so the production wording can be tuned
     *  against a number. The undertaking tool is only offered here because every
     *  case is one where it is plausibly in play. */
    private static final String AUDIT_SYSTEM =
            "You are a villager in a medieval village, talking to a player. Answer in character, "
            + "one or two short sentences. When the moment calls for it you may track a matter you "
            + "are seeing through, with an \"undertaking\" field: {\"op\": \"open\"} to begin one "
            + "(give \"summary\" and \"valence\": positive or negative), {\"op\": \"advance\"} when "
            + "it moves forward (give a \"note\"), {\"op\": \"resolve\"} when it is done. Only use it "
            + "when something is genuinely begun, advanced, or finished. Most turns have no "
            + "undertaking at all. Answer with ONLY a JSON object: {\"say\": \"...\"} optionally with "
            + "an \"undertaking\".";

    private static final List<Case> CASES = List.of(
            // should OPEN
            new Case("The player robbed your chest yesterday and you saw them.",
                    "I'm sorry about your chest. How can I make it up to you?", "open"),
            new Case("You are a builder short of oak for the next house.",
                    "I could fetch you materials if you tell me what you need.", "open"),
            // should ADVANCE (an open matter is in play)
            new Case("Matter with this player: they owe you ten wheat for grain they took. "
                    + "So far none brought.", "Here, I've brought you four wheat toward what I owe.", "advance"),
            new Case("Matter you are seeing through: saving for a bigger house; the frame is up.",
                    "How's the house coming along?", "advance"),
            // should RESOLVE
            new Case("Matter with this player: they owe you ten wheat. They have brought nine.",
                    "Here's the last wheat — that's all ten now.", "resolve"),
            // should NOT fire (the bite: over-emission)
            new Case("A calm day at your market stall.", "Lovely weather today, isn't it?", ""),
            new Case("A calm day at your market stall.", "What do you sell here?", ""),
            new Case("You are tending your field.", "Where would I find the blacksmith?", ""),
            new Case("Matter with this player: they owe you ten wheat, none brought yet.",
                    "Just passing through, don't mind me.", ""),
            new Case("A quiet evening.", "Goodnight, then.", ""));

    private static int audit(CommandSourceStack source) {
        if (!LlmService.get().isReady()) {
            source.sendFailure(Component.literal("LLM not ready; audit needs the model."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Undertaking audit: " + CASES.size() + " scripted turns, measuring the model..."), false);

        AtomicInteger shouldFire = new AtomicInteger();
        AtomicInteger didFire = new AtomicInteger();
        AtomicInteger correctFire = new AtomicInteger();   // fired AND wanted, right op
        AtomicInteger wrongOp = new AtomicInteger();        // fired when wanted but wrong op
        AtomicInteger falseFire = new AtomicInteger();      // fired when NOT wanted
        AtomicInteger missed = new AtomicInteger();         // wanted but silent
        StringBuilder log = new StringBuilder("\n=== undertaking audit ===\n");

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Case c : CASES) {
            chain = chain.thenCompose(v -> runCase(c, shouldFire, didFire, correctFire,
                    wrongOp, falseFire, missed, log));
        }
        chain.whenComplete((v, e) -> {
            int fires = didFire.get();
            double precision = fires == 0 ? 1.0 : (double) correctFire.get() / fires;
            double recall = shouldFire.get() == 0 ? 1.0 : (double) correctFire.get() / shouldFire.get();
            log.append(String.format(
                    "\nPRECISION %.0f%% (%d of %d ops were wanted, right op)\n"
                    + "RECALL    %.0f%% (%d of %d wanted ops fired correctly)\n"
                    + "false fires %d (ops on turns that should have been silent)\n"
                    + "wrong op    %d (fired on a right turn, wrong op)\n"
                    + "missed      %d (wanted an op, got none)\n",
                    precision * 100, correctFire.get(), fires,
                    recall * 100, correctFire.get(), shouldFire.get(),
                    falseFire.get(), wrongOp.get(), missed.get()));
            Villagelife.LOGGER.info(log.toString());
            source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(String.format(
                    "Audit done: precision %.0f%%, recall %.0f%%, %d false fires. Full report in the log.",
                    precision * 100, recall * 100, falseFire.get())), false));
        });
        return 1;
    }

    private static CompletableFuture<Void> runCase(Case c, AtomicInteger shouldFire, AtomicInteger didFire,
            AtomicInteger correctFire, AtomicInteger wrongOp, AtomicInteger falseFire, AtomicInteger missed,
            StringBuilder log) {
        boolean wanted = !c.expected().isBlank();
        if (wanted) {
            shouldFire.incrementAndGet();
        }
        String user = "Situation: " + c.context() + "\nPlayer says: \"" + c.playerLine()
                + "\"\nYour JSON answer:";
        return LlmService.get().submitChat(AUDIT_SYSTEM, user, List.of(), 96, 0.4D, 0.3D)
                .thenAccept(raw -> {
                    Optional<Op> op = raw.flatMap(UndertakingCommands::opOf);
                    if (op.isPresent()) {
                        didFire.incrementAndGet();
                        if (!wanted) {
                            falseFire.incrementAndGet();
                        } else if (op.get().op().equals(c.expected())) {
                            correctFire.incrementAndGet();
                        } else {
                            wrongOp.incrementAndGet();
                        }
                    } else if (wanted) {
                        missed.incrementAndGet();
                    }
                    String got = op.map(o -> o.op()).orElse("(none)");
                    String verdict = wanted
                            ? (op.map(o -> o.op().equals(c.expected())).orElse(false) ? "OK" : "MISS")
                            : (op.isPresent() ? "FALSE-FIRE" : "OK");
                    log.append(String.format("  [%-10s] want %-8s got %-8s  \"%s\"\n",
                            verdict, c.expected().isBlank() ? "(none)" : c.expected(), got, c.playerLine()));
                });
    }

    /** Pull the undertaking op out of a raw reply, tolerant of junk around the JSON. */
    private static Optional<Op> opOf(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
            if (!root.has("undertaking") || !root.get("undertaking").isJsonObject()) {
                return Optional.empty();
            }
            return Op.parse(root.getAsJsonObject("undertaking"));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
