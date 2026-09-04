package com.quzzar.kithkyn.relationships;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.chat.Dialogue;
import com.quzzar.kithkyn.chat.VillagerConversation;
import com.quzzar.kithkyn.chat.VillagerText;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.entities.KithkynAttachments;
import com.quzzar.kithkyn.llm.LlmService;
import com.quzzar.kithkyn.persona.PersonaData;

import net.minecraft.server.MinecraftServer;

/**
 * The married name a couple takes, settled by the couple, on the shared
 * {@link Dialogue} engine (docs/marriage.md, docs/conversations.md). Rather than
 * a rule choosing the surname, the brain convenes the two betrothed villagers,
 * they talk it over as themselves, and the way to end the talk is a valid choice:
 * a reply that names the household. This is the pattern Aaron keeps reaching for,
 * "just ask the villager", applied to a decision that is genuinely theirs.
 *
 * <p>The choice is constrained to what a marriage can sensibly make of two
 * names: keep one, take the other, or join them hyphenated (in either order).
 * The model is shown those exact options and its answer is validated against
 * them, so no wild surname can slip through. A talk that never lands a valid
 * choice resolves to nothing, and {@code MarriageService} weds the pair with its
 * hyphenation fallback instead: the couple's voice is honoured when they use it,
 * never a marriage blocked when they do not.
 *
 * <p>Turns ride the background LLM lane and are spoken aloud to any players in
 * earshot, so the deliberation is a scene in the world, not a hidden roll. Each
 * turn's world-touching work (speaking a line) hops to the server thread; the
 * prompts read only fixed strings snapshotted when the talk was convened, so a
 * turn's own prompt building is safe off the server thread.
 */
public final class MarriageNaming {

  /** Up to three turns each before the pair must conclude; a name is a small decision, not a debate. */
  private static final int MAX_TURNS = 6;

  /** Room for a short line plus the small decision object; a naming reply is never long. */
  private static final int NAMING_MAX_TOKENS = 96;

  /** Warm enough for character, low enough to keep the JSON intact on a small model. */
  private static final double NAMING_TEMPERATURE = 0.4D;

  /** A mild push off words just used, the same anti-repeat nudge conversation uses. */
  private static final double NAMING_PENALTY = 0.3D;

  private MarriageNaming() {
  }

