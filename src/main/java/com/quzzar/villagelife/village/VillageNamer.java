package com.quzzar.villagelife.village;

import java.util.Locale;
import java.util.Optional;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.llm.LlmService;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Names a village at founding (campfire map #60, decided by Aaron: LLM-named
 * only, no rename mechanism). The request rides the low-priority persona
 * queue so founding never blocks: the village starts under a deterministic
 * word-list name and the generated name lands moments later, before anyone
 * has usually arrived. One retry on unparseable output, then the word-list
 * name simply stands.
 */
public final class VillageNamer {

  /** Assembled fallback parts; combined they read as plausible settlement names. */
  private static final String[] FIRST = {
      "Ash", "Oak", "Stone", "Brook", "Fair", "Green", "Wolf", "Amber", "Frost", "Elm"};
  private static final String[] SECOND = {
      "field", "bury", "haven", "stead", "wick", "gate", "hollow", "march", "ford", "crest"};

  private static final int MAX_NAME_LENGTH = 32;

  private VillageNamer() {
  }

  /** A deterministic word-list name from the village id; never fails. */
  public static String fallbackName(String villageId) {
    int hash = villageId.hashCode();
    return FIRST[Math.floorMod(hash, FIRST.length)] + SECOND[Math.floorMod(hash / 31, SECOND.length)];
  }

  /** Requests the founding name async; applies it (with a resident sweep) when it lands. */
  public static void nameNewVillage(ServerLevel level, Village village, BlockPos center) {
    String biome = level.getBiome(center).unwrapKey()
        .map(key -> key.location().getPath().replace('_', ' '))
        .orElse("plains");
    String system = "You name new settlements in a medieval fantasy world."
        + " Reply with ONLY the settlement name: one to three words, letters and spaces only,"
        + " no quotes and no explanation.";
    String user = "A handful of settlers have raised a campfire and founded a tiny new camp in a "
        + biome + " landscape. Name the settlement.";
    request(level, village, system, user, true);
  }

  private static void request(ServerLevel level, Village village, String system, String user, boolean mayRetry) {
    LlmService.get().submitPersona(system, user, 16, 0.7)
        .thenAccept(reply -> level.getServer().execute(() -> {
          Optional<String> name = reply.flatMap(VillageNamer::parse);
          if (name.isPresent()) {
            village.applyName(name.get());
            Villagelife.LOGGER.info("The new village is named '{}'", name.get());
          } else if (mayRetry) {
            Villagelife.LOGGER.debug("Village name generation produced unusable output; retrying once");
            request(level, village, system, user, false);
          } else {
            Villagelife.LOGGER.info("Village name generation failed twice; '{}' stands", village.getName());
          }
        }));
  }

  /** First line, unquoted, at most three words of letters; empty when unusable. */
  private static Optional<String> parse(String raw) {
    String candidate = raw.strip().split("\\R", 2)[0].strip();
    if (candidate.length() >= 2
        && (candidate.charAt(0) == '"' || candidate.charAt(0) == '\'')
        && candidate.charAt(candidate.length() - 1) == candidate.charAt(0)) {
      candidate = candidate.substring(1, candidate.length() - 1).strip();
    }
    if (candidate.endsWith(".")) {
      candidate = candidate.substring(0, candidate.length() - 1).strip();
    }
    if (candidate.isEmpty() || candidate.length() > MAX_NAME_LENGTH) {
      return Optional.empty();
    }
    String[] words = candidate.split(" +");
    if (words.length > 3) {
      return Optional.empty();
    }
    if (!candidate.matches("[\\p{L}][\\p{L}' -]*")) {
      return Optional.empty();
    }
    // Settlement names read as proper nouns; a lowercase reply gets title case.
    StringBuilder titled = new StringBuilder(candidate.length());
    for (int i = 0; i < words.length; i++) {
      if (i > 0) {
        titled.append(' ');
      }
      titled.append(Character.toUpperCase(words[i].charAt(0)))
          .append(words[i].substring(1));
    }
    String result = titled.toString();
    if (result.toLowerCase(Locale.ROOT).startsWith("the ") && words.length == 1) {
      return Optional.empty();
    }
    return Optional.of(result);
  }

}
