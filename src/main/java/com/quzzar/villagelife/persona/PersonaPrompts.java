package com.quzzar.villagelife.persona;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.Virtue;
import com.quzzar.villagelife.entities.genetics.GeneticCondition;
import com.quzzar.villagelife.entities.genetics.Stat;
import com.quzzar.villagelife.entities.genetics.StatBlock;

/**
 * Builds the persona prompt per the contract on persona map issue #2 (as
 * amended by issue #4: occupation is NOT an input; the blurb must not invent a
 * job, because jobs churn under the campfire model). Stats are verbalized as
 * words only, and only when notable; the genetic condition is always
 * mentioned. The one-shot example is load-bearing for tag compliance on the
 * 1B model (issue #3), so keep it when tuning wording.
 */
final class PersonaPrompts {

    /** Bump when the prompt meaningfully changes; recorded on each PersonaData. */
    static final int PROMPT_VERSION = 2;

    static final String SYSTEM = """
            You write character sheets for villagers in a medieval village. \
            Given a villager's traits, respond with EXACTLY two lines in this format:
            BLURB: <2-3 sentences describing the villager in third person. Mention every listed trait. Do not give them a job or occupation. If a trait is a weakness (weak, frail, clumsy, near-sighted, slow-witted), keep it a weakness; never flip it into a strength.>
            QUIRK: <one observable habit, under 15 words>
            Do not write anything else.""";

    static final String EXAMPLE_IN = "Name: Doria Fenn (female). Personality: cheerful. "
            + "Traits: notably strong, keen-eyed.";

    static final String EXAMPLE_OUT = """
            BLURB: Doria Fenn is a stocky, bright-tempered woman who hauls twice her share without complaint. Even at a distance she spots ripe berries before anyone else has noticed them.
            QUIRK: Waves at every passing chicken as if greeting an old friend.""";

    /** Low/high phrase pair for one stat, with stronger wording at the extremes. */
    private record PhrasePair(String low, String extremeLow, String high, String extremeHigh) {
    }

    private static PhrasePair phrases(Stat stat) {
        return switch (stat) {
            case STRENGTH -> new PhrasePair("notably weak", "cripplingly weak", "notably strong", "immensely strong");
            case DEXTERITY -> new PhrasePair("clumsy", "hopelessly clumsy", "nimble", "astonishingly nimble");
            case CONSTITUTION ->
                new PhrasePair("frail and often ill", "gravely frail", "hardy", "seemingly indestructible");
            case INTELLIGENCE -> new PhrasePair("slow-witted", "very slow-witted", "sharp-minded", "brilliant");
            case WISDOM -> new PhrasePair("rash with poor judgment", "recklessly rash", "wise", "profoundly wise");
            case CHARISMA ->
                new PhrasePair("awkward around others", "painfully awkward", "instantly likable", "magnetically charming");
            case SIZE -> new PhrasePair("short", "very short", "tall", "towering");
            case EYESIGHT -> new PhrasePair("near-sighted", "nearly blind", "keen-eyed", "exceptionally keen-eyed");
        };
    }

    private PersonaPrompts() {
    }

    /** The user-message character sheet for one person, per the contract. */
    static String buildSheet(RealPerson person) {
        List<String> traits = new ArrayList<>();

        StatBlock stats = person.getStatBlock();
        if (stats != null) {
            // The condition is always mentioned and overrides SIZE wording.
            GeneticCondition condition = stats.getCondition();
            if (condition == GeneticCondition.GIGANTISM) {
                traits.add("a true giant, towering over everyone");
            } else if (condition == GeneticCondition.DWARFISM) {
                traits.add("remarkably tiny");
            }

            // Contradictory pairs get reconciled into one phrase; handing a
            // small model "notably weak, hardy" side by side (STR 6, CON 14)
            // makes it drop or flip one of them (audit finding, prompt v2).
            java.util.EnumSet<Stat> reconciled = java.util.EnumSet.noneOf(Stat.class);
            if (stats.get(Stat.STRENGTH) <= 7 && stats.get(Stat.CONSTITUTION) >= 14) {
                traits.add("physically weak but never ill");
                reconciled.add(Stat.STRENGTH);
                reconciled.add(Stat.CONSTITUTION);
            } else if (stats.get(Stat.STRENGTH) >= 14 && stats.get(Stat.CONSTITUTION) <= 7) {
                traits.add("strong-armed but sickly");
                reconciled.add(Stat.STRENGTH);
                reconciled.add(Stat.CONSTITUTION);
            }
            if (stats.get(Stat.INTELLIGENCE) <= 7 && stats.get(Stat.WISDOM) >= 14) {
                traits.add("slow of thought but deeply sensible");
                reconciled.add(Stat.INTELLIGENCE);
                reconciled.add(Stat.WISDOM);
            } else if (stats.get(Stat.INTELLIGENCE) >= 14 && stats.get(Stat.WISDOM) <= 7) {
                traits.add("brilliant but utterly reckless");
                reconciled.add(Stat.INTELLIGENCE);
                reconciled.add(Stat.WISDOM);
            }

            for (Stat stat : Stat.values()) {
                if (reconciled.contains(stat)) {
                    continue;
                }
                if (stat == Stat.SIZE && condition != GeneticCondition.NONE) {
                    continue;
                }
                int score = stats.get(stat);
                PhrasePair pair = phrases(stat);
                if (score <= 4) {
                    traits.add(pair.extremeLow());
                } else if (score <= 7) {
                    traits.add(pair.low());
                } else if (score >= 17) {
                    traits.add(pair.extremeHigh());
                } else if (score >= 14) {
                    traits.add(pair.high());
                }
            }
        }

        addVirtueTrait(traits, person, Virtue.AGGRESSION, "quick-tempered", "gentle");
        addVirtueTrait(traits, person, Virtue.CURIOSITY, "endlessly curious", "incurious");
        addVirtueTrait(traits, person, Virtue.DRIVE, "driven", "unhurried");
        addVirtueTrait(traits, person, Virtue.PROTECT_OTHERS, "fiercely protective of others", "self-serving");
        addVirtueTrait(traits, person, Virtue.PROTECT_SELF, "cautious for their own skin", "recklessly bold");

        String traitText = traits.isEmpty() ? "unremarkable in every measurable way" : String.join(", ", traits);

        return "Name: " + person.getFullName()
                + " (" + person.getGender().name().toLowerCase(Locale.ROOT) + "). Personality: "
                + person.getPersonality().displayName() + ". Traits: " + traitText + ".";
    }

    /** Virtues run -0.5..0.5; only pronounced ones (|v| >= 0.25) read as traits. */
    private static void addVirtueTrait(List<String> traits, RealPerson person, Virtue virtue,
            String highPhrase, String lowPhrase) {
        float value = person.getVirtue(virtue);
        if (value >= 0.25F) {
            traits.add(highPhrase);
        } else if (value <= -0.25F) {
            traits.add(lowPhrase);
        }
    }
}
