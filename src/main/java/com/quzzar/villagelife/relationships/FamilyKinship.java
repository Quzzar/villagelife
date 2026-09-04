package com.quzzar.villagelife.relationships;

import java.util.List;
import java.util.UUID;

import com.quzzar.villagelife.entities.RealPerson;

/** Close-family rules shared by marriage eligibility and family travel. */
public final class FamilyKinship {

  private FamilyKinship() {
  }

  /** Parent-child and siblings, including half-siblings, may never marry. */
  public static boolean areCloseFamily(RealPerson first, RealPerson second) {
    return areCloseFamily(
        first.getUUID(), first.getParentIds(), second.getUUID(), second.getParentIds());
  }

  /** Pure form used by tests and callers that already hold parent ids. */
  static boolean areCloseFamily(
      UUID firstId, List<UUID> firstParents, UUID secondId, List<UUID> secondParents) {
    if (firstParents.contains(secondId) || secondParents.contains(firstId)) {
      return true;
    }
    for (UUID parent : firstParents) {
      if (secondParents.contains(parent)) {
        return true;
      }
    }
    return false;
  }
}
