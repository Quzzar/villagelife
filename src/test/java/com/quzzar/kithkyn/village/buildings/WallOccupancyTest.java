package com.quzzar.kithkyn.village.buildings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class WallOccupancyTest {

  @Test
  void collidableBlocksSatisfyOrdinaryWallCells() {
    assertTrue(WallOccupancy.isSatisfied(true, false));
    assertFalse(WallOccupancy.isSatisfied(false, false));
  }

  @Test
  void clearableVegetationNeverSatisfiesAPlannedWallCell() {
    assertFalse(WallOccupancy.isSatisfied(true, false, true));
  }

  @Test
  void anExplicitTierUpgradeReopensItsPreviousWallCells() {
    assertFalse(WallOccupancy.isSatisfied(true, true));
  }

  @Test
  void onlyOpenCellsNeedWallBlocks() {
    Set<Integer> occupied = Set.of(71, 73);

    assertEquals(List.of(72, 74), WallOccupancy.missingHeights(71, 74, occupied::contains));
  }

  @Test
  void aFullyOccupiedColumnNeedsNoWallBlocks() {
    assertEquals(List.of(), WallOccupancy.missingHeights(71, 73, ignored -> true));
  }
}
