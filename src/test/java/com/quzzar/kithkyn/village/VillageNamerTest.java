package com.quzzar.kithkyn.village;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VillageNamerTest {

  @Test
  void foundingPromptUsesOnlyNaturalLocationContext() {
    assertEquals(
        "A new settlement is being founded in the windswept hills landscape. "
            + "Give it a place-name inspired only by the natural surroundings and terrain.",
        VillageNamer.foundingPrompt("windswept hills"));
  }
}
