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

  /** Shared tail: converse, execute any give, answer the player's screen. */
  private static void dispatch(RealPerson person, ServerPlayer player, String message, String historyLine) {
    converse(person, player.getGameProfile().getName(), player.getUUID(), message, historyLine)
        .thenAccept(reply -> player.getServer().execute(() -> {
          IN_FLIGHT.remove(player.getUUID());
          String say = reply.say();
          if (reply.give() != null && person.isAlive()) {
            executeGive(person, player, reply.give());
          }
          PacketDistributor.sendToPlayer(player, new PersonChatReplyPacket(person.getId(), say));
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

    return LlmService.get().submitChat(chat.system(), chat.user(), chat.examples(), MAX_NEW_TOKENS, CHAT_TEMPERATURE)
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
          finalizeExchange(person, speakerName, speakerUUID, historyLine, say, give, opinion);
          return new Reply(say, give, opinion);
        });
  }

  /**
   * Server-thread tail of every exchange: persist it to the history
   * attachment, apply the (capped) opinion adjustment, and log — async replies
   * are invisible to RCON sources, so headless capture reads the log.
   */
  private static void finalizeExchange(RealPerson person, String speakerName, UUID speakerUUID,
      String playerLine, String reply, String give, int requestedOpinion) {
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
      Villagelife.LOGGER.info("[chat] {} -> {}: \"{}\"{}{}", speakerName, person.getFullName(), reply,
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

  /** A give is honored only for an item actually in the villager's pockets. */
  private static void executeGive(RealPerson person, ServerPlayer player, String itemId) {
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
        return;
      }
    }
    Villagelife.LOGGER.debug("Chat give rejected, not in pockets: {}", itemId);
  }

  private static String fallbackLine(RealPerson person) {
    return person.getFullName() + " looks at you for a long moment, then goes back to their thoughts.";
  }

}
