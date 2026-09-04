package com.quzzar.villagelife.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PersonChatIntentTest {

  @Test
  void aHousingQuestionGetsPlanningFactsWithoutBecomingARequest() {
    assertTrue(VillageChangeIntent.discusses("Why aren't there any homes?"));
    assertFalse(VillageChangeIntent.proposes("Why aren't there any homes?"));
  }

  @Test
  void pluralHousingSuggestionsCanReachTheRequestTool() {
    assertTrue(VillageChangeIntent.proposes("We need houses for these people."));
    assertTrue(VillageChangeIntent.proposes("We could use more housing."));
  }

  @Test
  void ordinaryPersonalAdviceDoesNotOpenVillagePlanning() {
    assertFalse(VillageChangeIntent.proposes("You should rest tonight."));
    assertFalse(VillageChangeIntent.discusses("How are you feeling today?"));
  }
}
