package com.quzzar.villagelife.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
 * A conversation between two VILLAGERS, run on the shared {@link Dialogue}
 * engine and voiced through the same {@link PersonChatDispatcher#converse}
 * pipeline a player uses. Each turn, one villager's reply becomes the line the
 * other answers, so everything the chat system already does per-interlocutor
 * simply happens between the two of them: transcripts and close-of-session
 * summaries on both sides, undertakings opened and advanced with each other,
 * items handed over with {@code give}, opinions moved through OpinionService (a
 * resident lands on the relationship pair), and village {@code request}s filed
 * when one talks the other into one.
 *
 * <p>This class's own job is small: the lifecycle around a talk. It keeps both
 * parties standing (the shared chat session, which PauseForConversationGoal
 * reads), speaks each line aloud to players in earshot, and, when the engine
 * says the talk is over, closes it. The {@link Exchange} protocol supplies each
 * turn; the engine chains them. It stops when either party takes their leave
 * ({@code "done": true} on the reply, the model's own call, the same flag a
 * player's screen honours), when either party dies or drifts out of range, when
 * a turn takes longer than the session timeout (the background LLM lane is busy;
 * the pair returns to their lives rather than standing frozen), when one answers
 * words with blows, or when the model has nothing to say. There is no budget of
 * lines and no clock on the talk itself (Aaron, 2026-09-02: let them talk until
 * they want to stop, and if they yap, so be it); {@link Exchange#maxTurns()} is
 * a backstop only, never reached in an ordinary talk. Every end closes the same
 * way a screen-close does: one summary each, so the talk becomes a memory.
 *
 * <p>Turns ride the background LLM lane (LlmService.submitBackgroundChat), so
 * villager chatter never delays a player's reply or a village decision, and
 * {@link #MAX_ACTIVE} bounds how much of that lane gossip may occupy at once.
 */
public final class VillagerConversation {

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
   * Quiet time before the SAME two villagers may talk again, beyond the
   * per-villager cooldown each already serves. A guard that walked over to its
   * best-liked neighbour and talked will, on its next free moment, pick that
   * same neighbour again; without a floor between one PAIR's talks they fixate
   * on each other and stand about (Aaron, 2026-09-02). The per-villager
   * cooldown above is longer, so this only bites once a guard's own watch
   * (GuardPatrolGoal) has it chatting seldom enough that it would otherwise
   * re-pick the same partner.
   */
  private static final long PAIR_COOLDOWN_MS = 3 * 60 * 1000;

  /**
   * Conversations running at once, server-wide. One at a time keeps each
   * turn's wait on the shared background lane short (a slow turn ends the
   * talk, see the session timeout), and keeps an evening of village gossip
   * from crowding out personas and reflection.
   */
  private static final int MAX_ACTIVE = 1;

  /**
   * A backstop ceiling on turns, so a pair the model never lets finish still
   * stops one day. A real talk ends long before this on {@code done}, drift, or
   * the session timeout; there is deliberately no shorter budget of lines.
   */
  private static final int SAFETY_TURN_CAP = 400;

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

  /** When each PAIR last finished a conversation, keyed order-independently, for the pair cooldown. */
  private static final Map<String, Long> PAIR_LAST_ENDED = new ConcurrentHashMap<>();

  /** An order-independent key for a pair, so (a,b) and (b,a) are one entry. */
  private static String pairKey(RealPerson a, RealPerson b) {
    UUID x = a.getUUID();
    UUID y = b.getUUID();
    return x.compareTo(y) <= 0 ? x + "|" + y : y + "|" + x;
  }

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
    // These two talked recently: let them find someone else rather than fixate.
    if (!force && System.currentTimeMillis()
        - PAIR_LAST_ENDED.getOrDefault(pairKey(initiator, partner), 0L) < PAIR_COOLDOWN_MS) {
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

    Villagelife.LOGGER.info("[villager chat] {} strikes up a conversation with {}",
        initiator.getFullName(), partner.getFullName());
    Dialogue.run(new Exchange(initiator, partner)).whenComplete((result, error) -> {
      MinecraftServer server = initiator.getServer();
      if (server != null) {
        server.execute(() -> finish(initiator, partner));
      } else {
        finish(initiator, partner);
      }
    });
    return true;
  }

  /**
   * The two villagers as a {@link Dialogue.Protocol}: turn zero is the initiator
   * answering the opener cue, and thereafter each voice answers the other's last
   * line through {@link PersonChatDispatcher#converse}. The talk never resolves
   * to a value ({@code R} is {@link Void}); it only continues, leaves, or aborts,
   * and the lifecycle around it is closed by {@link #finish} when the engine is
   * done. Every turn's world-touching work is hopped to the server thread.
   */
  private static final class Exchange implements Dialogue.Protocol<Void> {

    private final RealPerson initiator;
    private final RealPerson partner;

    private Exchange(RealPerson initiator, RealPerson partner) {
      this.initiator = initiator;
      this.partner = partner;
    }

    @Override
    public int voices() {
      return 2;
    }

    @Override
    public int maxTurns() {
      return SAFETY_TURN_CAP;
    }

    @Override
    public CompletableFuture<Dialogue.Turn<Void>> takeTurn(int speakerIndex, Dialogue.Transcript transcript,
        boolean lastChance) {
      RealPerson speaker = speakerIndex % 2 == 0 ? initiator : partner;
      RealPerson listener = speaker == initiator ? partner : initiator;
      String message = transcript.isEmpty() ? OPENER_CUE : transcript.lastLine();
      String historyLine = transcript.isEmpty() ? "" : transcript.lastLine();

      CompletableFuture<Dialogue.Turn<Void>> turn = new CompletableFuture<>();
      MinecraftServer server = speaker.getServer();
      if (server == null) {
        turn.complete(Dialogue.Turn.abort());
        return turn;
      }
      // converse reads world and entity state synchronously before it hands off
      // to the LLM lane, so it is called on the server thread, not from the
      // engine's off-thread continuation; the reply's effects then hop back.
      server.execute(() -> PersonChatDispatcher
          .converse(speaker, listener.getFullName(), listener.getUUID(), message, historyLine, true)
          .whenComplete((reply, error) -> server.execute(() -> {
            try {
              if (error != null || reply == null || PersonChatDispatcher.isFallback(speaker, reply.say())) {
                turn.complete(Dialogue.Turn.abort());
                return;
              }
              if (!stillTogether(speaker, listener)) {
                turn.complete(Dialogue.Turn.abort());
                return;
              }
              speak(speaker, reply.say());
              if (reply.give() != null) {
                PersonChatDispatcher.executeGive(speaker, listener, reply.give(), reply.giveCount());
              }
              // Words answered with blows end the talk: the same decision a
              // villager may make with a player, made about a neighbour.
              if (reply.fight()) {
                speaker.pickFightWith(listener);
                turn.complete(Dialogue.Turn.abort());
                return;
              }
              // The speaker takes their leave: their own call, made in the
              // reply, and the farewell has just been spoken.
              if (reply.done()) {
                Villagelife.LOGGER.info("[villager chat] {} takes their leave of {}",
                    speaker.getFullName(), listener.getFullName());
                turn.complete(Dialogue.Turn.leave(reply.say()));
                return;
              }
              PersonChatDispatcher.markTalking(speaker, listener.getUUID());
              PersonChatDispatcher.markTalking(listener, speaker.getUUID());
              turn.complete(Dialogue.Turn.spoke(reply.say()));
            } catch (RuntimeException e) {
              Villagelife.LOGGER.warn("[villager chat] turn failed between {} and {}",
                  speaker.getFullName(), listener.getFullName(), e);
              turn.complete(Dialogue.Turn.abort());
            }
          })));
      return turn;
    }
  }

  /**
   * Whether the pair is still in this conversation with each other: both
   * alive, neither past their bedtime, both sessions pointing at one another
   * (which is also where the session timeout is felt), and still within
   * talking range. Panic, combat, or a death mid-talk fails this and the
   * conversation ends cleanly.
   */
  private static boolean stillTogether(RealPerson a, RealPerson b) {
    return a.isAlive() && b.isAlive()
        && !pastBedtime(a) && !pastBedtime(b)
        && PersonChatDispatcher.conversingWith(a).filter(b.getUUID()::equals).isPresent()
        && PersonChatDispatcher.conversingWith(b).filter(a.getUUID()::equals).isPresent()
        && a.distanceToSqr(b) <= TALK_RANGE * TALK_RANGE;
  }

  /**
   * Night has fallen on a villager who sleeps at night: their bed outranks the
   * talk, so the pair parts and {@link #finish} folds it into memory the same
   * as any other end. A guard never reaches bedtime and will talk till dawn; a
   * chat that ran past dusk used to freeze a sleeper in place all night, since
   * PauseForConversationGoal outranks SleepAtNightGoal.
   */
  private static boolean pastBedtime(RealPerson person) {
    return person.level().isNight() && person.getOccupation().sleepsAtNight();
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
    PAIR_LAST_ENDED.put(pairKey(a, b), now);
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
   * filling the player's chat transcript. Shared by every conversation a
   * village overhears, this one and the brain-convened deliberations alike.
   */
  public static void speak(RealPerson speaker, String line) {
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
