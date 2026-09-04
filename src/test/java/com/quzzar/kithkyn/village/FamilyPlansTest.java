package com.quzzar.kithkyn.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class FamilyPlansTest {

  @Test
  void pairIdentityIsCanonicalAndBirthStateIsExplicit() {
    UUID lower = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID higher = UUID.fromString("00000000-0000-0000-0000-000000000002");
    FamilyPlans.Plan talk = new FamilyPlans.Plan(higher, lower, 4L, -1L);
    FamilyPlans.Plan birth = new FamilyPlans.Plan(lower, higher, Long.MAX_VALUE, 5L);

    assertEquals(lower, talk.first());
    assertEquals(higher, talk.second());
    assertFalse(talk.hasPendingBirth());
    assertTrue(birth.hasPendingBirth());
    assertEquals(talk.key(), birth.key());
  }
}
