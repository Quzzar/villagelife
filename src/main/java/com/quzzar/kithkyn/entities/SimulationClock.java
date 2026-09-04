package com.quzzar.kithkyn.entities;

import java.util.OptionalLong;

/** Monotonic elapsed-time checks shared by persisted simulation records. */
public final class SimulationClock {

  private SimulationClock() {
  }

  /** Empty when the stored timestamp is not on the same monotonic timeline. */
  public static OptionalLong elapsed(long now, long then) {
    return then < 0L || now < then ? OptionalLong.empty() : OptionalLong.of(now - then);
  }

  /** Whether a past timestamp still falls within a positive recency window. */
  public static boolean isRecent(long now, long then, long window) {
    OptionalLong elapsed = elapsed(now, then);
    return elapsed.isPresent() && elapsed.getAsLong() < window;
  }
}
