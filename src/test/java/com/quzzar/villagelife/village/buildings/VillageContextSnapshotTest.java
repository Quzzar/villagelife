package com.quzzar.villagelife.village.buildings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.quzzar.villagelife.village.PopulationOutlook;

class VillageContextSnapshotTest {

  @Test
  void separatesAdultHomelessnessFromPreAdultsWithoutFamilyHomes() {
    VillageContextSnapshot snapshot = new VillageContextSnapshot(
        "Emberhollow", "hamlet", 11, 6, 2,
        4, 0, 4, 0,
        6, 0, 0, 1,
        Map.of("fishery", 3, "house", 1), Map.of("fisher", 2),
        PopulationOutlook.CAN_GROW, 5.5D, false,
        Optional.empty(), Optional.empty(), List.of(), List.of(), Optional.empty());

    String chat = snapshot.chatBriefing();
    assertTrue(chat.contains("4 are pre-adults; working teenagers also appear in the job counts"));
    assertTrue(chat.contains("1 adult resident needs independent housing"));
    assertTrue(chat.contains("None of the 4 pre-adults has a usable resident parent's home"));
    assertTrue(chat.contains("4 have no resident parent recorded"));
    assertTrue(chat.contains("Those children stay by the campfire at night"));
    assertFalse(chat.contains("4 pre-adults are dependently housed"));

    String planner = snapshot.plannerBriefing();
    assertTrue(planner.contains("1 adult resident is already unhoused"));
    assertFalse(planner.contains("5 people are already unhoused"));
  }

  @Test
  void reportsValidDependentHousingWithoutInventingSeparateBeds() {
    VillageContextSnapshot snapshot = new VillageContextSnapshot(
        "Emberhollow", "hamlet", 3, 2, 0,
        1, 1, 0, 0,
        2, 0, 0, 0,
        Map.of("house", 1), Map.of(), PopulationOutlook.HOLDING, 4.0D, false,
        Optional.empty(), Optional.empty(), List.of(), List.of(), Optional.empty());

    String chat = snapshot.chatBriefing();
    assertTrue(chat.contains("no adult residents need independent housing"));
    assertTrue(chat.contains("The pre-adult is housed with a resident parent"));
  }
}
