package com.quzzar.villagelife.village.buildings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** One persistent construction cell emitted by a wall-segment catalog. */
public record WallBlockPlan(long position, Piece piece, WallCellRole role) {

  public enum Piece {
    BODY,
    WALKWAY,
    STEP_NORTH,
    STEP_EAST,
    STEP_SOUTH,
    STEP_WEST,
    PARAPET,
    DOOR_LOWER_NORTH,
    DOOR_LOWER_EAST,
    DOOR_LOWER_SOUTH,
    DOOR_LOWER_WEST;
  }

  public BlockPos pos() {
    return BlockPos.of(this.position);
  }

  /** Resolves the catalog's semantic piece through the project's current palette. */
  public BlockState desiredState(WallTier tier) {
    return switch (this.piece) {
      case BODY -> tier.block().defaultBlockState();
      case WALKWAY -> tier == WallTier.STONE
          ? Blocks.STONE_BRICKS.defaultBlockState()
          : Blocks.OAK_PLANKS.defaultBlockState();
      case PARAPET -> tier == WallTier.STONE
          ? Blocks.STONE_BRICK_WALL.defaultBlockState()
          : Blocks.OAK_FENCE.defaultBlockState();
      case STEP_NORTH -> stair(tier, Direction.NORTH);
      case STEP_EAST -> stair(tier, Direction.EAST);
      case STEP_SOUTH -> stair(tier, Direction.SOUTH);
      case STEP_WEST -> stair(tier, Direction.WEST);
      case DOOR_LOWER_NORTH -> doorState(Direction.NORTH, DoubleBlockHalf.LOWER);
      case DOOR_LOWER_EAST -> doorState(Direction.EAST, DoubleBlockHalf.LOWER);
      case DOOR_LOWER_SOUTH -> doorState(Direction.SOUTH, DoubleBlockHalf.LOWER);
      case DOOR_LOWER_WEST -> doorState(Direction.WEST, DoubleBlockHalf.LOWER);
    };
  }

  private static BlockState stair(WallTier tier, Direction facing) {
    BlockState state = (tier == WallTier.STONE ? Blocks.STONE_BRICK_STAIRS : Blocks.OAK_STAIRS)
        .defaultBlockState();
    return state.setValue(StairBlock.FACING, facing);
  }

  private static BlockState doorState(Direction facing, DoubleBlockHalf half) {
    return Blocks.OAK_DOOR.defaultBlockState()
        .setValue(DoorBlock.FACING, facing)
        .setValue(DoorBlock.HALF, half)
        .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
        .setValue(DoorBlock.OPEN, Boolean.FALSE)
        .setValue(DoorBlock.POWERED, Boolean.FALSE);
  }

  static Piece step(Direction direction) {
    return switch (direction) {
      case NORTH -> Piece.STEP_NORTH;
      case EAST -> Piece.STEP_EAST;
      case SOUTH -> Piece.STEP_SOUTH;
      case WEST -> Piece.STEP_WEST;
      default -> Piece.WALKWAY;
    };
  }

  static Piece doorPiece(Direction direction) {
    return switch (direction) {
      case NORTH -> Piece.DOOR_LOWER_NORTH;
      case EAST -> Piece.DOOR_LOWER_EAST;
      case SOUTH -> Piece.DOOR_LOWER_SOUTH;
      case WEST -> Piece.DOOR_LOWER_WEST;
      default -> throw new IllegalArgumentException("Wall doors must face horizontally");
    };
  }
}
