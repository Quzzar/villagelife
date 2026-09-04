package com.quzzar.kithkyn.chat;

import java.util.List;

/** Builds a memory prompt from the counterpart's words, never the model's own claims. */
final class ConversationMemoryPrompt {

  private static final String[] VOLATILE_WORLD_STATE = {
      "build", "project", "saving", "save up", "short of", "material", "bed", "workplace",
      "job", "population", "stores", "finish"
  };

  record Prompt(String system, String user) {
  }

  private ConversationMemoryPrompt() {
  }

  static Prompt build(String personName, String counterpartName, String earlier,
      List<String> counterpartLines) {
    String groundedEarlier = withoutVolatileWorldState(earlier);
    String system = "You are " + personName + ". Write the subjective memory you will keep of "
        + counterpartName + " in two or three first-person sentences. Remember what they asked, requested, "
        + "promised, or personally revealed, and how the exchange left you feeling. Their words are claims, "
        + "not verified world facts: phrase a claim as something they said, and never turn a question into a fact. "
        + "Do not record current village buildings, construction, jobs, beds, or material totals in this memory; "
        + "those facts always come fresh from the simulation. "
        + (groundedEarlier.isBlank() ? "" : "Update the earlier subjective memory, retaining only relationship history "
            + "and what the counterpart said. Discard stale claims about current village state. ")
        + "Use only the counterpart's lines below and the earlier memory. Invent nothing. Write only the memory.";

    StringBuilder user = new StringBuilder();
    if (!groundedEarlier.isBlank()) {
      user.append("Earlier subjective memory:\n").append(groundedEarlier).append("\n\n");
    }
    user.append(counterpartName).append("'s lines in the conversation just now:\n");
    for (String line : counterpartLines) {
      if (!line.isBlank()) {
        user.append("- \"").append(line).append("\"\n");
      }
    }
    return new Prompt(system, user.toString());
  }

  /** Removes old summary sentences that purport to remember mutable simulation state. */
  static String withoutVolatileWorldState(String memory) {
    if (memory == null || memory.isBlank()) {
      return "";
    }
    StringBuilder kept = new StringBuilder();
    for (String sentence : memory.strip().split("(?<=[.!?])\\s+")) {
      String lower = sentence.toLowerCase(java.util.Locale.ROOT);
      boolean volatileFact = false;
      for (String marker : VOLATILE_WORLD_STATE) {
        if (lower.contains(marker)) {
          volatileFact = true;
          break;
        }
      }
      if (!volatileFact) {
        if (kept.length() > 0) {
          kept.append(' ');
        }
        kept.append(sentence.strip());
      }
    }
    return kept.toString();
  }
}
