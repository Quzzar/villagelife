package com.quzzar.kithkyn.relationships;

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
import com.quzzar.kithkyn.village.Village;
import com.quzzar.kithkyn.village.buildings.VillageContextSnapshot;

import net.minecraft.server.MinecraftServer;

/** A married pair deciding together whether they want a child now. */
public final class FamilyPlanningDialogue {

  public enum Decision {
    WANT_CHILD,
    NOT_NOW
  }

  private static final int MAX_TURNS = 6;
  private static final int MAX_TOKENS = 96;
  private static final double TEMPERATURE = 0.4D;
  private static final double PENALTY = 0.3D;

  private FamilyPlanningDialogue() {
  }

  /** Runs the visible two-parent conversation; no model means no forced decision. */
  public static CompletableFuture<Optional<Decision>> decide(
      Village village, RealPerson first, RealPerson second) {
    if (!LlmService.get().isReady()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    return Dialogue.run(new Planning(village, first, second));
  }

  private record ParsedTurn(String say, Optional<Boolean> wantsChild) {
  }

  private static final class Planning implements Dialogue.Protocol<Decision> {

    private final RealPerson first;
    private final RealPerson second;
    private final String firstName;
    private final String secondName;
    private final String villageContext;
    private final String firstSystem;
    private final String secondSystem;
    private Boolean firstChoice;
    private Boolean secondChoice;

    private Planning(Village village, RealPerson first, RealPerson second) {
      this.first = first;
      this.second = second;
      this.firstName = first.getFirstName();
      this.secondName = second.getFirstName();
      this.villageContext = VillageContextSnapshot.capture(village).chatBriefing();
      this.firstSystem = buildSystem(first, second);
      this.secondSystem = buildSystem(second, first);
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
    public CompletableFuture<Dialogue.Turn<Decision>> takeTurn(
        int speaker, Dialogue.Transcript transcript, boolean lastChance) {
      boolean firstSpeaks = speaker % 2 == 0;
      RealPerson person = firstSpeaks ? first : second;
      String name = firstSpeaks ? firstName : secondName;
      String system = firstSpeaks ? firstSystem : secondSystem;
      String user = buildUser(name, transcript, lastChance);
      String purpose = firstName + " and " + secondName + " discuss having a child";

      CompletableFuture<Dialogue.Turn<Decision>> turn = new CompletableFuture<>();
      LlmService.get().submitBackgroundChat(
          purpose, system, user, List.of(), MAX_TOKENS, TEMPERATURE, PENALTY)
          .whenComplete((reply, error) -> {
            MinecraftServer server = person.getServer();
            if (server == null) {
              turn.complete(Dialogue.Turn.abort());
              return;
            }
            server.execute(() -> {
              ParsedTurn parsed = error == null && reply != null && reply.isPresent()
                  ? parse(reply.get()) : null;
              if (parsed == null) {
                turn.complete(Dialogue.Turn.abort());
                return;
              }
              String say = VillagerText.clean(parsed.say());
              if (!say.isBlank()) {
                VillagerConversation.speak(person, say);
              }
              if (parsed.wantsChild().isPresent()) {
                if (firstSpeaks) {
                  firstChoice = parsed.wantsChild().get();
                } else {
                  secondChoice = parsed.wantsChild().get();
                }
                if (firstChoice != null && secondChoice != null) {
                  turn.complete(Dialogue.Turn.resolved(
                      firstChoice && secondChoice ? Decision.WANT_CHILD : Decision.NOT_NOW));
                  return;
                }
              }
              turn.complete(lastChance
                  ? Dialogue.Turn.abort()
                  : Dialogue.Turn.spoke(say.isBlank() ? "" : name + ": " + say));
            });
          });
      return turn;
    }

    private String buildSystem(RealPerson self, RealPerson partner) {
      StringBuilder system = new StringBuilder();
      system.append("You are ").append(self.getFullName()).append(", ")
          .append(self.getGender().describe()).append(", married to ")
          .append(partner.getFullName()).append(". ");
      PersonaData persona = self.getData(KithkynAttachments.PERSONA.get());
      if (!persona.isEmpty()) {
        system.append("About you: ").append(persona.blurb()).append(' ');
      }
      system.append("Your village has invited you and your spouse to decide whether you both want a child now. ")
          .append("Food, housing, deaths, work, and population are context for your personal decision, never rules that force it. ")
          .append("Speak as yourself in the first person, one short sentence per turn. ")
          .append("When you have decided for yourself, include your decision. Both spouses must independently want a child. ")
          .append("Reply with ONLY a JSON object: {\"say\":\"<what you say>\",\"decision\":{\"want_child\":true or false,\"reason\":\"<why, a few words>\"}}. ")
          .append("Leave out decision until you are ready to answer.");
      return system.toString();
    }

    private String buildUser(String name, Dialogue.Transcript transcript, boolean lastChance) {
      StringBuilder user = new StringBuilder(villageContext);
      user.append("\nYour family-planning talk so far:\n");
      if (transcript.isEmpty()) {
        user.append("(nothing said yet)\n");
      } else {
        for (String line : transcript.lines()) {
          user.append(line).append('\n');
        }
      }
      user.append("\nIt is your turn, ").append(name).append('.');
      if (lastChance) {
        user.append(" Give your own decision now.");
      }
      user.append(" Reply with ONLY the JSON object.");
      return user.toString();
    }

    private ParsedTurn parse(String raw) {
      int start = raw.indexOf('{');
      int end = raw.lastIndexOf('}');
      if (start < 0 || end <= start) {
        return null;
      }
      try {
        JsonObject root = JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
        String say = root.has("say") && root.get("say").isJsonPrimitive()
            ? root.get("say").getAsString() : "";
        JsonObject decision = root.has("decision") && root.get("decision").isJsonObject()
            ? root.getAsJsonObject("decision") : null;
        Optional<Boolean> wants = decision != null
            && decision.has("want_child") && decision.get("want_child").isJsonPrimitive()
                ? Optional.of(decision.get("want_child").getAsBoolean())
                : Optional.empty();
        return new ParsedTurn(say, wants);
      } catch (RuntimeException invalid) {
        Kithkyn.LOGGER.debug("[family] discarded malformed family-planning reply");
        return null;
      }
    }
  }
}
