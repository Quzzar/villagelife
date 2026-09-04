package com.quzzar.kithkyn.persona;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.entities.KithkynAttachments;
import com.quzzar.kithkyn.llm.LlmService;

/**
 * Persona generation, consuming the LLM via
 * {@link LlmService#submitPersona}: persona requests queue behind any pending
 * decide() and run one at a time, so a spawn burst cannot stall village
 * decisions. Callers follow the generate-before-spawn lifecycle (persona map
 * issue #4): generate first, and only spawn the entity into the world once the
 * future completes with a persona. An empty result means this arrival is
 * skipped, never a persona-less villager.
 */
public final class PersonaService {

    private static final int MAX_NEW_TOKENS = 120;
    private static final double TEMPERATURE = 0.5D;

    private PersonaService() {
    }

    /**
     * Generates a persona for a rolled-but-not-yet-spawned person. Completes
     * with empty on worker unavailability, timeout, or an unparseable
     * response; completion may happen off the server thread, so callers must
     * hop before touching level state.
     */
    /** One generation attempt's outcome; distinguishes retryable parse failures. */
    private record Attempt(Optional<PersonaData> persona, boolean parseFailed) {
    }

    public static CompletableFuture<Optional<PersonaData>> generateFor(RealPerson person) {
        long start = System.currentTimeMillis();
        // One retry on a parse failure (the 1B invents tag names a fair fraction
        // of the time even with the few-shot example); a worker-unavailable empty
        // is NOT retried, since the queue already waited as long as it could.
        return attempt(person, start).thenCompose(first -> {
            if (!first.parseFailed()) {
                return CompletableFuture.completedFuture(first.persona());
            }
            Kithkyn.LOGGER.debug("Retrying persona generation for {}", person.getFullName());
            return attempt(person, start).thenApply(Attempt::persona);
        });
    }

    private static CompletableFuture<Attempt> attempt(RealPerson person, long start) {
        String sheet = PersonaPrompts.buildSheet(person);
        // The example rides as a true few-shot turn, never concatenated into the
        // user message: a pasted example bleeds into outputs (villagers started
        // describing themselves as the example character, Doria Fenn).
        return LlmService.get().submitPersona("a persona for " + person.getFullName(), PersonaPrompts.SYSTEM, sheet,
                java.util.List.of(
                        new LlmService.FewShotExample(PersonaPrompts.EXAMPLE_IN, PersonaPrompts.EXAMPLE_OUT),
                        new LlmService.FewShotExample(PersonaPrompts.EXAMPLE_IN_2, PersonaPrompts.EXAMPLE_OUT_2)),
                MAX_NEW_TOKENS, TEMPERATURE)
                .thenApply(raw -> {
                    if (raw.isEmpty()) {
                        return new Attempt(Optional.empty(), false);
                    }
                    Optional<String[]> parsed = PersonaParser.parse(raw.get());
                    if (parsed.isEmpty()) {
                        Kithkyn.LOGGER.warn("Persona generation for {} produced unparseable output: {}",
                                person.getFullName(), raw.get().replace('\n', ' '));
                        return new Attempt(Optional.empty(), true);
                    }
                    long tookMs = System.currentTimeMillis() - start;
                    return new Attempt(Optional.of(new PersonaData(parsed.get()[0], parsed.get()[1],
                            "llama-3.2-1b", tookMs, PersonaPrompts.PROMPT_VERSION)), false);
                });
    }

    /** Attaches the generated persona to the entity (server thread only). */
    public static void attach(RealPerson person, PersonaData persona) {
        person.setData(KithkynAttachments.PERSONA, persona);
    }

    /** The persona carried by this person; {@link PersonaData#isEmpty()} when none. */
    public static PersonaData get(RealPerson person) {
        return person.getData(KithkynAttachments.PERSONA);
    }
}
