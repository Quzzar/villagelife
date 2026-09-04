package com.quzzar.kithkyn.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConversationStagnationTest {

  @Test
  void endsAnAlternatingParaphraseLoop() {
    ConversationStagnation progress = new ConversationStagnation(2);

    assertFalse(progress.record(0, "I am bouncing on my toes while you clear the way for Mosswood."));
    assertFalse(progress.record(1, "Keep bouncing; I will clear the way and make Mosswood safe."));
    assertFalse(progress.record(0, "I keep bouncing on my toes as you clear Mosswood's way."));
    assertFalse(progress.record(1, "Keep those toes bouncing while I clear the way for safe Mosswood."));
    assertFalse(progress.record(0, "I am still bouncing while you make the way clear for Mosswood."));
    assertFalse(progress.record(1, "Bounce on; I shall keep clearing Mosswood's way."));
    assertFalse(progress.record(0, "My toes keep bouncing as you clear the way."));

    assertTrue(progress.record(1, "Keep bouncing while I clear the way through Mosswood."));
  }

  @Test
  void newInformationResetsTheStaleRun() {
    ConversationStagnation progress = new ConversationStagnation(2);

    progress.record(0, "I repaired the eastern gate before breakfast.");
    progress.record(1, "The eastern gate looks sturdy after your repair.");
    progress.record(0, "I repaired that eastern gate carefully before breakfast.");
    progress.record(1, "Your careful repair made the eastern gate sturdy.");
    progress.record(0, "Tomorrow I will ask the sawyer for cedar boards.");
    progress.record(1, "Cedar boards should weather the coming winter well.");

    assertFalse(progress.record(0, "I will oil the hinges after the next rain."));
    assertFalse(progress.record(1, "Oil will keep rust from those hinges."));
  }
}
