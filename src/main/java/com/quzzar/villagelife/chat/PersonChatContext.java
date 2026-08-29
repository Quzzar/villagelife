package com.quzzar.villagelife.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.authlib.GameProfile;
import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.compat.AccessoryCompat;
import com.quzzar.villagelife.entities.PersonalLogData;
import com.quzzar.villagelife.entities.UndertakingData;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.VillagelifeAttachments;
import com.quzzar.villagelife.llm.LlmService.FewShotExample;
import com.quzzar.villagelife.other.YearManager;
import com.quzzar.villagelife.persona.PersonaData;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.VillageAttractiveness;
import com.quzzar.villagelife.village.VillageRequests;
import com.quzzar.villagelife.village.buildings.BuildingInfo;
import com.quzzar.villagelife.village.buildings.Buildings;
import com.quzzar.villagelife.village.buildings.VillageGoal;

import net.minecraft.world.item.Item;
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

  private static final String RULES_BODY = "Rules: Answer in one or two short sentences, always in character. "
      + "Never invent events, people, places, or items that are not in your briefing above. "
      + "You may hand an item from your pockets to the player with \"give\" (the item id), and \"give_count\" for how many (a whole number, default 1), but ONLY when they have just asked you for something. Never offer an item unprompted, and never give away anything precious. Most replies have no \"give\" at all. "
      + "When this moment genuinely changes how you feel about them, add \"opinion\": a whole number "
      + "from -10 to 10; omit it when your feeling is unchanged. ";

  private static final String RULES = RULES_BODY
      + "Answer with ONLY a JSON object: {\"say\": \"<reply>\"} "
      + "or {\"say\": \"<reply>\", \"give\": \"<item id>\", \"give_count\": <how many>, \"opinion\": <number>}.";

  private static final String SHAPE_UNDERTAKING =
      "Answer with ONLY a JSON object: {\"say\": \"<reply>\"}, adding any of \"give\", \"opinion\", or "
      + "\"undertaking\" only when it applies.";

  /**
   * The undertaking clause is split by the state the SERVER already knows, and
   * the model is shown only the ops that are legal in that state - the same
   * discipline the decision brain uses when it never shows an unaffordable
   * building (llm-brain.md). The audit (7b) measured why it must be: shown all
   * three ops at once, the 3B defaults nearly every matter to "open", opening a
   * second matter when the briefing plainly says one already stands. It cannot
   * pick open vs advance from context, so it is not asked to.
   *
   * The whole clause stays gated on top of that (undertakings map #24): on a
   * turn where no matter is in play at all, none of these variants is used and
   * the field is never mentioned - the give tool's lesson that a small model
   * reaches for an always-present field on turns that do not warrant it.
   *
   * NEW_MATTER: no matter stands, but this turn opens one (opensACommitment).
   * Only "open" is offered.
   */
  private static final String RULES_NEW_MATTER = RULES_BODY
      + "Something worth seeing through over time has come up - a task you mean to tackle, a problem to sort out, a "
      + "goal you are working toward, or something this person will do for you or put right - and no such matter yet "
      + "stands. If this turn truly begins one, record it so you remember it later: \"undertaking\": {\"op\": "
      + "\"open\", \"summary\": \"<what is to be seen through, in a few words>\", \"valence\": \"positive\" for a hope "
      + "or a kindness, \"negative\" for a problem or a wrong to right}. Most turns begin nothing - leave it out "
      + "unless this one does. "
      + SHAPE_UNDERTAKING;

  /**
   * OPEN_MATTER: a matter already stands with this person (openWith non-empty).
   * "Open" is withheld entirely - the server coerces a stray open to advance
   * anyway (7b's UndertakingService change), so the model's only real call is
   * the one the server cannot make: has this settled the matter (resolve) or
   * merely moved it (advance). Framed as that binary, with the completion-
   * language contrast carried by the few-shots below.
   */
  private static final String RULES_OPEN_MATTER = RULES_BODY
      + "A matter already stands between you and this person (named above). You are NOT opening a new one - you are "
      + "moving it forward or settling it. If this exchange moves it along, \"undertaking\": {\"op\": \"advance\", "
      + "\"note\": \"<what moved>\"}. If it settles the matter for good, \"undertaking\": {\"op\": \"resolve\", "
      + "\"note\": \"<how it ended>\"}. When their words mark it FINISHED - the LAST of it, the FINAL piece, the WHOLE "
      + "amount, all of it now, the debt PAID, the task DONE - that is resolve, never advance. If it does neither, "
      + "leave \"undertaking\" out. You never name which matter - the game knows. "
      + SHAPE_UNDERTAKING;

  /**
   * Whether the player's line plausibly opens a NEW matter - amends, a promise,
   * a debt. An existing open matter opens the gate on its own (see assemble),
   * so this only has to catch the moments that START one; the strong signals
   * are apology, owing, and promising. Delivery words ("here's the last of it")
   * are deliberately absent: they only mean anything when a matter already
   * stands, and that case is already gated in structurally.
   */
  private static boolean opensACommitment(String playerLine) {
    String line = playerLine.toLowerCase(java.util.Locale.ROOT);
    for (String marker : COMMITMENT_MARKERS) {
      if (line.contains(marker)) {
        return true;
      }
    }
    return false;
  }

  private static final String[] COMMITMENT_MARKERS = {
      "sorry", "apolog", "make it right", "make up for", "my fault", "forgive", "make amends",
      "owe", "repay", "pay you back", "i'll get you", "i will get you", "i'll bring", "i will bring",
      "promise", "i swear", "you have my word"
  };

  /**
   * Collapses a run of turns where the villager gave the same answer down to
   * the last of them.
   *
   * A small model that has said a line once is fairly likely to say it again;
   * once the transcript shows it saying that line TWICE, it is close to
   * certain. Measured on the live Qwen2.5-3B, with the same sentence in the
   * transcript: once, it never repeated; twice, 2 of 4; three times, 4 of 4.
   * That is the shape Aaron hit - a reasonable first answer, then the same
   * sentence to everything.
   *
   * So the escalation is the actual defect, and it is cheaper to prevent than
   * to sample around: the model never sees itself say the same thing twice,
   * so the attractor cannot build. Dropping those turns loses nothing worth
   * keeping - they are precisely the exchanges where the villager was not
   * really answering.
   */
  private static List<Turn> withoutRepeats(List<Turn> history) {
    List<Turn> kept = new ArrayList<>(history.size());
    for (Turn turn : history) {
      if (!kept.isEmpty() && sameAnswer(kept.get(kept.size() - 1), turn)) {
        kept.remove(kept.size() - 1);
      }
      kept.add(turn);
    }
    return kept;
  }

  /**
   * Delegates to the dispatcher's comparison so the transcript collapses on the
   * same rule the retry guard uses. Whole line OR the same opening sentence:
   * the model locks onto an opening and varies the tail, so four replies that
   * all begin "Just a bit on my mind, that's all." are four distinct lines and
   * a whole-line check leaves every one of them in the transcript to reinforce
   * the next.
   */
  private static boolean sameAnswer(Turn a, Turn b) {
    return a.villagerLine() != null && b.villagerLine() != null
        && PersonChatDispatcher.sameAnswer(a.villagerLine(), b.villagerLine());
  }

  private static final List<FewShotExample> EXAMPLES = List.of(
      new FewShotExample("Steve says: \"What did you find today?\"\nYour JSON answer:",
          "{\"say\": \"Only some wheat by the field this morning, nothing grander.\"}"),
      new FewShotExample("Steve says: \"Remember the goblin king we robbed last summer?\"\nYour JSON answer:",
          "{\"say\": \"I recall no such thing, Steve.\"}"),
      new FewShotExample("Steve says: \"Could you spare a few torches? You've always been kind to me.\"\nYour JSON answer:",
          "{\"say\": \"Take three, and mind the dark.\", \"give\": \"minecraft:torch\", \"give_count\": 3, \"opinion\": 2}"));

  /**
   * Shown when a NEW matter may open. Both are the villager's OWN matters - a
   * problem to sort (negative) and a goal in progress (positive) - because the
   * point of undertakings is a villager tracking its own things over time, not
   * only what a player owes. Kept clear of any one test scenario (roof, ox) so a
   * match on a different matter is generalisation, not the 3B copying the example
   * wholesale (the failure the live test caught with the old debt-only examples).
   */
  private static final List<FewShotExample> OPEN_EXAMPLES = List.of(
      new FewShotExample("Steve says: \"That roof of yours is still letting the rain in, isn't it?\"\nYour JSON answer:",
          "{\"say\": \"It is, and it's past time I patched it properly.\", "
          + "\"undertaking\": {\"op\": \"open\", \"summary\": \"Patch the leaking roof over my house\", \"valence\": \"negative\"}}"),
      new FewShotExample("Steve says: \"I hear you've been setting coin aside for something.\"\nYour JSON answer:",
          "{\"say\": \"Bit by bit, toward a second ox to work the field.\", "
          + "\"undertaking\": {\"op\": \"open\", \"summary\": \"Save up for a second ox for the plough\", \"valence\": \"positive\"}}"));

  /**
   * Shown when a matter STANDS, all on the villager's OWN matter so progress
   * tracking is taught the same way opening is: a neutral turn that records
   * NOTHING (advance was firing on every turn once the tool was offered), a step
   * of progress (advance), and a completion (resolve). Completion leans on "at
   * last", "done" so the 3B separates a final step from a partial one. Kept off
   * any one test scenario so a match is generalisation, not copying.
   */
  private static final List<FewShotExample> PROGRESS_EXAMPLES = List.of(
      new FewShotExample("Steve says: \"Just passing through, don't mind me.\"\nYour JSON answer:",
          "{\"say\": \"Mind yourself, the roads are dark this hour.\"}"),
      new FewShotExample("Steve says: \"How's that roof coming along?\"\nYour JSON answer:",
          "{\"say\": \"Half the shingles are back on. The rest tomorrow.\", "
          + "\"undertaking\": {\"op\": \"advance\", \"note\": \"Half the roof re-shingled\"}}"),
      new FewShotExample("Steve says: \"Looks like you've got that roof finished at last.\"\nYour JSON answer:",
          "{\"say\": \"Every last shingle, and dry beneath it now.\", "
          + "\"undertaking\": {\"op\": \"resolve\", \"note\": \"The roof is fully patched\"}}"));

  /** Which undertaking state a turn is in - drives both the rules and examples. */
  private enum UndertakingMode { NONE, NEW_MATTER, OPEN_MATTER }

  /** The base examples, plus the few-shots for the op(s) legal in this state. */
  private static List<FewShotExample> examplesFor(UndertakingMode mode) {
    List<FewShotExample> all = new ArrayList<>(EXAMPLES);
    switch (mode) {
      case OPEN_MATTER -> all.addAll(PROGRESS_EXAMPLES);
      case NEW_MATTER -> all.addAll(OPEN_EXAMPLES);
      case NONE -> { }
    }
    return all;
  }

  /**
   * Offered when the player is urging a village-wide direction. The villager may
   * forward it to the brain as a request but never decides it: the request only
   * reaches the planner's option list and states its case (VillageRequests,
   * UrbanPlanner). Gated like the undertaking tool so a small model is not tempted
   * to file a request on a turn that is not one.
   */
  private static final String RULES_REQUEST = RULES_BODY
      + "This person is urging a direction for the whole village. You do NOT decide what the village "
      + "does. But if you genuinely agree it should, you may put the idea to the village's judgement: "
      + "\"request\": {\"subject\": \"<what to build, a word or two>\", \"reason\": \"<why, a few words>\"}. "
      + "Add it only when you truly back the idea; if you do not, simply answer in character with no request. "
      + "Answer with ONLY a JSON object: {\"say\": \"<reply>\"}, adding \"give\", \"opinion\", or \"request\" "
      + "only when it applies.";

  /** Teaches the request field, and (second) that most urgings are declined, not forwarded. */
  private static final List<FewShotExample> REQUEST_EXAMPLES = List.of(
      new FewShotExample("Steve says: \"With those wolves about, you lot should build a wall.\"\nYour JSON answer:",
          "{\"say\": \"Aye, a wall would let us sleep easier. I'll put it to the village.\", "
          + "\"request\": {\"subject\": \"walls\", \"reason\": \"wolves at the fields after dark\"}}"),
      new FewShotExample("Steve says: \"You should build a great statue of me in the square.\"\nYour JSON answer:",
          "{\"say\": \"Ha. I think the village has better uses for its stone, friend.\"}"));

  /** The base examples plus the request few-shots, for a village-direction turn. */
  private static List<FewShotExample> withRequestExamples() {
    List<FewShotExample> all = new ArrayList<>(EXAMPLES);
    all.addAll(REQUEST_EXAMPLES);
    return all;
  }

  /**
   * Whether the player's line plausibly urges a village-wide building direction,
   * the gate for offering the request tool. Deliberately narrow: it looks for
   * building and priority language, so an ordinary "you should rest" does not
   * open it.
   */
  private static boolean proposesVillageChange(String playerLine) {
    String line = playerLine.toLowerCase(java.util.Locale.ROOT);
    for (String marker : VILLAGE_CHANGE_MARKERS) {
      if (line.contains(marker)) {
        return true;
      }
    }
    return false;
  }

  private static final String[] VILLAGE_CHANGE_MARKERS = {
      "should build", "should make", "let's build", "let us build", "build a ", "build the ",
      "build some ", "build more ", "build walls", "build a wall", "put up a ", "we need a ",
      "we need more ", "we need walls", "start building", "save up for", "save for a ",
      "prioriti", "focus on building", "instead of building", "the village should", "ought to build",
      "you lot should", "you should build"
  };

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
      // What the village is saving toward, in its own words, so the villager can
      // speak to it. The planner names a goal and then declines to spend until it
      // can afford it (VillageGoal); without this line the brain has no way to know
      // its own village is holding out for something, and cannot mention it.
      String goal = VillageGoal.current(village);
      if (goal != null) {
        BuildingInfo wanted = Buildings.getByName(goal);
        String label = wanted != null && wanted.hasWellFormedId()
            ? wanted.getCategory().replace('_', ' ')
            : (wanted != null ? wanted.getName() : goal);
        system.append("Your village is saving up to build a ").append(label);
        String why = VillageGoal.reason(village);
        if (why != null && !why.isEmpty()) {
          system.append(" (").append(why).append(')');
        }
        system.append(".\n");
        // The exact recipe and what is still short, so the villager speaks to the
        // real cost instead of inventing one. With only the goal name and reason
        // in the briefing, the small model filled the gap with plausible-but-wrong
        // materials - a lumberjack asked for "wheat seeds" off the "we need food"
        // reason, when the build actually costs cobblestone.
        List<ItemStack> recipe = wanted != null ? wanted.getMaterialCost() : List.of();
        if (!recipe.isEmpty()) {
          system.append("To build it the village needs ").append(joinCosts(recipe)).append(". ");
          String remaining = remainingCosts(recipe, village.stockTally());
          system.append(remaining.isEmpty()
              ? "Everything needed is gathered; building can begin soon."
              : "Still short " + remaining + ".");
          system.append('\n');
        }
      }
      // How the brain answered a request this villager put to it, told once so the
      // loop the player opened in chat visibly closes. takeUnheard marks it heard,
      // so it colours the next reply and is not raised again.
      VillageRequests.takeUnheard(village, person.getUUID()).ifPresent(request ->
          system.append(request.status() == VillageRequests.Status.ACCEPTED
              ? "The village took up your idea to build " + request.subject() + ". "
              : "The village weighed your idea to build " + request.subject()
                  + " but chose otherwise for now. ").append('\n'));
    }

    // Everything on their person is ALWAYS stated, even when empty: an omitted
    // line is an invitation for the model to invent contents (dogfood finding).
    system.append("You are holding: ").append(heldSummary(person)).append(".\n");
    String armor = armorSummary(person);
    if (!armor.isEmpty()) {
      system.append("You are wearing: ").append(armor).append(".\n");
    }
    // Accessory-mod slots, when such a mod is installed (#46).
    List<String> accessories = AccessoryCompat.wornAccessories(person);
    if (!accessories.isEmpty()) {
      system.append("You also have on: ").append(String.join(", ", accessories)).append(".\n");
    }
    String pockets = pocketsSummary(person);
    system.append("Your pockets: ").append(pockets.isEmpty() ? "empty" : pockets).append(".\n");

    system.append("It is now ").append(PersonalLogData.formatDay(person.level().getDayTime())).append(".\n");
    // Villagers reckon the year from the world's age (the Days in Year config),
    // one-based so a fresh world is year 1. The time of day above says where in
    // the day they are; this gives the larger calendar for a chat to lean on.
    int worldYear = (int) YearManager.getYears(person.level()) + 1;
    system.append("The year is ").append(worldYear).append(" MCE.\n");

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
    system.append(". ").append(opinionLine(person, playerName, playerUUID)).append('\n');

    // At most one structured tool is offered per turn: a small model reaches for
    // an always-present optional field on turns that do not warrant it (the
    // undertaking audit, #24). A village-direction the player is urging takes the
    // turn - the villager may forward it to the brain as a request - and the
    // undertaking tool stands down until a plainer turn.
    //
    // Otherwise the undertaking tool is offered only when a matter is plausibly in
    // play, and then only the ops legal in the current state. A matter already
    // standing takes precedence: the model is shown advance/resolve, never open,
    // because the server knows one stands and coerces a stray open anyway. Only
    // when nothing stands and the line opens a new commitment is "open" offered.
    // Open matters are stated so an advance or resolve knows its target.
    boolean proposesChange = village != null && proposesVillageChange(playerLine);
    List<UndertakingData.Undertaking> openMatters =
        person.getData(VillagelifeAttachments.UNDERTAKINGS.get()).openWith(playerUUID);
    UndertakingMode mode = proposesChange ? UndertakingMode.NONE
        : !openMatters.isEmpty() ? UndertakingMode.OPEN_MATTER
        : opensACommitment(playerLine) ? UndertakingMode.NEW_MATTER
        : UndertakingMode.NONE;
    if (proposesChange) {
      system.append(RULES_REQUEST);
    } else {
      if (mode == UndertakingMode.OPEN_MATTER) {
        system.append("Still between you and ").append(playerName).append(": ")
            .append(openMatters.stream().map(UndertakingData.Undertaking::summary)
                .collect(java.util.stream.Collectors.joining("; ")))
            .append(". If one moves forward or is settled now, mark it.\n");
      }
      system.append(switch (mode) {
        case OPEN_MATTER -> RULES_OPEN_MATTER;
        case NEW_MATTER -> RULES_NEW_MATTER;
        case NONE -> RULES;
      });
    }

    StringBuilder user = new StringBuilder();
    List<Turn> transcript = withoutRepeats(history);
    if (!transcript.isEmpty()) {
      user.append("Conversation so far:\n");
      for (Turn turn : transcript) {
        user.append(playerName).append(" said: \"").append(turn.playerLine()).append("\"\n");
        user.append("You answered: \"").append(turn.villagerLine()).append("\"\n");
      }
    }
    user.append(playerName).append(" says: \"").append(playerLine).append("\"\nYour JSON answer:");

    return new AssembledChat(system.toString(), user.toString(),
        proposesChange ? withRequestExamples() : examplesFor(mode));
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

  /** The full recipe as "104 cobblestone, 8 iron ingot", for the goal briefing. */
  private static String joinCosts(List<ItemStack> costs) {
    List<String> parts = new ArrayList<>();
    for (ItemStack cost : costs) {
      parts.add(cost.getCount() + " " + itemName(cost));
    }
    return String.join(", ", parts);
  }

  /**
   * Only the shortfall against current village stock, e.g. "40 cobblestone";
   * empty when every material is already gathered. Stock is the same tally the
   * planner affords against (chests plus what villagers carry), so what a
   * villager says it still needs matches what the brain is actually waiting on.
   */
  private static String remainingCosts(List<ItemStack> costs, Map<Item, Integer> stock) {
    List<String> parts = new ArrayList<>();
    for (ItemStack cost : costs) {
      int missing = cost.getCount() - stock.getOrDefault(cost.getItem(), 0);
      if (missing > 0) {
        parts.add(missing + " " + itemName(cost));
      }
    }
    return String.join(", ", parts);
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
