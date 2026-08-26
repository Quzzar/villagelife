package com.quzzar.villagelife.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.authlib.GameProfile;
import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.entities.PersonalLogData;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.VillagelifeAttachments;
import com.quzzar.villagelife.llm.LlmService.FewShotExample;
import com.quzzar.villagelife.persona.PersonaData;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.VillageAttractiveness;

import net.minecraft.world.item.ItemStack;

/**
 * Assembles a villager's per-turn briefing per the chat contract (conversation
 * map #24): everything relevant, compressed — persona, village situation, the
 * speaker and what they have thrown, recent pickups, pockets, and this
 * session's history — plus the few-shot example turns that the prototype
 * (#27) showed carry the format at small model sizes.
 */
public final class PersonChatContext {

  public record AssembledChat(String system, String user, List<FewShotExample> examples) {
  }

  /** One player↔villager exchange, for the session-scoped history. */
  public record Turn(String playerLine, String villagerLine) {
  }

  private static final String RULES = "Rules: Answer in one or two short sentences, always in character. "
      + "Never invent events, people, places, or items that are not in your briefing above. "
      + "You may hand an item from your pockets to the player with \"give\" when you are willing. "
      + "When this moment genuinely changes how you feel about them, add \"opinion\": a whole number "
      + "from -10 to 10; omit it when your feeling is unchanged. "
      + "Answer with ONLY a JSON object: {\"say\": \"<reply>\"} "
      + "or {\"say\": \"<reply>\", \"give\": \"<item id>\", \"opinion\": <number>}.";

  private static final List<FewShotExample> EXAMPLES = List.of(
      new FewShotExample("Steve says: \"What did you find today?\"\nYour JSON answer:",
          "{\"say\": \"Only some wheat by the field this morning, nothing grander.\"}"),
      new FewShotExample("Steve says: \"Remember the goblin king we robbed last summer?\"\nYour JSON answer:",
          "{\"say\": \"I recall no such thing, Steve.\"}"),
      new FewShotExample("Steve says: \"Could I have one of your torches? You've always been kind to me.\"\nYour JSON answer:",
          "{\"say\": \"Take it, and mind the dark.\", \"give\": \"minecraft:torch\", \"opinion\": 2}"));

  private PersonChatContext() {
  }

  public static AssembledChat assemble(RealPerson person, String playerName, java.util.UUID playerUUID,
      List<Turn> history, String playerLine) {
    StringBuilder system = new StringBuilder();

    String occupation = Utils.capitalize(person.getOccupation().name().toLowerCase());
    Village village = person.getVillage();
    String villageName = village != null ? village.getName() : "no village";
    system.append("You are ").append(person.getFullName())
        .append(", a villager in a village simulation. You are the ").append(occupation)
        .append(" of ").append(villageName).append(".\n");

    PersonaData persona = person.getData(VillagelifeAttachments.PERSONA.get());
    if (!persona.isEmpty()) {
      system.append("About you: ").append(persona.blurb());
      if (!persona.quirk().isBlank()) {
        system.append(" Quirk: ").append(persona.quirk());
      }
      system.append('\n');
    }

    if (village != null) {
      system.append("Your village: ").append(villageName).append(", a ")
          .append(tierName(village)).append(". ").append(situationLine(village)).append('\n');
    }

    // Everything on their person is ALWAYS stated, even when empty: an omitted
    // line is an invitation for the model to invent contents (dogfood finding).
    // Accessory-mod slots (Curios etc.) are a tracked follow-up.
    system.append("You are holding: ").append(heldSummary(person)).append(".\n");
    String armor = armorSummary(person);
    if (!armor.isEmpty()) {
      system.append("You are wearing: ").append(armor).append(".\n");
    }
    String pockets = pocketsSummary(person);
    system.append("Your pockets: ").append(pockets.isEmpty() ? "empty" : pockets).append(".\n");

    system.append("It is now ").append(PersonalLogData.formatDay(person.level().getDayTime())).append(".\n");

    PersonalLogData log = person.getData(VillagelifeAttachments.PERSONAL_LOG.get());
    String remembered = pickupSummary(person, log, 5);
    if (!remembered.isEmpty()) {
      system.append("You remember picking up: ").append(remembered).append(".\n");
    }

    String worries = issueSummary(log, 3);
    if (!worries.isEmpty()) {
      system.append("Your worries: ").append(worries).append(".\n");
    }

    String relations = relationSummary(person, village, 3);
    if (!relations.isEmpty()) {
      system.append("People in your life: ").append(relations).append('\n');
    }

    system.append("You are talking to ").append(playerName);
    int thrownBySpeaker = log.thrownBy(playerUUID).size();
    if (thrownBySpeaker > 0) {
      system.append(", who has thrown you items before");
    }
    system.append(". ").append(opinionLine(person, playerName, playerUUID)).append('\n').append(RULES);

    StringBuilder user = new StringBuilder();
    if (!history.isEmpty()) {
      user.append("Conversation so far:\n");
      for (Turn turn : history) {
        user.append(playerName).append(" said: \"").append(turn.playerLine()).append("\"\n");
        user.append("You answered: \"").append(turn.villagerLine()).append("\"\n");
      }
    }
    user.append(playerName).append(" says: \"").append(playerLine).append("\"\nYour JSON answer:");

    return new AssembledChat(system.toString(), user.toString(), EXAMPLES);
  }

  private static String tierName(Village village) {
    String id = village.getTierId();
    int colon = id.indexOf(':');
    return (colon >= 0 ? id.substring(colon + 1) : id);
  }

