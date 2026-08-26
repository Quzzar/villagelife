package com.quzzar.villagelife.relationships;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.persona.PersonaData;
import com.quzzar.villagelife.persona.PersonaService;
import com.quzzar.villagelife.village.Village;

import net.minecraft.server.level.ServerLevel;

/**
 * The two-stage newcomer integration pass (persona map issue #5), run exactly
 * once per villager at arrival, riding the same low-priority LLM queue as
 * personas so decisions and chat always preempt it.
 *
 * Stage 1 (selection): the roster is pre-filtered to at most
 * {@link #MAX_CANDIDATES} one-line descriptors (coworkers first) and the model
 * names up to {@link #MAX_SELECTIONS} residents the newcomer would form
 * opinions about; names are fuzzy-matched back against the real roster and
 * unmatched ones dropped.
 *
 * Stage 2 (one call per selected pair): both personas go in, ONE object comes
 * out - {@code {"value", "lean_a", "lean_b", "asymmetric", "flavor"}} - so the
 * roughly-shared-opinion invariant holds by construction. Malformed or
 * out-of-band output discards that pair, decide-style. Near-neutral pairs are
 * not stored; opinions are static once written (v1).
 */
public final class RelationshipService {

    private static final int MAX_CANDIDATES = 12;
    private static final int MAX_SELECTIONS = 5;
    private static final int SELECTION_TOKENS = 60;
    private static final int PAIR_TOKENS = 140;
    private static final double TEMPERATURE = 0.6D;

    private static final String SELECTION_SYSTEM = """
            You know everyone in a medieval village. A newcomer has just arrived. \
            From the resident list, choose up to 5 residents the newcomer would most plausibly \
            form a real opinion about (or who would form one about them). \
            Respond with ONLY the chosen residents' full names, one per line. No other text.""";

    private static final LlmService.FewShotExample SELECTION_EXAMPLE = new LlmService.FewShotExample(
            """
            Newcomer: Doria Fenn (female, cheerful; notably strong, keen-eyed).
            Residents:
            - Edda Sandoval (cranky): taps every doorframe twice before walking through
            - Tam Reed (bubbly): hums to the chickens at dawn
            - Osgood Vale (secluded): eats alone facing the wall""",
            "Edda Sandoval\nTam Reed");

    private static final String PAIR_SYSTEM = """
            You judge how two villagers in a medieval village feel about each other. \
            Their opinions of each other are usually close: a shared value with small personal leans. \
            Rarely, mark asymmetric true when one plausibly feels quite differently. \
            Respond with ONLY a JSON object exactly like: \
            {"value": <-100 to 100>, "lean_a": <-15 to 15>, "lean_b": <-15 to 15>, "asymmetric": false, "flavor": "<one sentence why>"} \
            Higher value means fonder. Do not write anything else.""";

    private static final LlmService.FewShotExample PAIR_EXAMPLE = new LlmService.FewShotExample(
            """
            Person A: Doria Fenn (cheerful; notably strong, keen-eyed).
            Person B: Tam Reed (bubbly): hums to the chickens at dawn.""",
            "{\"value\": 35, \"lean_a\": 5, \"lean_b\": -3, \"asymmetric\": false, \"flavor\": \"Two warm souls who trade jokes over the fence most mornings.\"}");

    private RelationshipService() {
    }

    /**
     * Fire-and-forget integration for a freshly-confirmed arrival. Safe to call
     * from the server thread; all village mutations hop back to it.
     */
    public static void integrateNewcomer(Village village, ServerLevel level, RealPerson newcomer) {
        List<Candidate> roster = buildRoster(village, level, newcomer.getUUID());
        if (roster.isEmpty()) {
            return; // founders precede everyone; later arrivals create their pairs
        }
        String newcomerLine = describe(newcomer);

        StringBuilder prompt = new StringBuilder("Newcomer: ").append(newcomerLine).append(".\nResidents:\n");
        for (Candidate candidate : roster) {
            prompt.append("- ").append(candidate.descriptor()).append('\n');
        }

        LlmService.get().submitPersona(SELECTION_SYSTEM, prompt.toString(),
                List.of(SELECTION_EXAMPLE), SELECTION_TOKENS, TEMPERATURE)
                .thenAccept(raw -> raw.ifPresent(text -> level.getServer().execute(
                        () -> onSelection(village, level, newcomer, roster, text))));
    }

    private record Candidate(UUID id, String fullName, String descriptor) {
    }

    /** At most MAX_CANDIDATES loaded residents, coworkers of the newcomer first. */
    private static List<Candidate> buildRoster(Village village, ServerLevel level, UUID newcomerId) {
        List<Candidate> coworkers = new ArrayList<>();
        List<Candidate> others = new ArrayList<>();
        for (UUID personId : village.getPopulation()) {
            if (personId.equals(newcomerId)) {
                continue;
            }
            RealPerson person = village.getPerson(level, personId);
            if (person == null) {
                continue;
            }
            Candidate candidate = new Candidate(personId, person.getFullName(), describe(person));
            var newcomerJob = village.getJobAssignment(newcomerId);
            var theirJob = village.getJobAssignment(personId);
            boolean coworker = newcomerJob != null && theirJob != null
                    && newcomerJob.getOccupation() == theirJob.getOccupation();
            (coworker ? coworkers : others).add(candidate);
        }
        java.util.Collections.shuffle(others);
        List<Candidate> roster = new ArrayList<>(coworkers);
        for (Candidate candidate : others) {
            if (roster.size() >= MAX_CANDIDATES) {
                break;
            }
            roster.add(candidate);
        }
        return roster.size() > MAX_CANDIDATES ? roster.subList(0, MAX_CANDIDATES) : roster;
    }

