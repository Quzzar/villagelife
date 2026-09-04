package com.quzzar.kithkyn.village.buildings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class WallTerrainTest {

  @Test
  void readsTheTerrainBelowAStandingTree() {
    Set<Integer> sturdyTerrain = Set.of(70);

    assertEquals(71, WallTerrain.surfaceY(78, -64, sturdyTerrain::contains));
  }

  @Test
  void onlyNaturalSoilWithAnExposedSideIsEmbedded() {
    assertEquals(true, WallTerrain.shouldEmbedSurface(true, false, true));
    assertEquals(false, WallTerrain.shouldEmbedSurface(true, false, false));
    assertEquals(false, WallTerrain.shouldEmbedSurface(false, false, true));
    assertEquals(false, WallTerrain.shouldEmbedSurface(true, true, true));
  }

  @Test
  void vegetationBufferWrapsBothSidesOfAJoinedWallRun() {
    Set<Long> occupied = Set.of(
        BlockPos.asLong(10, 0, 20),
        BlockPos.asLong(11, 0, 20));

    Set<Long> buffer = WallTerrain.horizontalBuffer(occupied);

    assertEquals(10, buffer.size());
    assertTrue(buffer.contains(BlockPos.asLong(10, 0, 19)));
    assertTrue(buffer.contains(BlockPos.asLong(10, 0, 21)));
    assertTrue(buffer.contains(BlockPos.asLong(11, 0, 19)));
    assertTrue(buffer.contains(BlockPos.asLong(11, 0, 21)));
    assertFalse(buffer.contains(BlockPos.asLong(10, 0, 20)));
    assertFalse(buffer.contains(BlockPos.asLong(11, 0, 20)));
  }

  @Test
  void treeClearanceIncludesTheWallAndReachesThreeBlocks() {
    long wallColumn = BlockPos.asLong(10, 0, 20);

    Set<Long> reach = WallTerrain.horizontalReach(Set.of(wallColumn), 3);

    assertEquals(49, reach.size());
    assertTrue(reach.contains(wallColumn));
    assertTrue(reach.contains(BlockPos.asLong(7, 0, 17)));
    assertTrue(reach.contains(BlockPos.asLong(13, 0, 23)));
    assertFalse(reach.contains(BlockPos.asLong(6, 0, 20)));
    assertFalse(reach.contains(BlockPos.asLong(10, 0, 24)));
  }
}
