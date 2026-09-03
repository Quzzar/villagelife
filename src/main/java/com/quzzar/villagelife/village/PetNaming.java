package com.quzzar.villagelife.village;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.chat.Dialogue;
import com.quzzar.villagelife.chat.VillagerConversation;
import com.quzzar.villagelife.chat.VillagerText;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.VillagelifeAttachments;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.persona.PersonaData;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.DyeColor;

/**
 * The name, collar, and coat a villager gives a new companion pet, settled by the
 * villager on the shared {@link Dialogue} engine, the same "just ask the villager"
 * pattern as {@link com.quzzar.villagelife.relationships.MarriageNaming}. Rather
 * than a rule rolling the details, the owner speaks as themselves about the animal
 * they have just been given and names it; the way to end the talk is a valid
 * choice.
 *
 * <p>The choice is constrained to what actually exists in game: the sixteen dye
 * colours for the collar, and the coat variants the registry holds for the
 * species. The model is shown those exact options and its answer is validated
 * against them, so nothing invalid can slip through. A talk that never lands a
 * valid choice resolves to nothing, and the caller keeps the random defaults the
 * pet already wears: the owner's voice is honoured when they use it, never a pet
 * left nameless when they do not.
 *
 * <p>One voice, at most two turns: naming a pet is a small, private decision, not
 * a debate. Turns ride the background LLM lane and are spoken aloud to any players
 * in earshot, so the naming is a scene in the world. Each turn's world-touching
 * work (speaking a line) hops to the server thread; the prompts read only fixed
 * strings snapshotted when the talk was convened.
 */
public final class PetNaming {

  /** One voice, two turns: a first say and, if it did not decide, a second that must. */
  private static final int MAX_TURNS = 2;

  /** Room for a short line plus the small decision object; a naming reply is never long. */
  private static final int NAMING_MAX_TOKENS = 96;

  /** Warm enough for character, low enough to keep the JSON intact on a small model. */
  private static final double NAMING_TEMPERATURE = 0.5D;

  /** A mild push off words just used, the same anti-repeat nudge conversation uses. */
  private static final double NAMING_PENALTY = 0.3D;

  /** A pet's name is short; anything past this is the model running on, and is trimmed. */
  private static final int MAX_NAME_LENGTH = 24;

  private PetNaming() {
  }

  /** The owner's chosen look for their pet: what to call it, its collar, and its coat. */
  public record PetDecision(String name, DyeColor collar, ResourceLocation variant) {
  }

