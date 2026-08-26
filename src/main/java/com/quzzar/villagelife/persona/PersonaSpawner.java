package com.quzzar.villagelife.persona;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.quzzar.villagelife.PersonEntityType;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.genetics.StatBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;

/**
 * THE generate-before-spawn pipeline (persona map issue #4), shared by the
 * audit command and the campfire arrival check: roll a complete person
 * (genetics in the constructor, identity in finalizeSpawn) WITHOUT adding them
 * to the world, generate their persona, and only spawn them once it arrives.
 * A failed generation discards the rolled person entirely; a villager can
 * never exist in the world without a persona.
 */
public final class PersonaSpawner {

    /**
     * Everything one attempt produced. {@code spawned} is present only on
     * success; on failure the rolled identity (name, sheet, stats) is still
     * reported so callers can log the skipped arrival, but the entity behind
     * it has been discarded and must not be used.
     */
    public record SpawnAttempt(String name, String sheet, StatBlock stats, Optional<RealPerson> spawned,
            Optional<PersonaData> persona) {
    }

    private PersonaSpawner() {
    }

    /**
     * Runs one full attempt. Must be called on the server thread; the returned
     * future also completes on the server thread. {@code configure} runs after
     * identity generation and before persona generation, so callers can set
     * village membership or other pre-spawn state.
     */
    public static CompletableFuture<SpawnAttempt> trySpawn(ServerLevel level, BlockPos pos,
            Consumer<RealPerson> configure) {
        RealPerson person = PersonEntityType.PERSON.get().create(level);
        if (person == null) {
            Villagelife.LOGGER.error("Could not create a person entity for a persona spawn attempt");
            return CompletableFuture.completedFuture(new SpawnAttempt("", "", null, Optional.empty(), Optional.empty()));
        }
        person.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0F, 0F);
        person.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.COMMAND, null);
        configure.accept(person);

        String name = person.getFullName();
        String sheet = PersonaPrompts.buildSheet(person);
        StatBlock stats = person.getStatBlock();

        CompletableFuture<SpawnAttempt> attempt = new CompletableFuture<>();
        PersonaService.generateFor(person).whenComplete((result, error) -> level.getServer().execute(() -> {
            Optional<PersonaData> persona = (error != null || result == null) ? Optional.empty() : result;
            if (error != null) {
                Villagelife.LOGGER.error("Persona generation errored for {}", name, error);
            }
            if (persona.isPresent()) {
                PersonaService.attach(person, persona.get());
                level.addFreshEntity(person);
                attempt.complete(new SpawnAttempt(name, sheet, stats, Optional.of(person), persona));
            } else {
                // Skipped arrival: the rolled person never enters the world.
                person.discard();
                Villagelife.LOGGER.info("Arrival skipped: persona generation failed for {}", name);
                attempt.complete(new SpawnAttempt(name, sheet, stats, Optional.empty(), Optional.empty()));
            }
        }));
        return attempt;
    }
}
