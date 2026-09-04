package com.quzzar.kithkyn.entities.ai.goals.work;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WallStepTest {

  @Test
  void choosesAnotherInteriorFootholdWhenATreeBlocksThePreferredOne() {
    WallWorkPlanner.Offset openSide = new WallWorkPlanner.Offset(0, 1);

    WallWorkPlanner.Offset chosen = WallWorkPlanner.choose(-1, 1, openSide::equals);

    assertEquals(openSide, chosen);
  }
}
