package com.quzzar.villagelife.persona;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import java.util.Locale;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.entities.genetics.Stat;
import com.quzzar.villagelife.persona.PersonaSpawner.SpawnAttempt;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.fml.loading.FMLPaths;

/**
 * One /vlpersona audit run: N villagers created through
 * {@link PersonaSpawner#trySpawn}, one at a time, with every attempt (spawned
 * or skipped) collected into a report file for the dogfood review. The
 * persona queue in LlmService already yields to pending decide() calls, so an
 * audit cannot stall village decisions.
 */
final class PersonaAuditRun {

    private final CommandSourceStack source;
    private final ServerLevel level;
    private final int count;
    private final Random random = new Random();
    private final List<SpawnAttempt> attempts = new ArrayList<>();
    /** A cloud judge future per successful attempt (issue #77); empty when the judge is off. */
    private final Map<SpawnAttempt, CompletableFuture<PersonaJudge.Result>> judged = new IdentityHashMap<>();
    private final long startedAt = System.currentTimeMillis();

    PersonaAuditRun(CommandSourceStack source, int count) {
        this.source = source;
        this.level = source.getLevel();
        this.count = count;
    }

    void start() {
        runSlot(1);
    }

    private void runSlot(int slot) {
        if (slot > count) {
            finish();
            return;
        }
        PersonaSpawner.trySpawn(level, pickSpawnPos(), person -> {
        }).thenAccept(attempt -> {
            attempts.add(attempt);
            if (attempt.spawned().isPresent() && attempt.persona().isPresent()) {
                PersonaData persona = attempt.persona().get();
                // Score this blurb against its intended traits with the cloud judge,
                // in parallel with the next spawn (issue #77). buildTraits re-derives
                // the exact phrases generation was asked to convey.
                judged.put(attempt, PersonaJudge.judge(
                        PersonaPrompts.buildTraits(attempt.spawned().get()), persona.blurb(), persona.quirk()));
                source.sendSuccess(() -> Component.literal(
                        "[" + slot + "/" + count + "] " + attempt.name() + " arrived ("
                                + persona.generationMs() + " ms). Quirk: " + persona.quirk()), false);
            } else {
                source.sendSuccess(() -> Component.literal(
                        "[" + slot + "/" + count + "] arrival skipped (persona generation failed)"), false);
            }
            runSlot(slot + 1);
        });
    }