  private static String situationLine(Village village) {
    VillageAttractiveness attractiveness = village.getAttractiveness();
    StringBuilder line = new StringBuilder();
    if (attractiveness != null) {
      switch (attractiveness.status()) {
        case GROWING -> line.append("Times are decent and new folk keep arriving.");
        case HOLDING -> line.append("Times are steady, neither good nor bad.");
        case DECLINING -> line.append("Times are hard and people talk of leaving.");
      }
    }
    // TODO: mention notable recent bookkeeper events (deaths, attacks) once
    // VillageBrain exposes the bookkeeper — its file is claimed by the
    // campfire-implementation lane right now.
    return line.toString();
  }

  /** What's in their hands — held items live outside the pockets inventory. */
  private static String heldSummary(RealPerson person) {
    ItemStack main = person.getMainHandItem();
    ItemStack off = person.getOffhandItem();
    if (main.isEmpty() && off.isEmpty()) {
      return "nothing";
    }
    StringBuilder held = new StringBuilder();
    if (!main.isEmpty()) {
      held.append("a ").append(itemName(main));
    }
    if (!off.isEmpty()) {
      if (held.length() > 0) {
        held.append(" and ");
      }
      held.append("a ").append(itemName(off));
    }
    return held.toString();
  }

  /**
   * The villager's standing feeling about the speaker — always stated so the
   * opinion tool compounds across conversations (#44).
   */
  private static String opinionLine(RealPerson person, String playerName, java.util.UUID playerUUID) {
    int opinion = person.getData(VillagelifeAttachments.SOCIAL.get()).relationships()
        .getOrDefault(playerUUID, 0);
    String feeling;
    if (opinion >= 60) {
      feeling = "You consider " + playerName + " a dear friend";
    } else if (opinion >= 25) {
      feeling = "You rather like " + playerName;
    } else if (opinion >= 10) {
      feeling = "You are warming to " + playerName;
    } else if (opinion > -10) {
      feeling = "You have no strong feelings about " + playerName + " yet";
    } else if (opinion > -25) {
      feeling = playerName + " has annoyed you before";
    } else if (opinion > -60) {
      feeling = "You dislike " + playerName;
    } else {
      feeling = "You despise " + playerName;
    }
    return feeling + ".";
  }

  /** Worn armor, e.g. "an iron helmet and iron boots". */
  private static String armorSummary(RealPerson person) {
    List<String> parts = new ArrayList<>();
    for (ItemStack stack : person.getArmorSlots()) {
      if (!stack.isEmpty()) {
        parts.add(itemName(stack));
      }
    }
    return String.join(" and ", parts);
  }

  /** The FULL pocket contents, aggregated by item — they know their own bags. */
  private static String pocketsSummary(RealPerson person) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (int i = 0; i < person.personMainInv.getContainerSize(); i++) {
      ItemStack stack = person.personMainInv.getItem(i);
      if (!stack.isEmpty()) {
        counts.merge(itemName(stack), stack.getCount(), Integer::sum);
      }
    }
    List<String> parts = new ArrayList<>();
    counts.forEach((name, count) -> parts.add(count + " " + name));
    return String.join(", ", parts);
  }

  private static String itemName(ItemStack stack) {
    return stack.getItem().toString().replace("minecraft:", "").replace('_', ' ');
  }

  private static String pickupSummary(RealPerson person, PersonalLogData log, int limit) {
    List<String> parts = new ArrayList<>();
    for (PersonalLogData.Entry entry : log.pickupsNewestFirst()) {
      if (parts.size() >= limit) {
        break;
      }
      String source = entry.who()
          .map(uuid -> "thrown to you by " + resolveName(person, uuid))
          .orElse("found");
      parts.add(entry.count() + " " + entry.item().replace("minecraft:", "")
          + " (" + source + ", " + PersonalLogData.formatDay(entry.dayTime()) + ")");
    }
    return String.join(", ", parts);
  }

  /** The person's strongest relationships, with names, feeling, and flavor. */
  private static String relationSummary(RealPerson person, Village village, int limit) {
    if (village == null || !(person.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
      return "";
    }
    java.util.UUID self = person.getUUID();
    List<String> parts = new ArrayList<>();
    village.relationshipsOf(self).stream()
        .sorted((a, b) -> Integer.compare(Math.abs(b.opinionOf(self)), Math.abs(a.opinionOf(self))))
        .limit(limit)
        .forEach(pair -> {
          if (serverLevel.getEntity(pair.other(self)) instanceof RealPerson other) {
            int opinion = pair.opinionOf(self);
            String feeling = opinion > 50 ? "you are close to them"
                : opinion > 15 ? "you are fond of them"
                : opinion >= -15 ? "you know them"
                : opinion >= -50 ? "you dislike them"
                : "you despise them";
            String flavor = pair.flavor().isBlank() ? "" : " " + pair.flavor();
            parts.add(other.getFullName() + " (" + feeling + "." + flavor + ")");
          }
        });
    return String.join(" ", parts);
  }

  private static String issueSummary(PersonalLogData log, int limit) {
    List<String> parts = new ArrayList<>();
    for (PersonalLogData.Entry entry : log.issuesNewestFirst()) {
      if (parts.size() >= limit) {
        break;
      }
      parts.add(entry.text() + " (" + PersonalLogData.formatDay(entry.dayTime()) + ")");
    }
    return String.join("; ", parts);
  }

  private static String resolveName(RealPerson person, java.util.UUID uuid) {
    return person.getServer() != null
        ? person.getServer().getProfileCache().get(uuid).map(GameProfile::getName).orElse("someone")
        : "someone";
  }

}
