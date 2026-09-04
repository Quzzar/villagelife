package com.quzzar.kithkyn.village.buildings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

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
    POST,
    BEAM_NORTH_SOUTH,
    BEAM_EAST_WEST,
    SLAB,
    TRAPDOOR_NORTH,
    TRAPDOOR_EAST,
    TRAPDOOR_SOUTH,
    TRAPDOOR_WEST,
    LADDER_NORTH,
    LADDER_EAST,
    LADDER_SOUTH,
    LADDER_WEST,
    LANTERN,
    LANTERN_HANGING,
    CAMPFIRE_NORTH,
    CAMPFIRE_EAST,
    CAMPFIRE_SOUTH,
    CAMPFIRE_WEST;
  }

  public BlockPos pos() {
    return BlockPos.of(this.position);
  }

  /** Resolves the catalog's semantic piece through the project's current palette. */
  public BlockState desiredState(WallTier tier) {
    return desiredState(tier, VillageStyle.PLAINS);
  }

  /** Resolves the catalog's semantic piece through the village's regional palette. */
  public BlockState desiredState(WallTier tier, VillageStyle style) {
    WoodWallPalette wood = WoodWallPalette.forStyle(style);
    return switch (this.piece) {
      case BODY -> tier == WallTier.STONE
          ? tier.block().defaultBlockState()
          : strippedLog(wood, Direction.Axis.Y);
      case WALKWAY -> tier == WallTier.STONE
          ? Blocks.STONE_BRICKS.defaultBlockState()
          : wood.planks().defaultBlockState();
      case PARAPET -> tier == WallTier.STONE
          ? Blocks.STONE_BRICK_WALL.defaultBlockState()
          : wood.fence().defaultBlockState();
      case STEP_NORTH -> stair(tier, wood, Direction.NORTH);
      case STEP_EAST -> stair(tier, wood, Direction.EAST);
      case STEP_SOUTH -> stair(tier, wood, Direction.SOUTH);
      case STEP_WEST -> stair(tier, wood, Direction.WEST);
      case POST -> strippedLog(wood, Direction.Axis.Y);
      case BEAM_NORTH_SOUTH -> strippedLog(wood, Direction.Axis.Z);
      case BEAM_EAST_WEST -> strippedLog(wood, Direction.Axis.X);
      case SLAB -> wood.slab().defaultBlockState()
          .setValue(SlabBlock.TYPE, SlabType.TOP);
      case TRAPDOOR_NORTH -> trapdoor(wood, Direction.NORTH);
      case TRAPDOOR_EAST -> trapdoor(wood, Direction.EAST);
      case TRAPDOOR_SOUTH -> trapdoor(wood, Direction.SOUTH);
      case TRAPDOOR_WEST -> trapdoor(wood, Direction.WEST);
      case LADDER_NORTH -> ladder(Direction.NORTH);
      case LADDER_EAST -> ladder(Direction.EAST);
      case LADDER_SOUTH -> ladder(Direction.SOUTH);
      case LADDER_WEST -> ladder(Direction.WEST);
      case LANTERN -> Blocks.LANTERN.defaultBlockState();
      case LANTERN_HANGING -> Blocks.LANTERN.defaultBlockState()
          .setValue(LanternBlock.HANGING, Boolean.TRUE);
      case CAMPFIRE_NORTH -> campfire(Direction.NORTH);
      case CAMPFIRE_EAST -> campfire(Direction.EAST);
      case CAMPFIRE_SOUTH -> campfire(Direction.SOUTH);
      case CAMPFIRE_WEST -> campfire(Direction.WEST);
    };
  }

  private static BlockState strippedLog(WoodWallPalette palette, Direction.Axis axis) {
    return palette.strippedLog().defaultBlockState()
        .setValue(RotatedPillarBlock.AXIS, axis);
  }

  private static BlockState stair(WallTier tier, WoodWallPalette palette, Direction facing) {
    BlockState state = (tier == WallTier.STONE ? Blocks.STONE_BRICK_STAIRS : palette.stairs())
        .defaultBlockState();
    return state.setValue(StairBlock.FACING, facing);
  }

  private static BlockState trapdoor(WoodWallPalette palette, Direction facing) {
    return palette.trapdoor().defaultBlockState()
        .setValue(TrapDoorBlock.FACING, facing)
        .setValue(TrapDoorBlock.HALF, Half.TOP)
        .setValue(TrapDoorBlock.OPEN, Boolean.TRUE)
        .setValue(TrapDoorBlock.POWERED, Boolean.FALSE);
  }

  private static BlockState ladder(Direction facing) {
    return Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, facing);
  }

  private static BlockState campfire(Direction facing) {
    return Blocks.CAMPFIRE.defaultBlockState()
        .setValue(CampfireBlock.FACING, facing)
        .setValue(CampfireBlock.LIT, Boolean.TRUE);
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

  static Piece trapdoorPiece(Direction direction) {
    return switch (direction) {
      case NORTH -> Piece.TRAPDOOR_NORTH;
      case EAST -> Piece.TRAPDOOR_EAST;
      case SOUTH -> Piece.TRAPDOOR_SOUTH;
      case WEST -> Piece.TRAPDOOR_WEST;
      default -> throw new IllegalArgumentException("Wall trapdoors must face horizontally");
    };
  }

  static Piece ladderPiece(Direction direction) {
    return switch (direction) {
      case NORTH -> Piece.LADDER_NORTH;
      case EAST -> Piece.LADDER_EAST;
      case SOUTH -> Piece.LADDER_SOUTH;
      case WEST -> Piece.LADDER_WEST;
      default -> throw new IllegalArgumentException("Wall ladders must face horizontally");
    };
  }

  static Piece campfirePiece(Direction direction) {
    return switch (direction) {
      case NORTH -> Piece.CAMPFIRE_NORTH;
      case EAST -> Piece.CAMPFIRE_EAST;
      case SOUTH -> Piece.CAMPFIRE_SOUTH;
      case WEST -> Piece.CAMPFIRE_WEST;
      default -> throw new IllegalArgumentException("Wall campfires must face horizontally");
    };
  }
}
