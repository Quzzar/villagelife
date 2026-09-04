package com.quzzar.kithkyn.persona;

import java.util.Locale;
import java.util.Optional;

/**
 * Lenient parser for the persona wire format decided on persona map issue #2
 * and refined by the prototype (issue #3): two tagged lines, BLURB: and
 * QUIRK:. Leniency requirements observed from real model output: tags matched
 * case-insensitively, leading markdown/decoration stripped (the model sometimes
 * emits "**BLURB**:"), and stray lines before/between the tagged lines are
 * ignored. Anything without both tags is a failed generation.
 */
final class PersonaParser {

    private PersonaParser() {
    }

    /** Returns the (blurb, quirk) pair, or empty if either tag is missing or blank. */
    static Optional<String[]> parse(String raw) {
        String blurb = null;
        String quirk = null;
        for (String line : raw.split("\\R")) {
            String cleaned = line.strip().replaceAll("^[*_>#\\-\\s]+", "");
            String lower = cleaned.toLowerCase(Locale.ROOT);
            if (lower.startsWith("blurb")) {
                String value = stripTag(cleaned);
                if (blurb == null && !value.isBlank()) {
                    blurb = value;
                }
            } else if (lower.startsWith("quirk")) {
                String value = stripTag(cleaned);
                if (quirk == null && !value.isBlank()) {
                    quirk = value;
                }
            }
        }
        if (blurb == null || quirk == null) {
            return Optional.empty();
        }
        return Optional.of(new String[] { blurb, quirk });
    }

    /** Drops the tag word plus any decoration between it and the content ("BLURB**: text" -> "text"). */
    private static String stripTag(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return "";
        }
        return line.substring(colon + 1).strip();
    }
}
