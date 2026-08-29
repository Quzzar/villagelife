package com.quzzar.villagelife.persona;

import java.util.List;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.llm.LlmService;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;

/**
 * Dev/audit commands for the persona system (persona map issue #6). Registered
 * from the persona package to keep this out of VillagelifeCommands' lane.
 *
 * /vlpersona audit <count>  - spawn N villagers through the full
 *                             generate-before-spawn pipeline and dump a report
 * /vlpersona show <entity>  - print the persona an existing person carries
 */
public final class PersonaCommands {

    private PersonaCommands() {
    }

    /** Mounted under /vldev by {@link com.quzzar.villagelife.dev.DevCommands}. */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> branch() {
        return Commands.literal("persona")
                .then(Commands.literal("audit")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 20))
                                .executes(context -> audit(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "count")))))
                .then(Commands.literal("show")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(context -> show(context.getSource(),
                                        EntityArgument.getEntity(context, "target")))))
                .then(Commands.literal("judgetest").executes(context -> judgetest(context.getSource())));
    }

    private static int audit(CommandSourceStack source, int count) {
        if (!LlmService.get().isReady()) {
            source.sendFailure(Component.literal(
                    "The LLM is not ready (status: " + LlmService.get().getStatus() + "). Try /vlbrain load."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Persona audit: generating " + count + " villagers (personas generate before each spawn)..."), true);
        new PersonaAuditRun(source, count).start();
        return 1;
    }

    private static int show(CommandSourceStack source, net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof RealPerson person)) {
            source.sendFailure(Component.literal("Target is not a villagelife person."));
            return 0;
        }
        PersonaData persona = PersonaService.get(person);
        if (persona.isEmpty()) {
            source.sendSuccess(() -> Component.literal(person.getFullName() + " has no persona."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                person.getFullName() + "\nBLURB: " + persona.blurb() + "\nQUIRK: " + persona.quirk()
                        + "\n(" + persona.model() + ", " + persona.generationMs() + " ms, prompt v"
                        + persona.promptVersion() + ")"),
                false);
        return 1;
    }

    /**
     * Deterministic selftest of {@link PersonaJudge#parse} (issue #77), so the
     * mapping from a judge reply to per-trait verdicts is exercised without a cloud
     * call: order, leniency, missing entries, junk, and the aggregate helpers. The
     * live scoring path is the cloud call in a real {@code persona audit}.
     */
    private static int judgetest(CommandSourceStack source) {
        int[] pass = {0};
        int[] fail = {0};
        List<String> traits = List.of("a true giant, towering over everyone",
                "physically weak but never ill", "quick-tempered");

        PersonaJudge.Result clean = PersonaJudge.parse(
                "[{\"trait\":\"a true giant\",\"verdict\":\"conveyed\"},"
                        + "{\"trait\":\"weak but never ill\",\"verdict\":\"contradicted\"},"
                        + "{\"trait\":\"quick-tempered\",\"verdict\":\"absent\"}]", traits);
        check(source, pass, fail, "clean array maps verdicts in order, by intended trait text",
                clean.ok() && clean.total() == 3
                        && clean.verdicts().get(0).trait().equals(traits.get(0))
                        && clean.verdicts().get(0).verdict() == PersonaJudge.Verdict.CONVEYED
                        && clean.verdicts().get(1).verdict() == PersonaJudge.Verdict.CONTRADICTED
                        && clean.verdicts().get(2).verdict() == PersonaJudge.Verdict.ABSENT);
        check(source, pass, fail, "aggregate counts and conveyedFraction",
                clean.count(PersonaJudge.Verdict.CONVEYED) == 1
                        && clean.count(PersonaJudge.Verdict.CONTRADICTED) == 1
                        && Math.abs(clean.conveyedFraction() - 1.0 / 3.0) < 1e-9);

        PersonaJudge.Result fenced = PersonaJudge.parse(
                "Here you go:\n```json\n[{\"verdict\":\"conveyed\"},{\"verdict\":\"conveyed\"},"
                        + "{\"verdict\":\"conveyed\"}]\n```", traits);
        check(source, pass, fail, "array extracted from surrounding prose and code fence",
                fenced.ok() && fenced.count(PersonaJudge.Verdict.CONVEYED) == 3);

        PersonaJudge.Result partial = PersonaJudge.parse("[{\"verdict\":\"conveyed\"}]", traits);
        check(source, pass, fail, "missing entries mark trailing traits unscored, none dropped",
                partial.ok() && partial.total() == 3
                        && partial.verdicts().get(2).verdict() == PersonaJudge.Verdict.UNSCORED);

        PersonaJudge.Result synonyms = PersonaJudge.parse(
                "[{\"verdict\":\"CONVEYED\"},{\"verdict\":\"contradicts the sheet\"},{\"verdict\":\"Absent.\"}]", traits);
        check(source, pass, fail, "verdict strings matched leniently (case and prefix)",
                synonyms.verdicts().get(0).verdict() == PersonaJudge.Verdict.CONVEYED
                        && synonyms.verdicts().get(1).verdict() == PersonaJudge.Verdict.CONTRADICTED
                        && synonyms.verdicts().get(2).verdict() == PersonaJudge.Verdict.ABSENT);

        PersonaJudge.Result bad = PersonaJudge.parse("the model refused and wrote a paragraph", traits);
        check(source, pass, fail, "unparseable reply fails cleanly (ok=false), no throw", !bad.ok());

        PersonaJudge.Result none = PersonaJudge.parse("[]", List.of());
        check(source, pass, fail, "traitless persona is vacuously fully conveyed",
                none.ok() && none.total() == 0 && none.conveyedFraction() == 1.0);

        source.sendSuccess(() -> Component.literal(String.format(
                "Persona judge parse selftest: %d passed, %d failed", pass[0], fail[0])), false);
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
}
