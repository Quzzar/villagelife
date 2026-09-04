package com.quzzar.kithkyn.village.buildings;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** One regional wood family applied to the canonical authored wall geometry. */
record WoodWallPalette(Block strippedLog, Block planks, Block stairs, Block slab,
    Block fence, Block trapdoor) {

  static WoodWallPalette forStyle(VillageStyle style) {
    return switch (style) {
      case TAIGA, SNOWY -> new WoodWallPalette(
          Blocks.STRIPPED_SPRUCE_LOG,
          Blocks.SPRUCE_PLANKS,
          Blocks.SPRUCE_STAIRS,
          Blocks.SPRUCE_SLAB,
          Blocks.SPRUCE_FENCE,
          Blocks.SPRUCE_TRAPDOOR);
      case DESERT, SAVANNA -> new WoodWallPalette(
          Blocks.STRIPPED_ACACIA_LOG,
          Blocks.ACACIA_PLANKS,
          Blocks.ACACIA_STAIRS,
          Blocks.ACACIA_SLAB,
          Blocks.ACACIA_FENCE,
          Blocks.ACACIA_TRAPDOOR);
      case PLAINS -> new WoodWallPalette(
          Blocks.STRIPPED_OAK_LOG,
          Blocks.OAK_PLANKS,
          Blocks.OAK_STAIRS,
          Blocks.OAK_SLAB,
          Blocks.OAK_FENCE,
          Blocks.OAK_TRAPDOOR);
    };
  }

  /** Whether a block belongs to any regional form of the authored wooden wall. */
  static boolean isWallWood(Block block) {
    for (VillageStyle style : VillageStyle.values()) {
      WoodWallPalette palette = forStyle(style);
      if (block == palette.strippedLog
          || block == palette.planks
          || block == palette.stairs
          || block == palette.slab
          || block == palette.fence
          || block == palette.trapdoor) {
        return true;
      }
    }
    return false;
  }
}
