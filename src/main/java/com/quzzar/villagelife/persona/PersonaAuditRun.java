package com.quzzar.villagelife.persona;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.quzzar.villagelife.Villagelife;
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
        long totalMs = System.currentTimeMillis() - startedAt;
        long succeeded = attempts.stream().filter(a -> a.spawned().isPresent()).count();

        StringBuilder report = new StringBuilder();
        report.append("Persona audit: ").append(succeeded).append('/').append(count)
                .append(" spawned, ").append(totalMs / 1000).append("s total, prompt v")
                .append(PersonaPrompts.PROMPT_VERSION).append('\n');
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
            } else {
                report.append("SKIPPED: persona generation failed\n");
            }
        }

        Path reportPath = FMLPaths.GAMEDIR.get().resolve("villagelife")
                .resolve("persona-audit-" + startedAt + ".txt");
        try {
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, report.toString());
            source.sendSuccess(() -> Component.literal("Persona audit done: " + succeeded + "/" + count
                    + " spawned. Report: " + reportPath), true);
        } catch (IOException e) {
            Villagelife.LOGGER.error("Could not write persona audit report", e);
            source.sendSuccess(() -> Component.literal("Persona audit done: " + succeeded + "/" + count
                    + " spawned. Report could not be written (see log)."), true);
        }
    }
}
