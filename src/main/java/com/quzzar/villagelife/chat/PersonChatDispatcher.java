package com.quzzar.villagelife.chat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.chat.PersonChatContext.AssembledChat;
import com.quzzar.villagelife.chat.PersonChatContext.Turn;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.VillagelifeAttachments;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.networking.PersonChatReplyPacket;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The conversation pipeline (conversation map #28): assembles the villager's
 * briefing, asks the LLM through the chat-priority slot, parses the
 * {@code {say, give?}} reply, executes a validated give, and answers the
 * player. Every failure path ends in one in-fiction fallback line — the
 * player can simply say it again.
 */
public final class PersonChatDispatcher {

  private static final int MAX_HISTORY_TURNS = 6;
  // Trimmed from 96 after live capture: 47 tokens took ~13s in-game on the 1B
  // (server and worker share cores), so the cap bounds the worst-case wait.
  private static final int MAX_NEW_TOKENS = 64;
  private static final double CHAT_TEMPERATURE = 0.4D; // livelier than decide()'s 0.0; locked at the prototype

  /**
   * How hard to push the model off words it has just used.
   *
   * A villager's own last line is in the transcript it is asked to continue,
   * and on a 3B at temperature 0.4 that line is the highest-probability thing
   * to say next - so it says it again, verbatim, to any input. Aaron got the
   * same sentence three times in a row to "What?", "I'm good" and "What?".
   *
   * Deliberately mild. The reply is JSON, and a large penalty would be
   * penalising the braces and quotes the parser needs as much as the words.
   */
  private static final double CHAT_REPETITION_PENALTY = 0.3D;

  /** Hotter and pushed harder, for the one retry after a verbatim repeat. */
  private static final double RETRY_TEMPERATURE = 0.85D;
  private static final double RETRY_REPETITION_PENALTY = 0.7D;

  /**
   * Words compared at the head of a reply to decide it is the same answer again.
   *
   * First tried as "the first sentence", which misses the commonest shape: a
   * model whose every reply opens "Ah, Quzzar! ..." has a TWO word first
   * sentence, so a sentence-based rule waves all twelve of them through. A
   * fixed number of leading words does not care where the punctuation falls.
   */
  private static final int OPENER_WORDS = 5;

  /** How long a villager keeps their hands in their pockets after parting with something. */
  private static final long GIVE_COOLDOWN_MS = 5L * 60L * 1000L;

  /** Last give per villager-and-player pair. Runtime only; generosity resets on restart. */
  private static final Map<String, Long> LAST_GIVE = new java.util.concurrent.ConcurrentHashMap<>();
  private static final long HISTORY_EXPIRY_MS = 10 * 60 * 1000;

  private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

  // ---- Conversation lifecycle (consumed by entity goals, e.g. pause+face) ----

  /** A screen the player has open on this villager; refreshed by messages. */
  private record ChatSession(UUID player, long lastActivityMs) {
  }

  private static final Map<Integer, ChatSession> SESSIONS = new ConcurrentHashMap<>();

  /** Backstop for lost close packets (disconnects, crashes). */
  private static final long SESSION_TIMEOUT_MS = 30_000;

  /** Called when the chat screen opens (right-click path). */
  public static void markOpen(RealPerson person, ServerPlayer player) {
    SESSIONS.put(person.getId(), new ChatSession(player.getUUID(), System.currentTimeMillis()));
  }

  /** Called when the client closes the chat screen. */
  public static void markClosed(int entityId, UUID player) {
    SESSIONS.computeIfPresent(entityId, (id, session) -> session.player().equals(player) ? null : session);
  }

  /**
   * True while a player has the chat screen open on this villager (activity
   * within the timeout). The authoritative "in conversation" signal: goals
   * gate on this rather than inventing their own notion.
   */
  public static boolean isConversing(RealPerson person) {
    ChatSession session = SESSIONS.get(person.getId());
    if (session == null) {
      return false;
    }
    if (System.currentTimeMillis() - session.lastActivityMs() > SESSION_TIMEOUT_MS) {
      SESSIONS.remove(person.getId(), session);
      return false;
    }
    return true;
  }

  /** The player currently conversing with this villager, if any. */
  public static Optional<UUID> conversingWith(RealPerson person) {
    return isConversing(person) ? Optional.of(SESSIONS.get(person.getId()).player()) : Optional.empty();
  }

  private PersonChatDispatcher() {
  }

  public static void handle(ServerPlayer player, RealPerson person, String message) {
    if (!IN_FLIGHT.add(player.getUUID())) {
      return; // one chat request in flight per player
    }
    dispatch(person, player, message, message);
  }

  /**
   * The villager speaks first: sent when a chat opens with nothing to show, so
   * a conversation never starts on an empty screen. Stored in history with an
   * empty player line, which the screen renders as a villager-only line.
   */
  private static final String GREETING_CUE = "*walks up to you*";

  public static void greet(RealPerson person, ServerPlayer player) {
    if (!IN_FLIGHT.add(player.getUUID())) {
      return;
    }
    dispatch(person, player, GREETING_CUE, "");
  }

  /**
   * Shared tail: converse, execute any give, answer the player's screen.
   *
   * The in-flight guard is cleared on EVERY outcome, not just success. It used
   * to be released inside thenAccept, so one failed request left the player's
   * entry behind for good, and from then on both greetings and sends were
   * dropped in silence: the villager never spoke and the send button looked
   * broken. A guard that can leak is worse than no guard at all.
   */
  private static void dispatch(RealPerson person, ServerPlayer player, String message, String historyLine) {
    CompletableFuture<Reply> pending;
    try {
      pending = converse(person, player.getGameProfile().getName(), player.getUUID(), message, historyLine);
    } catch (RuntimeException e) {
      IN_FLIGHT.remove(player.getUUID());
      throw e;
    }
    pending.whenComplete((reply, error) -> player.getServer().execute(() -> {
      IN_FLIGHT.remove(player.getUUID());
      if (error != null || reply == null) {
        Villagelife.LOGGER.warn("Chat with {} failed for {}", person.getFullName(),
            player.getGameProfile().getName(), error);
        PacketDistributor.sendToPlayer(player,
            new PersonChatReplyPacket(person.getId(), "..."));
        return;
      }
      if (reply.give() != null && person.isAlive()) {
        executeGive(person, player, reply.give());
      }
      PacketDistributor.sendToPlayer(player, new PersonChatReplyPacket(person.getId(), reply.say()));
    }));
  }

  /**
   * The pipeline core, shared by the right-click packet path and the
   * {@code /vlbrain chat} test command: assemble briefing, ask the LLM, parse,
   * record history. A validated give is returned but NOT executed here — the
   * caller decides (the packet path tosses the item; the command path, which
   * may have no player to toss to, just reports it).
   */
  public static CompletableFuture<Reply> converse(RealPerson person, String speakerName, UUID speakerUUID,
      String message) {
    return converse(person, speakerName, speakerUUID, message, message);
  }

  /**
   * As above, but {@code historyLine} is what gets recorded as the player's
   * side of the exchange: the greeting path passes an empty string, since the
   * player has not said anything yet.
   */
  public static CompletableFuture<Reply> converse(RealPerson person, String speakerName, UUID speakerUUID,
      String message, String historyLine) {
    long askedAtMs = System.currentTimeMillis();
    // Refresh the open session (never create one: console/RCON chats via
    // /vlbrain chat should not make the villager stand at attention).
    SESSIONS.computeIfPresent(person.getId(), (id, session) -> session.player().equals(speakerUUID)
        ? new ChatSession(speakerUUID, System.currentTimeMillis())
        : session);

    // History comes from the persisted attachment (survives restarts, #45);
    // the briefing sees the freshest tail of it.
    List<ChatHistoryData.Exchange> exchanges = person.getData(VillagelifeAttachments.CHAT_HISTORY.get())
        .with(speakerUUID);
    List<Turn> history = exchanges.stream()
        .skip(Math.max(0, exchanges.size() - MAX_HISTORY_TURNS))
        .map(e -> new Turn(e.playerLine(), e.reply()))
        .toList();

    AssembledChat chat = PersonChatContext.assemble(person, speakerName, speakerUUID, history, message);

    // What the villager said last time, so an identical answer can be caught
    // rather than shipped. The penalty above makes the lock less likely; this
    // catches it when it happens anyway, which is the half that does not
    // depend on a model honouring a sampling parameter.
    String lastAnswer = history.isEmpty() ? null : history.get(history.size() - 1).villagerLine();

    return ask(chat, CHAT_TEMPERATURE, CHAT_REPETITION_PENALTY)
        .thenCompose(first -> {
          if (!echoesLastAnswer(first, lastAnswer)) {
            return CompletableFuture.completedFuture(first);
          }
          Villagelife.LOGGER.debug("{} opened exactly as they did last time; sampling once more",
              person.getFullName());
          return ask(chat, RETRY_TEMPERATURE, RETRY_REPETITION_PENALTY)
              .thenApply(second -> echoesLastAnswer(second, lastAnswer) ? first : second);
        })
        .thenApply(result -> {
          String say = null;
          String give = null;
          int opinion = 0;
          if (result.isPresent()) {
            Reply parsed = parseReply(result.get());
            if (parsed != null) {
              say = parsed.say();
              give = parsed.give();
              opinion = parsed.opinionDelta();
            }
          }
          if (say == null || say.isBlank()) {
            say = fallbackLine(person);
            give = null;
            opinion = 0;
          }
          finalizeExchange(person, speakerName, speakerUUID, historyLine, say, give, opinion,
              System.currentTimeMillis() - askedAtMs);
          return new Reply(say, give, opinion);
        });
  }

  private static CompletableFuture<Optional<String>> ask(AssembledChat chat, double temperature,
      double penalty) {
    return LlmService.get().submitChat(chat.system(), chat.user(), chat.examples(),
        MAX_NEW_TOKENS, temperature, penalty);
  }

  /**
   * Whether a raw reply is the villager saying their last answer over again.
   *
   * Compared on the parsed "say" rather than the raw JSON, so a differing
   * "give" or "opinion" does not disguise the same sentence, and loosely
   * enough that trailing punctuation or capitalisation is not a difference.
   *
   * <b>The opening sentence counts on its own.</b> The first version of this
   * compared whole lines, which is what the failure looked like in the sample
   * I had. In real play it takes another shape: the model locks onto an
   * OPENING and varies the tail, so "Just a bit on my mind, that's all. How are
   * you?" is followed by "Just a bit on my mind, that's all. What's in your
   * inventory?" - four such replies in a row, every one of them a distinct
   * line, and a whole-line check waves all of them through.
   */
  private static boolean echoesLastAnswer(Optional<String> raw, String lastAnswer) {
    if (raw == null || raw.isEmpty() || lastAnswer == null || lastAnswer.isBlank()) {
      return false;
    }
    Reply parsed = parseReply(raw.get());
    if (parsed == null || parsed.say() == null || parsed.say().isBlank()) {
      return false; // unparseable is a different problem, with its own fallback
    }
    return sameAnswer(parsed.say(), lastAnswer);
  }

  /** Same whole line, or the same handful of opening words twice running. */
  static boolean sameAnswer(String a, String b) {
    if (flatten(a).equals(flatten(b))) {
      return true;
    }
    String openA = opener(a);
    String openB = opener(b);
    // A reply too short to have an opener is not evidence of anything.
    return !openA.isEmpty() && openA.equals(openB);
  }

  /** The first few words, flattened; empty when the reply is shorter than that. */
  private static String opener(String line) {
    String[] words = flatten(line).split(" ");
    if (words.length < OPENER_WORDS) {
      return "";
    }
    return String.join(" ", java.util.Arrays.copyOfRange(words, 0, OPENER_WORDS));
  }

  private static String flatten(String line) {
    return line.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim()
        .replaceAll(" +", " ");
  }

  /**
   * Server-thread tail of every exchange: persist it to the history
   * attachment, apply the (capped) opinion adjustment, and log — async replies
   * are invisible to RCON sources, so headless capture reads the log.
   */
  private static void finalizeExchange(RealPerson person, String speakerName, UUID speakerUUID,
      String playerLine, String reply, String give, int requestedOpinion, long elapsedMs) {
    var server = person.getServer();
    if (server == null) {
      return;
    }
    server.execute(() -> {
      int applied = applyOpinion(person, speakerUUID, requestedOpinion);
      person.setData(VillagelifeAttachments.CHAT_HISTORY.get(),
          person.getData(VillagelifeAttachments.CHAT_HISTORY.get())
              .withExchange(speakerUUID,
                  new ChatHistoryData.Exchange(playerLine, reply, person.level().getDayTime())));
      Villagelife.LOGGER.info("[chat] ({}ms) {} -> {}: \"{}\"{}{}", elapsedMs, speakerName, person.getFullName(), reply,
          give != null ? " [give: " + give + "]" : "",
          applied != 0 ? " [opinion: " + (applied > 0 ? "+" : "") + applied + "]" : "");
    });
  }

  public record Reply(String say, String give, int opinionDelta) {
  }

  /** Largest single-call opinion step the model may take. */
  private static final int OPINION_STEP_LIMIT = 10;
  /** Largest total swing one conversation window may produce (#44's cap). */
  private static final int OPINION_CONVERSATION_CAP = 15;
  private static final long OPINION_WINDOW_MS = 10 * 60 * 1000;

  private record OpinionBudget(long windowStartMs, int spent) {
  }

  private static final Map<Long, OpinionBudget> OPINION_BUDGETS = new ConcurrentHashMap<>();

  private static final Pattern SAY_PATTERN = Pattern.compile("\"say\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
  private static final Pattern GIVE_PATTERN = Pattern.compile("\"give\"\\s*:\\s*\"([a-z0-9_.:/-]+)\"");
  private static final Pattern OPINION_PATTERN = Pattern.compile("\"opinion\"\\s*:\\s*(-?\\d+)");

  /** Strict-then-regex, the parsing discipline used everywhere in llm-land. */
  private static Reply parseReply(String raw) {
    int start = raw.indexOf('{');
    for (int end : new int[] { raw.indexOf('}', start), raw.lastIndexOf('}') }) {
      if (start >= 0 && end > start) {
        try {
          JsonObject node = JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
          if (node.has("say")) {
            String give = node.has("give") && node.get("give").isJsonPrimitive()
                ? node.get("give").getAsString()
                : null;
            int opinion = 0;
            if (node.has("opinion") && node.get("opinion").isJsonPrimitive()) {
              try {
                opinion = clampStep(node.get("opinion").getAsInt());
              } catch (Exception ignored) {
              }
            }
            return new Reply(node.get("say").getAsString(), give, opinion);
          }
        } catch (Exception ignored) {
          // fall through to the regex pass
        }
      }
    }
    Matcher say = SAY_PATTERN.matcher(raw);
    if (say.find()) {
      Matcher give = GIVE_PATTERN.matcher(raw);
      Matcher opinion = OPINION_PATTERN.matcher(raw);
      return new Reply(say.group(1).replace("\\\"", "\""),
          give.find() ? give.group(1) : null,
          opinion.find() ? clampStep(Integer.parseInt(opinion.group(1))) : 0);
    }
    return null;
  }

  private static int clampStep(int delta) {
    return Math.max(-OPINION_STEP_LIMIT, Math.min(OPINION_STEP_LIMIT, delta));
  }

  /**
   * Applies the model's opinion adjustment to the villager→player opinion
   * store, spending from a bounded per-conversation budget so one chat cannot
   * swing a relationship end to end. Returns the delta actually applied.
   */
  private static int applyOpinion(RealPerson person, UUID speakerUUID, int requestedDelta) {
    if (requestedDelta == 0) {
      return 0;
    }
    long pairKey = ((long) person.getId() << 32) ^ speakerUUID.hashCode();
    long now = System.currentTimeMillis();
    OpinionBudget budget = OPINION_BUDGETS.compute(pairKey, (key, existing) ->
        existing == null || now - existing.windowStartMs() > OPINION_WINDOW_MS
            ? new OpinionBudget(now, 0)
            : existing);
    int remaining = OPINION_CONVERSATION_CAP - Math.abs(budget.spent());
    int applied = Math.max(-remaining, Math.min(remaining, requestedDelta));
    if (applied == 0) {
      return 0;
    }
    OPINION_BUDGETS.put(pairKey, new OpinionBudget(budget.windowStartMs(), budget.spent() + applied));

    var social = person.getData(com.quzzar.villagelife.entities.VillagelifeAttachments.SOCIAL.get());
    Map<UUID, Integer> relationships = new java.util.HashMap<>(social.relationships());
    int updated = Math.max(-100, Math.min(100, relationships.getOrDefault(speakerUUID, 0) + applied));
    relationships.put(speakerUUID, updated);
    person.setData(com.quzzar.villagelife.entities.VillagelifeAttachments.SOCIAL.get(),
        social.withRelationships(relationships));
    return applied;
  }

  /**
   * A give is honoured only for an item actually in the villager's pockets, and
   * only once in a while.
   *
   * The pockets check was the ONLY gate, which turned out not to be a gate at
   * all: the model offers items on ordinary conversational turns - a torch when
   * asked how business is, a diamond when asked what is in her inventory - and
   * anything a villager happened to be carrying could be handed over on any
   * turn. Aaron was given a diamond for asking a question.
   *
   * The prompt now tells them to give only when asked, which helps and cannot
   * be relied on: it is a request to a 3B model, not a rule. This is the rule.
   * A villager parting with something occasionally is the charm; a villager
   * emptying their pockets over a conversation is the bug, and a cooldown
   * bounds the second without touching the first.
   */
  private static void executeGive(RealPerson person, ServerPlayer player, String itemId) {
    long now = System.currentTimeMillis();
    String pair = person.getUUID() + ":" + player.getUUID();
    Long last = LAST_GIVE.get(pair);
    if (last != null && now - last < GIVE_COOLDOWN_MS) {
      Villagelife.LOGGER.debug("Chat give refused, {} already gave something recently",
          person.getFullName());
      return;
    }
    ResourceLocation id = ResourceLocation.tryParse(itemId.contains(":") ? itemId : "minecraft:" + itemId);
    if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
      Villagelife.LOGGER.debug("Chat give rejected, unknown item: {}", itemId);
      return;
    }
    var item = BuiltInRegistries.ITEM.get(id);
    for (int i = 0; i < person.personMainInv.getContainerSize(); i++) {
      ItemStack stack = person.personMainInv.getItem(i);
      if (!stack.isEmpty() && stack.getItem() == item) {
        ItemStack handed = person.personMainInv.removeItem(i, 1);
        ItemEntity drop = new ItemEntity(person.level(), person.getX(), person.getEyeY() - 0.3, person.getZ(), handed);
        Vec3 toward = player.position().add(0, 0.5, 0).subtract(drop.position()).normalize().scale(0.3);
        drop.setDeltaMovement(toward);
        drop.setNoPickUpDelay();
        person.level().addFreshEntity(drop);
        LAST_GIVE.put(pair, now);
        Villagelife.LOGGER.info("[chat] {} handed {} to {}", person.getFullName(), itemId,
            player.getGameProfile().getName());
        return;
      }
    }
    Villagelife.LOGGER.debug("Chat give rejected, not in pockets: {}", itemId);
  }

  private static String fallbackLine(RealPerson person) {
    return person.getFullName() + " looks at you for a long moment, then goes back to their thoughts.";
  }

}
