package com.quzzar.kithkyn.entities.ai.goals.work;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.quzzar.kithkyn.village.buildings.MineShaft;

import net.minecraft.core.BlockPos;

class MineTopologyTest {

  @Test
  void plannedRibEntranceIsInteriorRatherThanRampLining() {
    BlockPos rampEdge = new BlockPos(2, -10, 8);
    BlockPos ribEntrance = new BlockPos(3, -10, 8);

    assertTrue(MineTopology.isRamp(rampEdge));
    assertTrue(MineTopology.isRib(ribEntrance));
    assertTrue(MineTopology.isInterior(ribEntrance));
    assertFalse(MineTopology.crossesExteriorBoundary(rampEdge, ribEntrance));
  }

  @Test
  void branchWorkPositionBelongsToTheNavigableMine() {
    BlockPos fourBlocksAlongRib = new BlockPos(6, -10, 8);

    assertTrue(MineShaft.withinExcavation(fourBlocksAlongRib));
  }

  @Test
  void offsetStepBesideFloodedRampCellIsExteriorLining() {
    BlockPos floodedRampCell = new BlockPos(-2, -3, 5);
    BlockPos offsetLeak = new BlockPos(-2, -3, 6);

    assertTrue(MineTopology.isRamp(floodedRampCell));
    assertFalse(MineTopology.isInterior(offsetLeak));
    assertTrue(MineTopology.crossesExteriorBoundary(floodedRampCell, offsetLeak));
  }

  @Test
  void ribOuterEdgeStillCrossesIntoExteriorLining() {
    BlockPos ribEnd = new BlockPos(10, -10, 8);
    BlockPos beyondRib = new BlockPos(11, -10, 8);

    assertTrue(MineTopology.isRib(ribEnd));
    assertTrue(MineTopology.crossesExteriorBoundary(ribEnd, beyondRib));
  }

  @Test
  void floodedRibCanBeSacrificedAtItsDoorwayWithoutBlockingTheRamp() {
    BlockPos floodedRibEnd = new BlockPos(-10, -10, 8);

    assertEquals(new BlockPos(-3, -10, 8), MineTopology.ribDoorway(floodedRibEnd));
    assertFalse(MineTopology.isRamp(MineTopology.ribDoorway(floodedRibEnd)));
    assertNull(MineTopology.ribDoorway(new BlockPos(-2, -10, 8)));
  }

  @Test
  void floodedRibIsIndependentFromTheDescendingRamp() {
    BlockPos rampEdge = new BlockPos(-2, -10, 8);
    BlockPos ribDoorway = new BlockPos(-3, -10, 8);

    assertFalse(MineTopology.sameFront(rampEdge, ribDoorway));
  }

  @Test
  void eachRibIsAnIndependentExcavationFront() {
    BlockPos leftRib = new BlockPos(-4, -10, 8);
    BlockPos fartherAlongLeftRib = new BlockPos(-8, -10, 8);
    BlockPos rightRib = new BlockPos(4, -10, 8);
    BlockPos deeperLeftRib = new BlockPos(-4, -14, 12);

    assertTrue(MineTopology.sameFront(leftRib, fartherAlongLeftRib));
    assertFalse(MineTopology.sameFront(leftRib, rightRib));
    assertFalse(MineTopology.sameFront(leftRib, deeperLeftRib));
  }

  @Test
  void dryRampCellsRemainOneExcavationFront() {
    BlockPos upperRamp = new BlockPos(2, -10, 8);
    BlockPos lowerRamp = new BlockPos(-2, -13, 11);

    assertTrue(MineTopology.sameFront(upperRamp, lowerRamp));
  }

  @Test
  void floodedRibDoesNotMarkConnectedRampSupportAsFloodWork() {
    BlockPos rampSupport = new BlockPos(2, -10, 8);
    BlockPos ribDoorwaySupport = new BlockPos(3, -10, 8);
    BlockPos ribWater = new BlockPos(4, -10, 8);
    Set<BlockPos> supports = Set.of(rampSupport, ribDoorwaySupport);

    assertFalse(MineTopology.supportClusterTouchesWater(
        rampSupport, supports::contains, ribWater::equals, 128));
  }

  @Test
  void rampWorkingFrontStillRemainsUntilItsOwnWaterIsCleared() {
    BlockPos upperSupport = new BlockPos(0, -10, 8);
    BlockPos lowerSupport = new BlockPos(0, -10, 9);
    BlockPos rampWater = new BlockPos(0, -10, 10);
    Set<BlockPos> supports = Set.of(upperSupport, lowerSupport);

    assertTrue(MineTopology.supportClusterTouchesWater(
        upperSupport, supports::contains, rampWater::equals, 128));
  }
}
