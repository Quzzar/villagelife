package com.quzzar.kithkyn.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.quzzar.kithkyn.Kithkyn;

/**
 * The one turn-taking engine behind every conversation a village holds among its
 * own minds: two villagers gossiping, a quartermaster and the brain shelving a
 * storehouse, a betrothed pair settling their married name. Each of those is the
 * same shape, and this is that shape once: a set of voices take turns, a
 * transcript grows, and the talk ends when someone takes their leave, when a
 * result is reached, or when the turns run out.
 *
 * <p>The engine owns only the loop. What a voice actually says, and what a reply
 * means, is the caller's, expressed as a {@link Protocol}: it produces each turn
 * (building the prompt, calling the model, reading the answer) and tells the
 * engine how the turn ended. The engine chains those turns on their futures,
 * caps the round count, and returns the result, if any. Nothing here reaches a
 * model or touches the world; a protocol does both, on whatever thread and lane
 * it chooses, and hops back to the server thread for anything that changes the
 * world before it completes its turn.
 *
 * <p>The reactive player-to-villager conversation is deliberately NOT driven
 * from here: a human supplies each of its turns, so there is no loop for the
 * engine to run. It shares the single-turn core ({@link PersonChatDispatcher})
 * with the autonomous conversations instead, which is the seam that already
 * existed. This engine unifies the ones the village drives itself.
 *
 * @param <R> the result a conversation resolves to (a shelving plan, a chosen
 *            name); {@link Void} for a social talk that only ends, never resolves.
 */
public final class Dialogue {

  /** How one turn ended, and so how the conversation continues (or does not). */
  public enum End {
    /** The speaker said their piece; the next voice takes over. */
    CONTINUE,
    /** The speaker took their leave; the conversation ends with no result. */
    LEAVE,
    /** The speaker reached the result the conversation was for; it ends with it. */
    RESOLVED,
    /** The turn produced nothing usable (a dead model, a pair drifted apart); it ends with no result. */
    ABORT
  }

  /**
   * One voice's turn. Built through the factory methods rather than by hand, so
   * the {@link End} and the payload can never disagree.
   */
  public record Turn<R>(End end, String line, Optional<R> resolution) {

    /** The speaker said {@code line}; the talk goes on. */
    public static <R> Turn<R> spoke(String line) {
      return new Turn<>(End.CONTINUE, line == null ? "" : line, Optional.empty());
    }

    /** The speaker said {@code line} and took their leave; the talk ends here. */
    public static <R> Turn<R> leave(String line) {
      return new Turn<>(End.LEAVE, line == null ? "" : line, Optional.empty());
    }

    /** The conversation reached its result; the talk ends with it. */
    public static <R> Turn<R> resolved(R result) {
      return new Turn<>(End.RESOLVED, "", Optional.of(result));
    }

    /** The turn gave nothing to work with; the talk ends with no result. */
    public static <R> Turn<R> abort() {
      return new Turn<>(End.ABORT, "", Optional.empty());
    }
  }

  /** The running record of what has been said, in order, for a protocol that wants the talk so far in its prompt. */
  public static final class Transcript {

    private final List<String> lines = new ArrayList<>();

    /** Records a spoken line, ignoring the blank ones a protocol may hand back on a turn it does not narrate. */
    private void record(String line) {
      if (line != null && !line.isBlank()) {
        lines.add(line.strip());
      }
    }

    /** Every line said so far, oldest first. */
    public List<String> lines() {
      return List.copyOf(lines);
    }

    public boolean isEmpty() {
      return lines.isEmpty();
    }

    public int size() {
      return lines.size();
    }

    /** The most recent line, or an empty string when no one has spoken yet. */
    public String lastLine() {
      return lines.isEmpty() ? "" : lines.get(lines.size() - 1);
    }
  }

  /**
   * How a particular conversation produces and reads each turn. The engine owns
   * the loop; this owns the content and the meaning of a reply.
   *
   * @param <R> the result this conversation can resolve to
   */
  public interface Protocol<R> {

    /** How many voices take part; turns go round these in order. */
    int voices();

    /** The hard ceiling on turns, so a conversation the model never ends still stops. */
    int maxTurns();

    /**
     * Produce the next turn: the {@code speaker} (0-based, cycling through the
     * voices) answers, given the talk so far. {@code lastChance} is true on the
     * final permitted turn, so a protocol that must land a result can force it.
     * The future may complete on any thread; hop to the server thread inside for
     * anything that touches the world.
     */
    CompletableFuture<Turn<R>> takeTurn(int speaker, Transcript transcript, boolean lastChance);
  }

  private Dialogue() {
  }

  /**
   * Runs a conversation to its end and completes with its result, if it reached
   * one. Never completes exceptionally: a turn that throws or fails ends the
   * conversation with no result, the same as a model that had nothing to say, so
   * a caller's own fallback stands.
   */
  public static <R> CompletableFuture<Optional<R>> run(Protocol<R> protocol) {
    if (protocol.voices() <= 0 || protocol.maxTurns() <= 0) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    return step(protocol, new Transcript(), 0);
  }

  private static <R> CompletableFuture<Optional<R>> step(Protocol<R> protocol, Transcript transcript, int turnIndex) {
    if (turnIndex >= protocol.maxTurns()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    int speaker = turnIndex % protocol.voices();
    boolean lastChance = turnIndex >= protocol.maxTurns() - 1;
    CompletableFuture<Turn<R>> pending;
    try {
      pending = protocol.takeTurn(speaker, transcript, lastChance);
    } catch (RuntimeException error) {
      Kithkyn.LOGGER.warn("[dialogue] a turn threw before it began; ending the conversation", error);
      return CompletableFuture.completedFuture(Optional.empty());
    }
    return pending.thenCompose(turn -> {
      if (turn == null || turn.end() == End.ABORT) {
        return CompletableFuture.completedFuture(Optional.<R>empty());
      }
      if (turn.end() == End.RESOLVED) {
        return CompletableFuture.completedFuture(turn.resolution());
      }
      transcript.record(turn.line());
      if (turn.end() == End.LEAVE) {
        return CompletableFuture.completedFuture(Optional.<R>empty());
      }
      return step(protocol, transcript, turnIndex + 1);
    }).exceptionally(error -> {
      Kithkyn.LOGGER.warn("[dialogue] a turn failed; ending the conversation", error);
      return Optional.<R>empty();
    });
  }
}
