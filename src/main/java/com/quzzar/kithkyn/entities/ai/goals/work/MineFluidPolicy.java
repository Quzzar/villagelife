package com.quzzar.kithkyn.entities.ai.goals.work;

/** Pure ordering rule for a flooded stretch of shaft. */
final class MineFluidPolicy {

  enum Action {
    SEAL,
    BULKHEAD,
    COFFERDAM,
    BAIL,
    BLOCKED
  }

  private MineFluidPolicy() {
  }

  static Action next(boolean boundaryOpen, boolean boundaryReachable,
      boolean bulkheadReachable, boolean cofferdamReachable, boolean hasBucket) {
    if (boundaryOpen) {
      if (boundaryReachable) {
        return Action.SEAL;
      }
      if (bulkheadReachable) {
        return Action.BULKHEAD;
      }
      return cofferdamReachable ? Action.COFFERDAM : Action.BLOCKED;
    }
    return hasBucket ? Action.BAIL : Action.BLOCKED;
  }
}