  /**
   * Runs the owner's naming talk and completes with the name, collar, and coat
   * they settle on, or empty when the LLM is down or their talk lands no valid
   * choice (the caller then keeps the pet's random defaults). Call on the server
   * thread: the talk's fixed facts are read from the owner and the registry as it
   * starts.
   */
  public static CompletableFuture<Optional<PetDecision>> decide(RealPerson owner, CompanionPets.Species species,
      TamableAnimal pet) {
    if (!LlmService.get().isReady()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    return Dialogue.run(new Naming(owner, species, pet));
  }

  /** A parsed reply: what the owner said, and the look they settled on, if they settled a valid one. */
  private record ParsedTurn(String say, Optional<PetDecision> decision) {
  }

  /**
   * The owner as a single-voice {@link Dialogue.Protocol}: they speak as
   * themselves and the first valid look resolves the talk. All the prompt strings
   * and the allowed options are built once, when the talk is convened, so a turn
   * reads no live entity state; only speaking a line touches the world, and that
   * hops to the server thread.
   */
  private static final class Naming implements Dialogue.Protocol<PetDecision> {

    private final RealPerson owner;
    private final CompanionPets.Species species;
    private final String ownerFirst;
    private final String system;

    /** Coat options by lowercase path, so the model's answer can be matched back to the real key. */
    private final Map<String, ResourceLocation> allowedVariants;

    private Naming(RealPerson owner, CompanionPets.Species species,
        TamableAnimal pet) {
      this.owner = owner;
      this.species = species;
      this.ownerFirst = owner.getFirstName();
      this.allowedVariants = variantsFor(owner, species);
      this.system = buildSystem(owner, species, allowedVariants.keySet());
    }

    @Override
    public int voices() {
      return 1;
    }

    @Override
    public int maxTurns() {
      return MAX_TURNS;
    }

    @Override
    public CompletableFuture<Dialogue.Turn<PetDecision>> takeTurn(int speaker, Dialogue.Transcript transcript,
        boolean lastChance) {
      String user = buildUser(transcript, lastChance);
      String purpose = ownerFirst + " names their " + species.word();

      CompletableFuture<Dialogue.Turn<PetDecision>> turn = new CompletableFuture<>();
      LlmService.get()
          .submitBackgroundChat(purpose, system, user, List.of(), NAMING_MAX_TOKENS, NAMING_TEMPERATURE, NAMING_PENALTY)
          .whenComplete((reply, error) -> {
            MinecraftServer server = owner.getServer();
            if (server == null) {
              turn.complete(Dialogue.Turn.abort());
              return;
            }
            server.execute(() -> {
              ParsedTurn parsed = (error != null || reply == null || reply.isEmpty())
                  ? null
                  : parse(reply.get());
              if (parsed == null) {
                turn.complete(Dialogue.Turn.abort());
                return;
              }
              String say = VillagerText.clean(parsed.say());
              if (!say.isBlank()) {
                VillagerConversation.speak(owner, say);
              }
              if (parsed.decision().isPresent()) {
                PetDecision decision = parsed.decision().get();
                Villagelife.LOGGER.info("[pet naming] {} names their {} '{}'", owner.getFullName(),
                    species.word(), decision.name());
                turn.complete(Dialogue.Turn.resolved(decision));
                return;
              }
              if (lastChance) {
                turn.complete(Dialogue.Turn.abort());
                return;
              }
              turn.complete(Dialogue.Turn.spoke(say.isBlank() ? "" : ownerFirst + ": " + say));
            });
          });
      return turn;
    }

    /** The owner's briefing: who they are, the animal before them, and the exact collar and coat options. */
    private String buildSystem(RealPerson self, CompanionPets.Species species, Iterable<String> variantPaths) {
      StringBuilder system = new StringBuilder();
      system.append("You are ").append(self.getFullName()).append(", ").append(self.getGender().describe())
          .append(" of ").append(self.getVillageName()).append(". ");
      PersonaData persona = self.getData(VillagelifeAttachments.PERSONA.get());
      if (persona != null && !persona.isEmpty()) {
        system.append("About you: ").append(persona.blurb()).append(' ');
      }
      system.append("You have just been given a ").append(species.word())
          .append(" of your own, and it is yours to name and to make ready. Choose a name for it, ")
          .append("a collar colour, and its coat, in keeping with who you are. Speak as yourself, in the ")
          .append("first person, one short sentence a turn. When you have decided, state your choices in a ")
          .append("decision. Reply with ONLY a JSON object: {\"say\":\"<what you say>\",")
          .append("\"decision\":{\"name\":\"<the ").append(species.word()).append("'s name>\",")
          .append("\"collar\":\"<a collar colour>\",\"variant\":\"<a coat>\"}}. ")
          .append("The collar must be exactly one of: ").append(quoted(collarNames())).append(". ")
          .append("The coat must be exactly one of: ").append(quoted(variantPaths)).append('.');
      return system.toString();
    }

    /** The turn's cue: the scene, the talk so far, and a last turn that insists on a decision. */
    private String buildUser(Dialogue.Transcript transcript, boolean lastChance) {
      StringBuilder user = new StringBuilder();
      user.append("A ").append(species.word()).append(" of your own stands before you; name it and ready it.\n\n");
      user.append("Your words so far:\n");
      if (transcript.isEmpty()) {
        user.append("(nothing said yet)\n");
      } else {
        for (String line : transcript.lines()) {
          user.append(line).append('\n');
        }
      }
      user.append("\nIt is your turn, ").append(ownerFirst).append('.');
      if (lastChance) {
        user.append(" Include your \"decision\" now with the name, collar, and coat you settle on.");
      }
      user.append(" Reply with ONLY the JSON object.");
      return user.toString();
    }

    /**
     * Reads a reply: the strict-then-lenient discipline used across llm-land, the
     * spoken line from {@code say} and the look from {@code decision}. A decision
     * counts only when the name, collar, and coat are all valid; otherwise the
     * turn spoke but did not decide. Null when nothing parses, which ends the talk.
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
        Optional<PetDecision> decision = Optional.empty();
        if (node.has("decision") && node.get("decision").isJsonObject()) {
          decision = readDecision(node.getAsJsonObject("decision"));
        }
        return new ParsedTurn(say, decision);
      } catch (RuntimeException e) {
        return null;
      }
    }

    /** A decision from its object, present only when the name, collar, and coat all validate. */
    private Optional<PetDecision> readDecision(JsonObject decision) {
      Optional<String> name = validateName(field(decision, "name"));
      Optional<DyeColor> collar = validateCollar(field(decision, "collar"));
      Optional<ResourceLocation> variant = validateVariant(field(decision, "variant"));
      if (name.isEmpty() || collar.isEmpty() || variant.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(new PetDecision(name.get(), collar.get(), variant.get()));
    }

    /** A cleaned, trimmed, capped name, or empty when the model gave nothing usable. */
    private Optional<String> validateName(String candidate) {
      if (candidate == null) {
        return Optional.empty();
      }
      String cleaned = VillagerText.clean(candidate).strip();
      if (cleaned.length() > MAX_NAME_LENGTH) {
        cleaned = cleaned.substring(0, MAX_NAME_LENGTH).strip();
      }
      return cleaned.isEmpty() ? Optional.empty() : Optional.of(cleaned);
    }

    /** The dye colour whose name the model gave, case-insensitively, or empty. */
    private Optional<DyeColor> validateCollar(String candidate) {
      if (candidate == null) {
        return Optional.empty();
      }
      String trimmed = candidate.strip();
      for (DyeColor colour : DyeColor.values()) {
        if (colour.getName().equalsIgnoreCase(trimmed)) {
          return Optional.of(colour);
        }
      }
      return Optional.empty();
    }

    /** The coat key the model gave, matched by path case-insensitively against the allowed set, or empty. */
    private Optional<ResourceLocation> validateVariant(String candidate) {
      if (candidate == null) {
        return Optional.empty();
      }
      String trimmed = candidate.strip().toLowerCase(Locale.ROOT);
      // Accept a bare path ("ashen") or a full key ("minecraft:ashen").
      int colon = trimmed.indexOf(':');
      String path = colon >= 0 ? trimmed.substring(colon + 1) : trimmed;
      return Optional.ofNullable(allowedVariants.get(path));
    }

    /** The exact-field getter: a string primitive, or null when absent or not a string. */
    private String field(JsonObject object, String key) {
      return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }
  }

  /** The species' coat variants keyed by lowercase path, in registry order, for prompt and validation. */
  private static Map<String, ResourceLocation> variantsFor(RealPerson owner, CompanionPets.Species species) {
    Map<String, ResourceLocation> variants = new LinkedHashMap<>();
    Set<ResourceLocation> keys = species == CompanionPets.Species.DOG
        ? owner.level().registryAccess().registryOrThrow(Registries.WOLF_VARIANT).keySet()
        : owner.level().registryAccess().registryOrThrow(Registries.CAT_VARIANT).keySet();
    for (ResourceLocation key : keys) {
      variants.put(key.getPath().toLowerCase(Locale.ROOT), key);
    }
    return variants;
  }

  /** The sixteen dye-colour names, for the collar option list. */
  private static List<String> collarNames() {
    List<String> names = new ArrayList<>();
    for (DyeColor colour : DyeColor.values()) {
      names.add(colour.getName());
    }
    return names;
  }

  /** The options as a quoted, comma-separated list for the prompt. */
  private static String quoted(Iterable<String> values) {
    List<String> shown = new ArrayList<>();
    for (String value : values) {
      shown.add('"' + value + '"');
    }
    return String.join(", ", shown);
  }
}
