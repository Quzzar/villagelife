package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.List;

/** Pure progress policy for postponing unreachable wall segments without losing them. */
final class WallProgress {

  /** The untouched ring cursor and the earlier indexes waiting for another try. */
  record State(int cursor, List<Integer> deferred) {

    State {
      deferred = List.copyOf(deferred);
    }
  }

  private WallProgress() {
  }

  static boolean isComplete(State state, int ringSize) {
    return state.cursor() >= ringSize && state.deferred().isEmpty();
  }

  static int currentIndex(State state, int ringSize) {
    return state.cursor() < ringSize ? state.cursor() : state.deferred().get(0);
  }

  static State advance(State state, int ringSize) {
    if (state.cursor() < ringSize) {
      return new State(state.cursor() + 1, state.deferred());
    }
    if (state.deferred().isEmpty()) {
      return state;
    }
    return new State(state.cursor(), state.deferred().subList(1, state.deferred().size()));
  }

  static State defer(State state, int ringSize) {
    List<Integer> deferred = new ArrayList<>(state.deferred());
    if (state.cursor() < ringSize) {
      deferred.add(state.cursor());
      return new State(state.cursor() + 1, deferred);
    }
    if (!deferred.isEmpty()) {
      deferred.add(deferred.remove(0));
    }
    return new State(state.cursor(), deferred);
  }
}
