package com.quzzar.kithkyn.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class WorkAreaTest {

  @Test
  void tillingRejectsAWorkableCellPastTheFarmEdge() {
    WorkArea farm = new WorkArea(0, 0, 0, 6, 1, 8);

    assertNull(farm.firstInSquare(5, 0, 7, 2,
        position -> position.x() == 7 && position.z() == 7));
    assertEquals(new WorkArea.Position(6, 0, 7), farm.firstInSquare(5, 0, 7, 2,
        position -> position.x() == 6 && position.z() == 7));
  }

  @Test
  void theSameScanWorksForRotatedNegativeWorldBounds() {
    WorkArea farm = new WorkArea(-14, 63, -8, -6, 64, 4);

    assertNull(farm.firstInSquare(-7, 63, -7, 2,
        position -> position.x() == -5));
    assertEquals(new WorkArea.Position(-6, 63, -7), farm.firstInSquare(-7, 63, -7, 2,
        position -> position.x() == -6 && position.z() == -7));
  }
}
