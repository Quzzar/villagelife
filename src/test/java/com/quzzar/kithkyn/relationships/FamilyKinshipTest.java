package com.quzzar.kithkyn.relationships;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class FamilyKinshipTest {

  private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID PARENT = UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Test
  void rejectsParentChildInEitherDirection() {
    assertTrue(FamilyKinship.areCloseFamily(FIRST, List.of(), SECOND, List.of(FIRST)));
    assertTrue(FamilyKinship.areCloseFamily(FIRST, List.of(SECOND), SECOND, List.of()));
  }

  @Test
  void rejectsFullAndHalfSiblings() {
    assertTrue(FamilyKinship.areCloseFamily(FIRST, List.of(PARENT), SECOND, List.of(PARENT)));
  }

  @Test
  void unrelatedAdultsRemainEligible() {
    assertFalse(FamilyKinship.areCloseFamily(FIRST, List.of(), SECOND, List.of()));
  }
}
