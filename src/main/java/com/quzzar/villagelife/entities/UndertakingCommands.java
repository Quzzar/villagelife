package com.quzzar.villagelife.entities;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
                .then(Commands.literal("audit").executes(c -> audit(c.getSource())))
                .then(Commands.literal("show")
                        .then(Commands.argument("target", net.minecraft.commands.arguments.EntityArgument.entity())
                                .executes(c -> show(c.getSource(),
                                        net.minecraft.commands.arguments.EntityArgument.getEntity(c, "target")))));
    }

    /**
     * Prints a villager's undertakings and the Tester's standing with them, so the
     * write path can be eyeballed end to end after a {@code /vlbrain chat} turn:
     * does a real conversation actually persist, advance, resolve, and bump standing.
     */
    private static int show(CommandSourceStack source, net.minecraft.world.entity.Entity target) {
        if (!(target instanceof RealPerson person)) {
            source.sendFailure(Component.literal("Target is not a villager."));
            return 0;
        }
        List<Undertaking> all = person.getData(VillagelifeAttachments.UNDERTAKINGS.get()).undertakings();
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal(person.getFullName() + " has no undertakings."), false);
        } else {
            source.sendSuccess(() -> Component.literal(person.getFullName() + " has " + all.size() + " undertaking(s):"), false);
            for (Undertaking u : all) {
                String line = "  [" + u.state() + "/" + u.valence() + "] " + u.summary()
                        + u.progressNote().map(n -> " | note: " + n).orElse("")
                        + u.resolution().map(r -> " | resolved: " + r).orElse("");
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
        UUID tester = UUID.nameUUIDFromBytes("villagelife-tester".getBytes());
        int standing = person.getData(VillagelifeAttachments.SOCIAL.get()).relationships().getOrDefault(tester, 0);
        source.sendSuccess(() -> Component.literal("  Tester standing: " + standing), false);
        return 1;
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

        // the standing-bump trigger: a resolved NEGATIVE matter reports it, a positive one does not.
        check(source, pass, fail, "resolvedNegative fires on a righted wrong", r3.resolvedNegative());
        var posDone = UndertakingService.apply(
                UndertakingData.EMPTY.with(new Undertaking(UUID.randomUUID(), Valence.POSITIVE, State.ACTIVE,
                        UndertakingData.Origin.PLAYER, "Bring the promised apples", Optional.of(player),
                        List.of(), Optional.empty(), Optional.empty(), 40L, 41L)),
                new Op("resolve", "", "", "All delivered", ""), player, true, 42L);
        check(source, pass, fail, "resolvedNegative stays false on a kept promise",
                posDone.changed() && !posDone.resolvedNegative());

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

        // coercion: an "open" while a matter with this player stands folds into it as
        // an advance, so no second matter is created and the note is recorded.
        var coerced = UndertakingService.apply(resolved.with(r1.data().allOpen().get(0)),
                new Op("open", "They owe me even more wheat now", "negative", "", ""), player, true, 110L);
        check(source, pass, fail, "open coerces to advance when a player matter stands",
                coerced.changed() && coerced.action().startsWith("advanced")
                        && coerced.data().openWith(player).size() == 1);

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
    /** Which production undertaking state this case lands in (see PersonChatContext). */
    private enum Mode { NONE, NEW_MATTER, OPEN_MATTER }

    private record Case(String context, String playerLine, String expected, boolean matterStandsWithPlayer) {

        /**
         * The state the live gate would put this turn in: a matter standing with the
         * player wins (OPEN_MATTER), else a commitment-opening line (NEW_MATTER), else
         * the tool is never offered (NONE). Exactly PersonChatContext.assemble's order.
         */
        Mode mode() {
            if (matterStandsWithPlayer) {
                return Mode.OPEN_MATTER;
            }
            return opensACommitment(playerLine) ? Mode.NEW_MATTER : Mode.NONE;
        }
    }

    /**
     * The measurement prompt, kept in step with production (PersonChatContext,
     * commit c7ad47c): the two-state split the live gate uses, mirrored verbatim.
     * The GATED pass picks a state exactly as production does (a matter stands ->
     * OPEN_MATTER, else a commitment-opening line -> NEW_MATTER, else the field is
     * never offered), so the number reflects what ships. The UNGATED pass offers
     * all three ops at once, the configuration 7b measured defaulting nearly every
     * matter to "open" - kept as the baseline the split is measured against.
     *
     * Mirrored, not imported, because PersonChatContext keeps these private; if that
     * wording moves, this must re-sync, and the comment there and here both say so.
     */
    private static final String AUDIT_BASE =
            "You are a villager in a medieval village, talking to a player. Answer in character, "
            + "one or two short sentences.";

    /** Ungated baseline: open+advance+resolve dangled together. */
    private static final String ALL_OPS_CLAUSE =
            " A lasting matter between you and this person can be recorded with \"undertaking\", but ONLY on the turn "
            + "it actually happens - most replies have none. When they make amends or promise something that will take "
            + "time, open it: {\"op\": \"open\", \"summary\": \"<what is to be done>\", \"valence\": "
            + "\"positive\" for a kindness or \"negative\" for a wrong to right}. When an existing matter moves "
            + "forward, {\"op\": \"advance\", \"note\": \"<what moved>\"}. When it is settled, "
            + "{\"op\": \"resolve\", \"note\": \"<how it ended>\"}. You never name which matter.";

    /** Gated, no matter stands but the line opens one: open only (mirrors RULES_NEW_MATTER). */
    private static final String NEW_MATTER_CLAUSE =
            " This person is making amends, or promising something that will take time, and no such matter yet stands "
            + "between you. If their words truly begin one, record it: \"undertaking\": {\"op\": \"open\", "
            + "\"summary\": \"<what is to be done, in a few words>\", \"valence\": \"positive\" for a kindness or "
            + "\"negative\" for a wrong to right}. Most turns begin nothing - leave it out unless this one does.";

    /** Gated, a matter stands: advance/resolve only, open withheld (mirrors RULES_OPEN_MATTER). */
    private static final String OPEN_MATTER_CLAUSE =
            " A matter already stands between you and this person (named above). You are NOT opening a new one - you are "
            + "moving it forward or settling it. If this exchange moves it along, \"undertaking\": {\"op\": \"advance\", "
            + "\"note\": \"<what moved>\"}. If it settles the matter for good, \"undertaking\": {\"op\": \"resolve\", "
            + "\"note\": \"<how it ended>\"}. When their words mark it FINISHED - the LAST of it, the FINAL piece, the WHOLE "
            + "amount, all of it now, the debt PAID, the task DONE - that is resolve, never advance. If it does neither, "
            + "leave \"undertaking\" out. You never name which matter - the game knows.";

    private static final String SHAPE_UNDERTAKING =
            " Answer with ONLY a JSON object: {\"say\": \"<reply>\"}, adding \"undertaking\" only when it applies.";

    /** Two open few-shots, one per valence, shown in NEW_MATTER (mirrors OPEN_EXAMPLES). */
    private static final List<LlmService.FewShotExample> OPEN_EXAMPLES = List.of(
            new LlmService.FewShotExample(
                    "Player says: \"I'm sorry I broke into your chest. How can I make it right?\"\nYour JSON answer:",
                    "{\"say\": \"Bring back the ten wheat you took and we're square.\", \"undertaking\": "
                    + "{\"op\": \"open\", \"summary\": \"Bring back the ten wheat taken from my chest\", "
                    + "\"valence\": \"negative\"}}"),
            new LlmService.FewShotExample(
                    "Player says: \"I'm sorry you're short on oak. I'll bring you a stack tomorrow.\"\nYour JSON answer:",
                    "{\"say\": \"That would see my roof done. I'd be in your debt, Steve.\", \"undertaking\": "
                    + "{\"op\": \"open\", \"summary\": \"Steve to bring a stack of oak for the roof\", "
                    + "\"valence\": \"positive\"}}"));

    /**
     * The four turns shown in OPEN_MATTER (mirrors PROGRESS_EXAMPLES): a neutral
     * no-op (advance was firing on every turn), a partial delivery (advance), and
     * two completions (resolve, one per valence) leaning hard on completion
     * language, since one resolve example proved too thin for the 3B.
     */
    private static final List<LlmService.FewShotExample> PROGRESS_EXAMPLES = List.of(
            new LlmService.FewShotExample(
                    "Player says: \"Just passing through, don't mind me.\"\nYour JSON answer:",
                    "{\"say\": \"Mind yourself, Steve. The roads are dark this hour.\"}"),
            new LlmService.FewShotExample(
                    "Player says: \"Here's four wheat toward what I owe you.\"\nYour JSON answer:",
                    "{\"say\": \"Four - a start. Six more and we're even.\", \"undertaking\": "
                    + "{\"op\": \"advance\", \"note\": \"Four of the ten wheat brought back\"}}"),
            new LlmService.FewShotExample(
                    "Player says: \"Here's the last of it - that's the whole ten wheat now.\"\nYour JSON answer:",
                    "{\"say\": \"Then we're square, Steve. No hard feelings.\", \"undertaking\": "
                    + "{\"op\": \"resolve\", \"note\": \"The wheat debt is paid in full\"}}"),
            new LlmService.FewShotExample(
                    "Player says: \"That's your fence done - every rail back in place.\"\nYour JSON answer:",
                    "{\"say\": \"Fine work, and quicker than I'd hoped. We're square.\", \"undertaking\": "
                    + "{\"op\": \"resolve\", \"note\": \"The fence is fully mended\"}}"));

    private static final List<LlmService.FewShotExample> ALL_UNDERTAKING_EXAMPLES;
    static {
        List<LlmService.FewShotExample> all = new java.util.ArrayList<>(OPEN_EXAMPLES);
        all.addAll(PROGRESS_EXAMPLES);
        ALL_UNDERTAKING_EXAMPLES = List.copyOf(all);
    }

    /** The commitment markers production gates NEW_MATTER on (mirrors COMMITMENT_MARKERS). */
    private static final String[] COMMITMENT_MARKERS = {
            "sorry", "apolog", "make it right", "make up for", "my fault", "forgive", "make amends",
            "owe", "repay", "pay you back", "i'll get you", "i will get you", "i'll bring", "i will bring",
            "promise", "i swear", "you have my word"
    };

    private static boolean opensACommitment(String playerLine) {
        String line = playerLine.toLowerCase(java.util.Locale.ROOT);
        for (String marker : COMMITMENT_MARKERS) {
            if (line.contains(marker)) {
                return true;
            }
        }
        return false;
    }

        private static final List<Case> CASES = List.of(
            // should OPEN (no matter stands yet). The gate needs a commitment marker
            // in the line: "sorry" catches the first, "owe/i'll bring" the offer to help.
            new Case("The player robbed your chest yesterday and you saw them.",
                    "I'm sorry about your chest. How can I make it up to you?", "open", false),
            new Case("You are a builder short of oak for the next house.",
                    "I'm sorry you're short. I'll bring you the oak you need.", "open", false),
            // should ADVANCE (an open matter is in play)
            new Case("Matter with this player: they owe you ten wheat for grain they took. "
                    + "So far none brought.", "Here, I've brought you four wheat toward what I owe.", "advance", true),
            new Case("Matter with this player: they promised you a new fence; the posts are set.",
                    "The fence posts are all set now, rails next.", "advance", true),
            // should RESOLVE
            new Case("Matter with this player: they owe you ten wheat. They have brought nine.",
                    "Here's the last wheat — that's all ten now.", "resolve", true),
            // should NOT fire (the bite: over-emission)
            new Case("A calm day at your market stall.", "Lovely weather today, isn't it?", "", false),
            new Case("A calm day at your market stall.", "What do you sell here?", "", false),
            new Case("You are tending your field.", "Where would I find the blacksmith?", "", false),
            new Case("Matter with this player: they owe you ten wheat, none brought yet.",
                    "Just passing through, don't mind me.", "", true),
            new Case("A quiet evening.", "Goodnight, then.", "", false));

    private static int audit(CommandSourceStack source) {
        if (!LlmService.get().isReady()) {
            source.sendFailure(Component.literal("LLM not ready; audit needs the model."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Undertaking audit: " + CASES.size() + " turns, ungated then gated, measuring the model..."),
                false);

        // Run the ungated pass (tool offered every turn), then the gated pass
        // (tool offered only when a matter is plausibly in play), so one run
        // yields both numbers and the delta that justifies gating.
        runPass(source, false).thenCompose(ungated ->
                runPass(source, true).thenAccept(gated -> {
                    StringBuilder out = new StringBuilder("\n=== undertaking audit (Llama vs the prompt) ===\n"
                            + "(* = model said open, server coerced to advance because a matter stands)\n");
                    out.append(ungated.render("UNGATED (tool every turn)"));
                    out.append(gated.render("GATED   (tool only when plausible)"));
                    out.append(String.format(
                            "\nDELTA: gating cut false fires %d -> %d, precision %.0f%% -> %.0f%%\n",
                            ungated.falseFire, gated.falseFire,
                            ungated.precision() * 100, gated.precision() * 100));
                    Villagelife.LOGGER.info(out.toString());
                    source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(String.format(
                            "Audit done. Ungated: precision %.0f%%, %d false fires. "
                            + "Gated: precision %.0f%%, %d false fires. Full report in the log.",
                            ungated.precision() * 100, ungated.falseFire,
                            gated.precision() * 100, gated.falseFire)), false));
                }));
        return 1;
    }

    /** One pass's tally. */
    private static final class Tally {
        int shouldFire, didFire, correctFire, wrongOp, falseFire, missed;
        final StringBuilder rows = new StringBuilder();

        double precision() {
            return didFire == 0 ? 1.0 : (double) correctFire / didFire;
        }

        double recall() {
            return shouldFire == 0 ? 1.0 : (double) correctFire / shouldFire;
        }

        String render(String title) {
            return String.format("%s\n%s"
                    + "  precision %.0f%% (%d/%d ops wanted+right)   recall %.0f%% (%d/%d)   "
                    + "false fires %d, wrong op %d, missed %d\n",
                    title, rows,
                    precision() * 100, correctFire, didFire, recall() * 100, correctFire, shouldFire,
                    falseFire, wrongOp, missed);
        }
    }

    private static CompletableFuture<Tally> runPass(CommandSourceStack source, boolean gated) {
        Tally t = new Tally();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Case c : CASES) {
            chain = chain.thenCompose(v -> runCase(c, gated, t));
        }
        return chain.thenApply(v -> t);
    }

    private static CompletableFuture<Void> runCase(Case c, boolean gated, Tally t) {
        boolean wanted = !c.expected().isBlank();
        if (wanted) {
            t.shouldFire++;
        }
        // Gating: in the gated pass the tool is offered only in the state production
        // would offer it. A NONE turn cannot fire, and gating it out is a correct
        // silence; a wanted op gated out is (rightly) a miss.
        Mode mode = c.mode();
        if (gated && mode == Mode.NONE) {
            if (wanted) {
                t.missed++;
            }
            t.rows.append(String.format("  [%-10s] want %-8s got %-8s  \"%s\"\n",
                    "GATED-OUT", c.expected().isBlank() ? "(none)" : c.expected(), "(none)", c.playerLine()));
            return CompletableFuture.completedFuture(null);
        }
        // The clause and few-shots for the state this turn is in. The ungated pass
        // offers all three ops at once (the 7b baseline); the gated pass offers only
        // the op(s) legal in this state, exactly as production does.
        String clause;
        List<LlmService.FewShotExample> shots;
        if (!gated) {
            clause = ALL_OPS_CLAUSE;
            shots = ALL_UNDERTAKING_EXAMPLES;
        } else if (mode == Mode.OPEN_MATTER) {
            clause = OPEN_MATTER_CLAUSE;
            shots = PROGRESS_EXAMPLES;
        } else {
            clause = NEW_MATTER_CLAUSE;
            shots = OPEN_EXAMPLES;
        }
        String system = AUDIT_BASE + clause + SHAPE_UNDERTAKING;
        String user = "Situation: " + c.context() + "\nPlayer says: \"" + c.playerLine()
                + "\"\nYour JSON answer:";
        return LlmService.get().submitChat(system, user, shots, 96, 0.4D, 0.3D)
                .thenAccept(raw -> {
                    Optional<Op> op = raw.flatMap(UndertakingCommands::opOf);
                    // Score the EFFECTIVE op, i.e. what UndertakingService.apply lands
                    // in-world: an "open" the model emits while a matter with this
                    // player stands is coerced to "advance" server-side, because
                    // whether a matter stands is server-known, not the 3B's to judge.
                    Optional<String> effective = op.map(o ->
                            o.op().equals("open") && c.matterStandsWithPlayer() ? "advance" : o.op());
                    if (effective.isPresent()) {
                        t.didFire++;
                        if (!wanted) {
                            t.falseFire++;
                        } else if (effective.get().equals(c.expected())) {
                            t.correctFire++;
                        } else {
                            t.wrongOp++;
                        }
                    } else if (wanted) {
                        t.missed++;
                    }
                    boolean coerced = op.map(o -> o.op().equals("open") && c.matterStandsWithPlayer()).orElse(false);
                    String got = effective.map(e -> coerced ? e + "*" : e).orElse("(none)");
                    String verdict = wanted
                            ? (effective.map(e -> e.equals(c.expected())).orElse(false) ? "OK" : "MISS")
                            : (effective.isPresent() ? "FALSE-FIRE" : "OK");
                    t.rows.append(String.format("  [%-10s] want %-8s got %-8s  \"%s\"\n",
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
