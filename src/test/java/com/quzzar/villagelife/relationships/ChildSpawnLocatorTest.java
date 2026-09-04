package com.quzzar.villagelife.relationships;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class ChildSpawnLocatorTest {

  @Test
  void findsSupportedSpaceAboveAMidpointRoundedIntoTheFloor() {
    BlockPos midpoint = new BlockPos(100, 63, 200);
    BlockPos safe = midpoint.above();

    Optional<BlockPos> found = ChildCreationService.findNearbySpawnPosition(
        midpoint, safe::equals);

    assertEquals(Optional.of(safe), found);
  }

  @Test
  void searchesBeyondTheImmediateEightNeighbors() {
    BlockPos midpoint = new BlockPos(100, 64, 200);
    BlockPos safe = midpoint.offset(2, 0, 0);

    Optional<BlockPos> found = ChildCreationService.findNearbySpawnPosition(
        midpoint, safe::equals);

    assertEquals(Optional.of(safe), found);
  }

  @Test
  void refusesToReturnTheBlockedMidpointWhenNoNearbyPositionIsSafe() {
    Optional<BlockPos> found = ChildCreationService.findNearbySpawnPosition(
        new BlockPos(100, 64, 200), ignored -> false);

    assertTrue(found.isEmpty());
  }
}
