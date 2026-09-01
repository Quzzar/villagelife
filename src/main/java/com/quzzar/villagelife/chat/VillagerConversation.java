package com.quzzar.villagelife.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.VillagelifeAttachments;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.networking.VillagerSpeechPacket;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A conversation between two VILLAGERS, driven by the same
 * {@link PersonChatDispatcher#converse} pipeline a player uses. Each turn, one
 * villager's reply becomes the line the other answers, so everything the chat
 * system already does per-interlocutor simply happens between the two of them:
 * transcripts and close-of-session summaries on both sides, undertakings
 * opened and advanced with each other, items handed over with {@code give},
 * opinions moved through OpinionService (a resident lands on the relationship
 * pair), and village {@code request}s filed when one talks the other into one.
 *
 * <p>The driver's own job is small: alternate the turns, keep both parties
 * standing (the shared chat session, which PauseForConversationGoal reads),
 * speak each line aloud to players in earshot, and stop. It stops when the
 * line budget runs out, when either party dies or drifts out of range, when a
 * turn takes longer than the session timeout (the background LLM lane is busy;
 * the pair returns to their lives rather than standing frozen), or when the
 * model has nothing to say. Every end closes the same way a screen-close does:
 * one summary each, so the talk becomes a memory.
 *
 * <p>Turns ride the background LLM lane (LlmService.submitBackgroundChat), so
 * villager chatter never delays a player's reply or a village decision, and
 * {@link #MAX_ACTIVE} bounds how much of that lane gossip may occupy at once.
 */
public final class VillagerConversation {

  /**
   * Spoken lines in one conversation: the opener plus alternating replies.
   * Always even, so the conversation ends on an answer, not a question left
   * hanging. Two lengths so talks do not all feel metronome-identical.
   */
  private static final int MIN_LINES = 4;

  /** How close two villagers stand to talk; drifting past it ends the talk. */
  private static final double TALK_RANGE = 6.0D;

  /** How far a spoken line carries to watching players, in blocks. */
  private static final double EARSHOT = 16.0D;

  /**
   * Quiet time each participant gets after a conversation before the seek
   * goal considers them again. In-memory only: a restart forgiving cooldowns
   * is harmless, and persisting them would be bookkeeping for its own sake.
   */
  private static final long COOLDOWN_MS = 5 * 60 * 1000;

  /**
   * Conversations running at once, server-wide. One at a time keeps each
   * turn's wait on the shared background lane short (a slow turn ends the
   * talk, see the session timeout), and keeps an evening of village gossip
   * from crowding out personas and reflection.
   */
  private static final int MAX_ACTIVE = 1;

  /**
   * Minimum quiet time between conversation STARTS, server-wide. The
   * per-villager cooldown paces who talks; this paces how often anyone
   * talks, which is what bounds LLM spend on a billed cloud provider no
   * matter how large the population grows. Without it the first live run
   * chained conversations back to back the moment the previous one ended.
   */
  private static final long MIN_START_GAP_MS = 3 * 60 * 1000;

  private static volatile long lastStartMs = 0L;

  /**
   * The initiator's opening cue, attributed to the other villager the way the
   * player greeting cue is: the approached party looks up, and the approacher
   * speaks first. Recorded with an empty history line, so transcripts show
   * the opener as unprompted speech, exactly like a screen-open greeting.
   */
  private static final String OPENER_CUE = "*looks up as you approach*";

  private static final AtomicInteger ACTIVE = new AtomicInteger();

  /** When each villager last finished a conversation, for the cooldown. */
  private static final Map<UUID, Long> LAST_ENDED = new ConcurrentHashMap<>();

  private VillagerConversation() {
  }

  /**
   * Whether this villager could join a conversation right now: alive, not
   * already in one, and past their cooldown. The seek goal asks this about
   * itself and about candidate partners.
   */
  public static boolean readyToTalk(RealPerson person) {
    return person.isAlive()
        && !PersonChatDispatcher.isConversing(person)
        && System.currentTimeMillis() - LAST_ENDED.getOrDefault(person.getUUID(), 0L) >= COOLDOWN_MS;
  }

  /** Whether a conversation may start now: a free slot, and past the start gap. */
  public static boolean hasCapacity() {
    return ACTIVE.get() < MAX_ACTIVE
        && System.currentTimeMillis() - lastStartMs >= MIN_START_GAP_MS;
  }

  /**
   * Starts a conversation with {@code initiator} speaking first, if both
   * parties are free, close enough, and the LLM is up. Returns whether it
   * started; a false is not an error, just a moment that did not happen.
   * Server thread only.
   */
  public static boolean tryStart(RealPerson initiator, RealPerson partner) {
    return tryStart(initiator, partner, false);
  }

  /**
   * As above; {@code force} (the dev command) skips the server-wide start
   * gap, never the busy/capacity checks. A forced start still stamps the
   * gap, so it counts toward pacing like any other.
   */
  public static boolean tryStart(RealPerson initiator, RealPerson partner, boolean force) {
    if (!VillagelifeConfig.LlmVillagerConversations || !LlmService.get().isReady()) {
      return false;
    }
    if (!force && System.currentTimeMillis() - lastStartMs < MIN_START_GAP_MS) {
      return false;
    }
    if (initiator == partner || !initiator.isAlive() || !partner.isAlive()) {
      return false;
    }
    if (initiator.distanceToSqr(partner) > TALK_RANGE * TALK_RANGE) {
      return false;
    }
    if (PersonChatDispatcher.isConversing(initiator) || PersonChatDispatcher.isConversing(partner)) {
      return false;
    }
    if (ACTIVE.incrementAndGet() > MAX_ACTIVE) {
      ACTIVE.decrementAndGet();
      return false;
    }

    // A conversation is a session bounded by the Minecraft day, both ways:
    // yesterday's transcript was summarized when it closed, so today starts
    // fresh from the memory (the same rule RealPerson.openChat applies).
    freshen(initiator, partner.getUUID());
    freshen(partner, initiator.getUUID());

    PersonChatDispatcher.markTalking(initiator, partner.getUUID());
    PersonChatDispatcher.markTalking(partner, initiator.getUUID());
    lastStartMs = System.currentTimeMillis();

    int lines = MIN_LINES + 2 * initiator.getRandom().nextInt(2);
    Villagelife.LOGGER.info("[villager chat] {} strikes up a conversation with {} ({} lines at most)",
        initiator.getFullName(), partner.getFullName(), lines);
    takeTurn(initiator, partner, OPENER_CUE, "", lines);
    return true;
  }

  /**
   * One turn: {@code speaker} answers {@code message} (the listener's last
   * line, or the opener cue), the reply is spoken aloud and any give is
   * executed, then the roles swap. The chain runs on futures, hopping back to
   * the server thread for everything that touches entities; the shared chat
   * session is refreshed each completed turn, so a turn the LLM cannot serve
   * within the session timeout lets both parties walk away on their own.
   */
  private static void takeTurn(RealPerson speaker, RealPerson listener, String message,
      String historyLine, int linesLeft) {
    try {
      PersonChatDispatcher
          .converse(speaker, listener.getFullName(), listener.getUUID(), message, historyLine, true)
          .whenComplete((reply, error) -> {
            MinecraftServer server = speaker.getServer();
            if (server == null) {
              finish(speaker, listener);
              return;
            }
            server.execute(() -> {
              if (error != null || reply == null || PersonChatDispatcher.isFallback(speaker, reply.say())) {
                finish(speaker, listener);
                return;
              }
              if (!stillTogether(speaker, listener)) {
                finish(speaker, listener);
                return;
              }
              speak(speaker, reply.say());
              if (reply.give() != null) {
                PersonChatDispatcher.executeGive(speaker, listener, reply.give(), reply.giveCount());
              }
              if (linesLeft <= 1) {
                finish(speaker, listener);
                return;
              }
              PersonChatDispatcher.markTalking(speaker, listener.getUUID());
              PersonChatDispatcher.markTalking(listener, speaker.getUUID());
              takeTurn(listener, speaker, reply.say(), reply.say(), linesLeft - 1);
            });
          });
    } catch (RuntimeException e) {
      // Logged, not rethrown: a throw here would land in the goal tick and
      // take the server down for a line of gossip.
      finish(speaker, listener);
      Villagelife.LOGGER.warn("[villager chat] turn failed between {} and {}",
          speaker.getFullName(), listener.getFullName(), e);
    }
  }

  /**
   * Whether the pair is still in this conversation with each other: both
   * alive, both sessions pointing at one another (which is also where the
   * session timeout is felt), and still within talking range. Panic, combat,
   * or a death mid-talk fails this and the conversation ends cleanly.
   */
  private static boolean stillTogether(RealPerson a, RealPerson b) {
    return a.isAlive() && b.isAlive()
        && PersonChatDispatcher.conversingWith(a).filter(b.getUUID()::equals).isPresent()
        && PersonChatDispatcher.conversingWith(b).filter(a.getUUID()::equals).isPresent()
        && a.distanceToSqr(b) <= TALK_RANGE * TALK_RANGE;
  }

  /**
   * Every end runs through here exactly once per conversation: free the
   * capacity slot, start both cooldowns, release both from standing still,
   * and fold the talk into each side's memory of the other, the same summary
   * a screen-close writes.
   */
  private static void finish(RealPerson a, RealPerson b) {
    ACTIVE.decrementAndGet();
    long now = System.currentTimeMillis();
    LAST_ENDED.put(a.getUUID(), now);
    LAST_ENDED.put(b.getUUID(), now);
    PersonChatDispatcher.markClosed(a.getId(), b.getUUID());
    PersonChatDispatcher.markClosed(b.getId(), a.getUUID());
    if (a.getServer() != null) {
      PersonChatDispatcher.summarizeSession(a, b.getUUID(), b.getFullName());
      PersonChatDispatcher.summarizeSession(b, a.getUUID(), a.getFullName());
    }
    Villagelife.LOGGER.info("[villager chat] {} and {} part ways", a.getFullName(), b.getFullName());
  }

  /** Drops a stale (earlier-day) transcript with this counterpart, keeping today's. */
  private static void freshen(RealPerson person, UUID counterpart) {
    ChatHistoryData history = person.getData(VillagelifeAttachments.CHAT_HISTORY.get());
    if (history.staleFor(counterpart, person.level().getDayTime())) {
      person.setData(VillagelifeAttachments.CHAT_HISTORY.get(), history.clearedFor(counterpart));
    }
  }

  /**
   * Speaks a line aloud: players within earshot see it briefly above the
   * speaker's head, keeping ambient villager talk in the world instead of
   * filling the player's chat transcript.
   */
  private static void speak(RealPerson speaker, String line) {
    if (!(speaker.level() instanceof ServerLevel level)) {
      return;
    }
    for (ServerPlayer player : level.players()) {
      if (player.distanceToSqr(speaker) <= EARSHOT * EARSHOT) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
            new VillagerSpeechPacket(speaker.getId(), line));
      }
    }
  }

  /**
   * Dev-only: makes a villager speak a raw line right now, cleaned exactly as a
   * generated line would be ({@link VillagerText#clean}). Lets the speech-bubble
   * render and the em-dash strip be verified in any world without standing up a
   * live LLM conversation.
   */
  public static void speakTest(RealPerson speaker, String raw) {
    speak(speaker, VillagerText.clean(raw));
  }

}