    private BlockPos pickSpawnPos() {
        BlockPos base = BlockPos.containing(source.getPosition());
        BlockPos scattered = base.offset(random.nextInt(9) - 4, 0, random.nextInt(9) - 4);
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, scattered);
    }

    private void finish() {
        // Let the parallel judge calls settle before the report is built, so their
        // per-trait scores land in it. allOf over an empty set completes at once.
        CompletableFuture.allOf(judged.values().toArray(new CompletableFuture[0]))
                .whenComplete((v, e) -> writeReport());
    }

    private void writeReport() {
        long totalMs = System.currentTimeMillis() - startedAt;
        long succeeded = attempts.stream().filter(a -> a.spawned().isPresent()).count();

        StringBuilder report = new StringBuilder();
        report.append("Persona audit: ").append(succeeded).append('/').append(count)
                .append(" spawned, ").append(totalMs / 1000).append("s total, prompt v")
                .append(PersonaPrompts.PROMPT_VERSION).append('\n');
        report.append(judgeHeader());
        for (SpawnAttempt attempt : attempts) {
            report.append('\n').append("=== ").append(attempt.name()).append(" ===\n");
            if (attempt.stats() != null) {
                for (Stat stat : Stat.values()) {
                    report.append(stat.getNbtKey()).append('=').append(attempt.stats().get(stat)).append(' ');
                }
                report.append("condition=").append(attempt.stats().getCondition()).append('\n');
            }
            report.append("sheet: ").append(attempt.sheet()).append('\n');
            if (attempt.persona().isPresent()) {
                PersonaData persona = attempt.persona().get();
                report.append("BLURB: ").append(persona.blurb()).append('\n');
                report.append("QUIRK: ").append(persona.quirk()).append('\n');
                report.append("(").append(persona.generationMs()).append(" ms)\n");
                appendJudgement(report, attempt);
            } else {
                report.append("SKIPPED: persona generation failed\n");
            }
        }

        Path reportPath = FMLPaths.GAMEDIR.get().resolve("villagelife")
                .resolve("persona-audit-" + startedAt + ".txt");
        String tail;
        try {
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, report.toString());
            tail = " spawned. Report: " + reportPath;
        } catch (IOException ex) {
            Villagelife.LOGGER.error("Could not write persona audit report", ex);
            tail = " spawned. Report could not be written (see log).";
        }
        // Back to the server thread: writeReport runs on whichever thread finished
        // the last judge call, which is not the server thread.
        String message = "Persona audit done: " + succeeded + "/" + count + tail;
        source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(message), true));
    }

    /** The aggregate judge line across every scored persona, or a note when the judge is off. */
    private String judgeHeader() {
        int conveyed = 0;
        int contradicted = 0;
        int absent = 0;
        int unscored = 0;
        int totalTraits = 0;
        boolean anyScored = false;
        for (CompletableFuture<PersonaJudge.Result> future : judged.values()) {
            PersonaJudge.Result r = future.join();
            if (!r.ok()) {
                continue;
            }
            anyScored = true;
            conveyed += r.count(PersonaJudge.Verdict.CONVEYED);
            contradicted += r.count(PersonaJudge.Verdict.CONTRADICTED);
            absent += r.count(PersonaJudge.Verdict.ABSENT);
            unscored += r.count(PersonaJudge.Verdict.UNSCORED);
            totalTraits += r.total();
        }
        if (!anyScored) {
            return PersonaJudge.configured()
                    ? "Judge: no personas scored (see per-persona lines for the error)\n"
                    : "Judge: off (set the Persona judge API key to score persona quality)\n";
        }
        String line = "Judge (" + VillagelifeConfig.PersonaJudgeProvider + "): conveyed "
                + pct(conveyed, totalTraits) + " (" + conveyed + '/' + totalTraits
                + "), contradicted " + contradicted + ", absent " + absent;
        return (unscored > 0 ? line + ", unscored " + unscored : line) + '\n';
    }

    /** Per-persona verdict line, naming the contradictions and absences worth reading. */
    private void appendJudgement(StringBuilder report, SpawnAttempt attempt) {
        CompletableFuture<PersonaJudge.Result> future = judged.get(attempt);
        if (future == null) {
            return;
        }
        PersonaJudge.Result r = future.join();
        if (!r.ok()) {
            report.append("JUDGE: ").append(r.error()).append('\n');
            return;
        }
        report.append("JUDGE: conveyed ").append(r.count(PersonaJudge.Verdict.CONVEYED)).append('/').append(r.total())
                .append(", contradicted ").append(r.count(PersonaJudge.Verdict.CONTRADICTED))
                .append(", absent ").append(r.count(PersonaJudge.Verdict.ABSENT)).append('\n');
        // A contradiction is a shipped-wrong description, an absence a dropped
        // trait: both are worth naming, a conveyed trait is not.
        for (PersonaJudge.TraitVerdict tv : r.verdicts()) {
            if (tv.verdict() == PersonaJudge.Verdict.CONTRADICTED || tv.verdict() == PersonaJudge.Verdict.ABSENT) {
                report.append("  ").append(tv.verdict().name().toLowerCase(Locale.ROOT))
                        .append(": ").append(tv.trait()).append('\n');
            }
        }
    }

    private static String pct(int n, int total) {
        return total == 0 ? "n/a" : Math.round(100.0 * n / total) + "%";
    }
}
