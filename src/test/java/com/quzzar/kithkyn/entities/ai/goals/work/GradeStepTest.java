package com.quzzar.kithkyn.entities.ai.goals.work;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

class GradeStepTest {

  private static final BlockPos CUT = new BlockPos(1, 70, 1);

  @Test
  void cannotCutAThinCapOverTwoBlocksOfAir() {
    BlockGetter ground = new Ground(Map.of(
        CUT, Blocks.GRASS_BLOCK.defaultBlockState(),
        CUT.below(3), Blocks.GRASS_BLOCK.defaultBlockState()));

    assertFalse(GradeStep.hasSupportBelowCut(ground, CUT),
        "Removing the cap would expose y=67 ground instead of lowering the surface to y=69");
  }

  @Test
  void canLowerSolidGroundOneBlock() {
    BlockGetter ground = new Ground(Map.of(
        CUT, Blocks.GRASS_BLOCK.defaultBlockState(),
        CUT.below(), Blocks.STONE.defaultBlockState()));

    assertTrue(GradeStep.hasSupportBelowCut(ground, CUT));
  }

  @Test
  void cannotExposeWaterBeneathTheCut() {
    BlockGetter ground = new Ground(Map.of(
        CUT, Blocks.GRASS_BLOCK.defaultBlockState(),
        CUT.below(), Blocks.WATER.defaultBlockState()));

    assertFalse(GradeStep.hasSupportBelowCut(ground, CUT));
  }

  @Test
  void partialBlockBelowDoesNotSupplyTheAssumedStandingHeight() {
    BlockGetter ground = new Ground(Map.of(
        CUT, Blocks.GRASS_BLOCK.defaultBlockState(),
        CUT.below(), Blocks.STONE_SLAB.defaultBlockState()));

    assertFalse(GradeStep.hasSupportBelowCut(ground, CUT));
  }

  @Test
  void canRefillAnEarlierPartialCutTowardTheStableTarget() {
    assertTrue(GradeStep.withinBudget(65, 64, 1, false));
  }

  @Test
  void ordinaryGroundCannotMovePastItsOriginalBudget() {
    assertFalse(GradeStep.withinBudget(65, 62, -1, false));
    assertFalse(GradeStep.withinBudget(65, 68, 1, false));
  }

  @Test
  void aBuildingApronMayStillReachItsRequiredRamp() {
    assertTrue(GradeStep.withinBudget(65, 60, -1, true));
    assertTrue(GradeStep.withinBudget(65, 70, 1, true));
  }

  @Test
  void fillApproachMustStandOnFinishedGroundNearTheWork() {
    assertTrue(GradeStep.safePlacementLevel(88, 89, false));
    assertFalse(GradeStep.safePlacementLevel(88, 88, true));
    assertFalse(GradeStep.safePlacementLevel(88, 93, false));
  }

  /** Real Minecraft block states and collision shapes in a small deterministic column. */
  private record Ground(Map<BlockPos, BlockState> blocks) implements BlockGetter {

    @Override
    public BlockState getBlockState(BlockPos pos) {
      return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
      return null;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
      return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
      return 384;
    }

    @Override
    public int getMinBuildHeight() {
      return -64;
    }
  }

}
