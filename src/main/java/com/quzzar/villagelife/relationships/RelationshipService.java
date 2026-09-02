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
            Set asymmetric true only when the two genuinely see each other differently, which is \
            about one pair in ten; for every other pair it is false and the leans stay tiny. \
            These two have only just met, so keep value between -60 and 60: adoration and hatred \
            are earned over time, not assigned on arrival. \
            The flavor must be about the PAIR: what passes between them, what they share, what \
            grates. Never describe one villager on their own, and never write a line that would \
            still make sense if the other person did not exist. \
            Respond with ONLY a JSON object exactly like: \
            {"value": <-60 to 60>, "lean_a": <-15 to 15>, "lean_b": <-15 to 15>, "asymmetric": false, "flavor": "<one sentence about the two of them>"} \
            Higher value means fonder. Do not write anything else.""";

    private static final LlmService.FewShotExample PAIR_EXAMPLE = new LlmService.FewShotExample(
            """
            Person A: Doria Fenn (cheerful; notably strong, keen-eyed).
            Person B: Tam Reed (bubbly): hums to the chickens at dawn.""",
            "{\"value\": 35, \"lean_a\": 5, \"lean_b\": -3, \"asymmetric\": false, \"flavor\": \"Two warm souls who trade jokes over the fence most mornings.\"}");

    /** A cooler pair, to show that a low value still needs a line about BOTH of them. */
    private static final LlmService.FewShotExample PAIR_EXAMPLE_COOL = new LlmService.FewShotExample(
            """
            Person A: Miren Oak (cranky; slow of foot).
            Person B: Sella Vance (smug): counts every coin twice.""",
            "{\"value\": -20, \"lean_a\": -6, \"lean_b\": 2, \"asymmetric\": false, \"flavor\": \"They share a wall and an old argument about whose goats ruined whose garden.\"}");

    /** The strongest feeling generation may assign on the day two people meet. */
    private static final int GENERATED_LIMIT = 60;

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

        LlmService.get().submitPersona("who " + newcomer.getFullName() + " takes to in " + village.getName(),
                SELECTION_SYSTEM, prompt.toString(),
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
        LlmService.get().submitPersona(newcomer.getFullName() + " and " + candidate.fullName() + " size each other up",
                PAIR_SYSTEM, prompt, List.of(PAIR_EXAMPLE, PAIR_EXAMPLE_COOL),
                PAIR_TOKENS, TEMPERATURE)
                .thenAccept(raw -> raw.ifPresent(text -> level.getServer().execute(() -> {
                    RelationshipPair pair = parsePair(newcomer.getUUID(), candidate.id(), text);
                    if (pair == null) {
                        Villagelife.LOGGER.debug("Discarded malformed pair output for {} and {}",
                                newcomer.getFullName(), candidate.fullName());
                        return;
                    }
                    pair = withoutBiography(pair, newcomer.getFullName(), candidate.fullName());
                    if (Math.abs(pair.value()) < RelationshipPair.NEUTRAL_BAND) {
                        return; // neutral pairs are represented by absence
                    }
                    village.putRelationship(pair);
                    Villagelife.LOGGER.debug("Relationship: {} and {} at {} ({})", newcomer.getFullName(),
                            candidate.fullName(), pair.value(), pair.flavor());
                })));
    }

    /** Lenient JSON extraction; any structural or range violation discards the pair. */
    /**
     * A flavour line that names one of the two and not the other is a
     * biography that happened to be stored on a relationship, which the audit
     * on #21 found in most pairs. Better to keep the numbers and drop the
     * sentence than to let a villager describe their neighbour to themselves.
     */
    private static RelationshipPair withoutBiography(RelationshipPair pair, String nameA, String nameB) {
        String flavor = pair.flavor();
        if (flavor.isBlank()) {
            return pair;
        }
        boolean mentionsA = mentionsAnyPartOf(flavor, nameA);
        boolean mentionsB = mentionsAnyPartOf(flavor, nameB);
        if (mentionsA == mentionsB) {
            return pair;
        }
        Villagelife.LOGGER.debug("Dropped a one-sided flavour line: \"{}\"", flavor);
        return new RelationshipPair(pair.personA(), pair.personB(), pair.value(),
                pair.leanA(), pair.leanB(), pair.asymmetric(), "", pair.married());
    }

    private static boolean mentionsAnyPartOf(String flavor, String fullName) {
        for (String part : fullName.split(" +")) {
            if (part.length() > 2 && flavor.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static RelationshipPair parsePair(UUID a, UUID b, String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
            int value = json.get("value").getAsInt();
            // Generation meets people on their first day, so it may not hand out
            // devotion or loathing; those are earned by drift and by what
            // happens between them (#72).
            value = net.minecraft.util.Mth.clamp(value, -GENERATED_LIMIT, GENERATED_LIMIT);
            if (value < -100 || value > 100) {
                return null;
            }
            int leanA = json.has("lean_a") ? json.get("lean_a").getAsInt() : 0;
            int leanB = json.has("lean_b") ? json.get("lean_b").getAsInt() : 0;
            boolean asymmetric = json.has("asymmetric") && json.get("asymmetric").getAsBoolean();
            // The flag only earns its name when the two leans actually diverge:
            // a model that marks asymmetric while giving both people the same
            // small lean has described a symmetric pair (#72). Believing it
            // there would make asymmetry meaningless wherever it appears.
            asymmetric = asymmetric && Math.abs(leanA - leanB) > RelationshipPair.LEAN_LIMIT;
            String flavor = json.has("flavor") ? json.get("flavor").getAsString() : "";
            return RelationshipPair.create(a, b, value, leanA, leanB, asymmetric, flavor);
        } catch (Exception e) {
            return null;
        }
    }
}