    /** One line per person: name, personality, and their quirk when a persona exists. */
    private static String describe(RealPerson person) {
        PersonaData persona = PersonaService.get(person);
        String tail = persona.isEmpty() ? "" : ": " + persona.quirk();
        return person.getFullName() + " (" + person.getPersonality().displayName() + ")" + tail;
    }

    private static void onSelection(Village village, ServerLevel level, RealPerson newcomer,
            List<Candidate> roster, String rawSelection) {
        List<Candidate> selected = new ArrayList<>();
        for (String line : rawSelection.split("\\R")) {
            String cleaned = normalize(line.strip().replaceAll("^[-*\\d.\\s]+", ""));
            if (cleaned.isBlank() || selected.size() >= MAX_SELECTIONS) {
                continue;
            }
            Candidate match = matchCandidate(cleaned, roster, selected);
            if (match != null) {
                selected.add(match);
            }
        }
        if (selected.isEmpty()) {
            Villagelife.LOGGER.debug("Relationship selection for {} matched no residents (raw: {})",
                    newcomer.getFullName(), rawSelection.replace('\n', ' '));
            return;
        }
        for (Candidate candidate : selected) {
            requestPair(village, level, newcomer, candidate);
        }
    }

    /**
     * Small models mangle names ("Anselm Mu" for Anselm Muñoz, bare surnames),
     * so matching runs on diacritic-stripped lowercase with fallbacks: full-name
     * containment first, then a unique last-name match, then a unique first-name
     * match. Ambiguous fallbacks match nobody rather than guessing.
     */
    private static Candidate matchCandidate(String cleaned, List<Candidate> roster, List<Candidate> selected) {
        for (Candidate candidate : roster) {
            String full = normalize(candidate.fullName());
            if (!selected.contains(candidate) && (cleaned.contains(full) || full.contains(cleaned))) {
                return candidate;
            }
        }
        Candidate lastNameMatch = uniqueNamePartMatch(cleaned, roster, selected, false);
        if (lastNameMatch != null) {
            return lastNameMatch;
        }
        return uniqueNamePartMatch(cleaned, roster, selected, true);
    }

    private static Candidate uniqueNamePartMatch(String cleaned, List<Candidate> roster, List<Candidate> selected,
            boolean firstName) {
        Candidate found = null;
        for (Candidate candidate : roster) {
            if (selected.contains(candidate)) {
                continue;
            }
            String[] parts = normalize(candidate.fullName()).split("\\s+");
            String part = firstName ? parts[0] : parts[parts.length - 1];
            if (!part.isBlank() && cleaned.contains(part)) {
                if (found != null) {
                    return null; // ambiguous: two candidates share the name part
                }
                found = candidate;
            }
        }
        return found;
    }

    /** Lowercase with diacritics stripped ("Muñoz" -> "munoz"). */
    private static String normalize(String text) {
        String decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private static void requestPair(Village village, ServerLevel level, RealPerson newcomer, Candidate candidate) {
        String prompt = "Person A: " + describe(newcomer) + ".\nPerson B: " + candidate.descriptor() + ".";
        LlmService.get().submitPersona(PAIR_SYSTEM, prompt, List.of(PAIR_EXAMPLE), PAIR_TOKENS, TEMPERATURE)
                .thenAccept(raw -> raw.ifPresent(text -> level.getServer().execute(() -> {
                    RelationshipPair pair = parsePair(newcomer.getUUID(), candidate.id(), text);
                    if (pair == null) {
                        Villagelife.LOGGER.debug("Discarded malformed pair output for {} and {}",
                                newcomer.getFullName(), candidate.fullName());
                        return;
                    }
                    if (Math.abs(pair.value()) < RelationshipPair.NEUTRAL_BAND) {
                        return; // neutral pairs are represented by absence
                    }
                    village.putRelationship(pair);
                    Villagelife.LOGGER.debug("Relationship: {} and {} at {} ({})", newcomer.getFullName(),
                            candidate.fullName(), pair.value(), pair.flavor());
                })));
    }

    /** Lenient JSON extraction; any structural or range violation discards the pair. */
    private static RelationshipPair parsePair(UUID a, UUID b, String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
            int value = json.get("value").getAsInt();
            if (value < -100 || value > 100) {
                return null;
            }
            int leanA = json.has("lean_a") ? json.get("lean_a").getAsInt() : 0;
            int leanB = json.has("lean_b") ? json.get("lean_b").getAsInt() : 0;
            boolean asymmetric = json.has("asymmetric") && json.get("asymmetric").getAsBoolean();
            String flavor = json.has("flavor") ? json.get("flavor").getAsString() : "";
            return RelationshipPair.create(a, b, value, leanA, leanB, asymmetric, flavor);
        } catch (Exception e) {
            return null;
        }
    }
}
