package com.quzzar.kithkyn.chat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.quzzar.kithkyn.chat.PersonChatContext.Turn;
import com.quzzar.kithkyn.entities.PersonalLogData;

class PersonChatTimelineTest {

  @Test
  void placesAPickupBetweenTheConversationTurnsThatSurroundedIt() {
    List<Turn> turns = List.of(
        new Turn("Could you spare some dirt?", "I can give you twelve.", 100L),
        new Turn("I did not receive it.", "That is strange.", 120L));
    PersonalLogData.Entry pickup = PersonalLogData.pickup(
        "minecraft:dirt", 12, 110L, 110L, Optional.empty());

    String timeline = PersonChatContext.recentTimeline(
        turns, List.of(pickup), "Aaron", ignored -> "someone", 5);

    int offer = timeline.indexOf("Could you spare some dirt?");
    int pickedUp = timeline.indexOf("You picked up 12 dirt from the ground.");
    int missing = timeline.indexOf("I did not receive it.");
    assertTrue(offer >= 0 && offer < pickedUp && pickedUp < missing, timeline);
  }
}
