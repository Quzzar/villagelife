package com.quzzar.villagelife.relationships;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.persona.PersonaData;
import com.quzzar.villagelife.entities.PersonalLogData;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.VillagelifeAttachments;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.village.Village;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A villager making up their mind about what happened to them.
 *
 * Game code writes facts into the personal log and stops there: someone took
 * your job, someone killed the skeleton that was hunting you, someone threw
 * you a diamond. This is the other half — periodically a villager reads the
 * entries they have not yet felt anything about, and their brain decides who
 * they think better or worse of for them, and by how much. The answer is
 * applied through {@link OpinionService}, so it lands on a fellow villager or
 * on a player through exactly the same call.
 *
 * Nothing here decides feelings on the villager's behalf. If the model is
 * unavailable or answers unusably, the entries are simply marked considered
 * and the villager carries on unchanged: a quiet person, not a wrong one.
 */
public final class ReflectionService {

  /** Seconds between reflection passes per village, phase-staggered. */
  public static final int REFLECT_INTERVAL_SECONDS = 180;

  /** At most this many entries are weighed at once, newest first. */
  private static final int MAX_ENTRIES = 6;

  private static final String SYSTEM = "You are a villager in a settlement, thinking over "
      + "what has happened to you lately and how it changes your feelings about people. "
      + "You are given numbered people and the things that happened involving them. "
      + "For each person whose standing with you genuinely changed, give a whole number "
      + "from -15 to 15: negative if you think worse of them, positive if better. "
      + "Most events change nothing; leave those people out entirely. "
      + "Answer with ONLY a JSON array like "
      + "[{\"person\": 1, \"change\": 6, \"why\": \"<a few words>\"}] and nothing else.";

  private ReflectionService() {
  }

  /**
   * Picks one resident with unconsidered entries and lets them reflect. One
   * villager per pass keeps this to a trickle on the background queue, which
   * is where slow thinking belongs.
   */
  public static void tick(Village village, ServerLevel level) {
    if (!LlmService.get().isReady()) {
      return;
    }
    for (UUID residentId : village.getPopulation()) {
      if (!(level.getEntity(residentId) instanceof RealPerson person)) {
        continue;
      }
      PersonalLogData log = person.getData(VillagelifeAttachments.PERSONAL_LOG.get());
      List<PersonalLogData.Entry> fresh = log.unreflected();
      if (fresh.isEmpty()) {
        continue;
      }
      reflect(person, level, log, fresh);
      return;
    }
  }

  private static void reflect(RealPerson person, ServerLevel level, PersonalLogData log,
      List<PersonalLogData.Entry> fresh) {
    // Group what happened by the person it happened with, so the villager
    // weighs a person rather than a list of disconnected incidents.
    Map<UUID, List<String>> byParty = new LinkedHashMap<>();
    for (int i = fresh.size() - 1; i >= 0 && byParty.size() < MAX_ENTRIES; i--) {
      PersonalLogData.Entry entry = fresh.get(i);
      entry.who().ifPresent(who ->
          byParty.computeIfAbsent(who, key -> new ArrayList<>()).add(describe(entry)));
    }
    if (byParty.isEmpty()) {
      markConsidered(person, log, level);
      return;
    }

    List<UUID> order = new ArrayList<>(byParty.keySet());
    StringBuilder user = new StringBuilder();
    PersonaData persona = person.getData(VillagelifeAttachments.PERSONA.get());
    if (!persona.isEmpty()) {
      user.append("You are ").append(person.getFullName()).append(". ")
          .append(persona.blurb()).append('\n');
    }
    for (int i = 0; i < order.size(); i++) {
      UUID party = order.get(i);
      user.append(i + 1).append(". ").append(nameOf(level, person, party))
          .append(" (you currently feel ").append(OpinionService.opinionOf(person, party))
          .append(" about them on a scale of -100 to 100)\n");
      for (String happening : byParty.get(party)) {
        user.append("   - ").append(happening).append('\n');
      }
    }
    user.append("Your JSON answer:");

    long now = person.level().getDayTime();
    LlmService.get().submitPersona(SYSTEM, user.toString(), 128, 0.4)
        .thenAccept(reply -> level.getServer().execute(() -> {
          if (!person.isAlive()) {
            return;
          }
          reply.ifPresent(text -> applyJudgements(person, level, order, text));
          PersonalLogData current = person.getData(VillagelifeAttachments.PERSONAL_LOG.get());
          person.setData(VillagelifeAttachments.PERSONAL_LOG.get(), current.reflectedAt(now));
        }));
  }

  private static void applyJudgements(RealPerson person, ServerLevel level, List<UUID> order, String raw) {
    JsonArray array = parseArray(raw);
    if (array == null) {
      Villagelife.LOGGER.debug("[reflection] {} produced no usable judgement", person.getFullName());
      return;
    }
    for (JsonElement element : array) {
      if (!element.isJsonObject()) {
        continue;
      }
      JsonObject judgement = element.getAsJsonObject();
      if (!judgement.has("person") || !judgement.has("change")) {
        continue;
      }
      int index;
      int change;
      try {
        index = judgement.get("person").getAsInt() - 1;
        change = judgement.get("change").getAsInt();
      } catch (NumberFormatException | IllegalStateException e) {
        continue;
      }
      if (index < 0 || index >= order.size() || change == 0) {
        continue;
      }
      String why = judgement.has("why") ? judgement.get("why").getAsString() : "on reflection";
      UUID target = order.get(index);
      int applied = OpinionService.apply(person, target, change, why);
      if (applied != 0) {
        Villagelife.LOGGER.info("[reflection] {} thinks {} of {}: {}",
            person.getFullName(), applied > 0 ? "better" : "worse",
            nameOf(level, person, target), why);
      }
    }
  }

  /** Tolerant of a model that wraps the array in prose or a code fence. */
  private static JsonArray parseArray(String raw) {
    int start = raw.indexOf('[');
    int end = raw.lastIndexOf(']');
    if (start < 0 || end <= start) {
      return null;
    }
    try {
      JsonElement parsed = JsonParser.parseString(raw.substring(start, end + 1));
      return parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String describe(PersonalLogData.Entry entry) {
    if (PersonalLogData.KIND_PICKUP.equals(entry.kind())) {
      String item = entry.item().contains(":")
          ? entry.item().substring(entry.item().indexOf(':') + 1).replace('_', ' ')
          : entry.item();
      return "They threw you " + entry.count() + " " + item
          + " (" + PersonalLogData.formatDay(entry.dayTime()) + ")";
    }
    return entry.text() + " (" + PersonalLogData.formatDay(entry.dayTime()) + ")";
  }

  private static String nameOf(ServerLevel level, RealPerson person, UUID party) {
    if (level.getEntity(party) instanceof RealPerson other) {
      return other.getFullName();
    }
    ServerPlayer player = level.getServer().getPlayerList().getPlayer(party);
    if (player != null) {
      return player.getGameProfile().getName();
    }
    Optional<Village> village = Optional.ofNullable(person.getVillage());
    return village.isPresent() && village.get().hasResident(party) ? "a neighbour" : "someone";
  }

  private static void markConsidered(RealPerson person, PersonalLogData log, ServerLevel level) {
    person.setData(VillagelifeAttachments.PERSONAL_LOG.get(),
        log.reflectedAt(person.level().getDayTime()));
  }

}