  /**
   * Runs the couple's naming talk and completes with the surname they settle on,
   * or empty when the LLM is down or their talk lands no valid choice (the caller
   * then falls back to a hyphenation). Call on the server thread: the talk's
   * fixed facts are read from the pair as it starts.
   */
  public static CompletableFuture<Optional<String>> chooseSurname(RealPerson a, RealPerson b) {
    if (!LlmService.get().isReady()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    return Dialogue.run(new Naming(a, b));
  }

  /** A parsed reply: what the villager said, and the household name they named, if they named a valid one. */
  private record ParsedTurn(String say, Optional<String> surname) {
  }

  /**
   * The two betrothed as a {@link Dialogue.Protocol}: they take turns as
   * themselves, and the first valid household name resolves the talk. All the
   * prompt strings are built once, when the talk is convened, so a turn reads no
   * live entity state; only speaking a line touches the world, and that hops to
   * the server thread.
   */
  private static final class Naming implements Dialogue.Protocol<String> {

    private final RealPerson a;
    private final RealPerson b;
    private final String aFirst;
    private final String bFirst;
    private final String villageName;
    private final List<String> allowedSurnames;
    private final String systemA;
    private final String systemB;

    private Naming(RealPerson a, RealPerson b) {
      this.a = a;
      this.b = b;
      this.aFirst = a.getFirstName();
      this.bFirst = b.getFirstName();
      this.villageName = a.getVillageName();
      String aSurname = a.getLastName();
      String bSurname = b.getLastName();
      List<String> allowed = new ArrayList<>();
      addUnique(allowed, aSurname);
      addUnique(allowed, bSurname);
      addUnique(allowed, aSurname + "-" + bSurname);
      addUnique(allowed, bSurname + "-" + aSurname);
      this.allowedSurnames = List.copyOf(allowed);
      this.systemA = buildSystem(a, b, aSurname, bSurname);
      this.systemB = buildSystem(b, a, bSurname, aSurname);
    }

    @Override
    public int voices() {
      return 2;
    }

    @Override
    public int maxTurns() {
      return MAX_TURNS;
    }

    @Override
    public CompletableFuture<Dialogue.Turn<String>> takeTurn(int speaker, Dialogue.Transcript transcript,
        boolean lastChance) {
      boolean aSpeaks = speaker % 2 == 0;
      RealPerson person = aSpeaks ? a : b;
      String first = aSpeaks ? aFirst : bFirst;
      String system = aSpeaks ? systemA : systemB;
      String user = buildUser(first, transcript, lastChance);
      String purpose = aFirst + " and " + bFirst + " name their household";

      CompletableFuture<Dialogue.Turn<String>> turn = new CompletableFuture<>();
      LlmService.get()
          .submitBackgroundChat(purpose, system, user, List.of(), NAMING_MAX_TOKENS, NAMING_TEMPERATURE, NAMING_PENALTY)
          .whenComplete((reply, error) -> {
            MinecraftServer server = person.getServer();
            if (server == null) {
              turn.complete(Dialogue.Turn.abort());
              return;
            }
            server.execute(() -> {
              ParsedTurn parsed = (error != null || reply == null || reply.isEmpty())
                  ? null
                  : parse(reply.get());
              if (parsed == null) {
                // A dead or unreadable turn ends the talk; the wedding falls
                // back to a hyphenation rather than press a silent model.
                turn.complete(Dialogue.Turn.abort());
                return;
              }
              String say = VillagerText.clean(parsed.say());
              if (!say.isBlank()) {
                VillagerConversation.speak(person, say);
              }
              if (parsed.surname().isPresent()) {
                Kithkyn.LOGGER.info("[marriage naming] {} settles on the '{}' household",
                    person.getFullName(), parsed.surname().get());
                turn.complete(Dialogue.Turn.resolved(parsed.surname().get()));
                return;
              }
              if (lastChance) {
                turn.complete(Dialogue.Turn.abort());
                return;
              }
              // Continue: the other speaks next, and the transcript carries this
              // line, attributed, so each sees what the other has said.
              turn.complete(Dialogue.Turn.spoke(say.isBlank() ? "" : first + ": " + say));
            });
          });
      return turn;
    }

    /** The speaker's briefing: who they are, the choice before them, and the exact names they may pick. */
    private String buildSystem(RealPerson self, RealPerson partner, String yourSurname, String theirSurname) {
      StringBuilder system = new StringBuilder();
      system.append("You are ").append(self.getFullName()).append(", ").append(self.getGender().describe())
          .append(", soon to marry ").append(partner.getFullName()).append(". ");
      PersonaData persona = self.getData(KithkynAttachments.PERSONA.get());
      if (!persona.isEmpty()) {
        system.append("About you: ").append(persona.blurb()).append(' ');
      }
      system.append("The two of you are choosing the family name your household will take. You may keep your name (")
          .append(yourSurname).append("), take ").append(partner.getFirstName()).append("'s (").append(theirSurname)
          .append("), or join them (").append(yourSurname).append('-').append(theirSurname).append(" or ")
          .append(theirSurname).append('-').append(yourSurname)
          .append("). Speak as yourself, in the first person, one short sentence a turn. When you and ")
          .append(partner.getFirstName()).append(" have agreed, state the name in a decision. ")
          .append("Reply with ONLY a JSON object: {\"say\":\"<what you say>\",")
          .append("\"decision\":{\"surname\":\"<the household name>\",\"reason\":\"<why, a few words>\"}}. ")
          .append("Leave out \"decision\" until you both agree. The surname must be exactly one of: ")
          .append(quoted(allowedSurnames)).append('.');
      return system.toString();
    }

    /** The turn's cue: the scene, the talk so far, and whose turn it is; the last turn insists on a decision. */
    private String buildUser(String yourFirst, Dialogue.Transcript transcript, boolean lastChance) {
      StringBuilder user = new StringBuilder();
      user.append(villageName).append(" is about to see you wed; settle your married name together.\n\n");
      user.append("Your talk so far:\n");
      if (transcript.isEmpty()) {
        user.append("(nothing said yet)\n");
      } else {
        for (String line : transcript.lines()) {
          user.append(line).append('\n');
        }
      }
      user.append("\nIt is your turn, ").append(yourFirst).append('.');
      if (lastChance) {
        user.append(" You have talked enough; include your \"decision\" now with the name you both take.");
      }
      user.append(" Reply with ONLY the JSON object.");
      return user.toString();
    }

    /** The chosen surname if it is exactly one of the allowed forms (case-insensitive), in its canonical spelling. */
    private Optional<String> validate(String candidate) {
      if (candidate == null) {
        return Optional.empty();
      }
      String trimmed = candidate.strip();
      for (String allowed : allowedSurnames) {
        if (allowed.equalsIgnoreCase(trimmed)) {
          return Optional.of(allowed);
        }
      }
      return Optional.empty();
    }

    /**
     * Reads a reply: the strict-then-lenient discipline used across llm-land, the
     * spoken line from {@code say} and the choice from {@code decision.surname}
     * (or a bare top-level {@code surname}, wherever a small model puts it). Null
     * when nothing parses, which ends the talk.
     */
    private ParsedTurn parse(String raw) {
      int start = raw.indexOf('{');
      int end = raw.lastIndexOf('}');
      if (start < 0 || end <= start) {
        return null;
      }
      try {
        JsonObject node = JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
        String say = node.has("say") && node.get("say").isJsonPrimitive() ? node.get("say").getAsString() : "";
        Optional<String> surname = Optional.empty();
        if (node.has("decision") && node.get("decision").isJsonObject()) {
          JsonObject decision = node.getAsJsonObject("decision");
          if (decision.has("surname") && decision.get("surname").isJsonPrimitive()) {
            surname = validate(decision.get("surname").getAsString());
          }
        }
        if (surname.isEmpty() && node.has("surname") && node.get("surname").isJsonPrimitive()) {
          surname = validate(node.get("surname").getAsString());
        }
        return new ParsedTurn(say, surname);
      } catch (RuntimeException e) {
        return null;
      }
    }
  }

  /** Adds a surname to the option list if it is non-blank and not already there (a shared name collapses the options). */
  private static void addUnique(List<String> list, String surname) {
    String trimmed = surname == null ? "" : surname.strip();
    if (!trimmed.isEmpty() && list.stream().noneMatch(existing -> existing.equalsIgnoreCase(trimmed))) {
      list.add(trimmed);
    }
  }

  /** The allowed names as a quoted, comma-separated list for the prompt. */
  private static String quoted(List<String> names) {
    List<String> shown = new ArrayList<>();
    for (String name : names) {
      shown.add('"' + name + '"');
    }
    return String.join(", ", shown);
  }
}
