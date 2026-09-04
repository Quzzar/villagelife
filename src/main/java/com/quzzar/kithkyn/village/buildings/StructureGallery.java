package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import com.quzzar.kithkyn.Kithkyn;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Places every loaded building definition side by side on labelled plinths, so a
 * whole catalogue can be walked end to end and compared. Built for reviewing
 * candidate structures (docs/structure-sourcing.md) and for checking a content
 * pass, not for anything the simulation uses.
 *
 * <p>Buildings are grouped by category and then by level, so a category's
 * progression reads left to right and unrelated categories never interleave.
 */
public class StructureGallery {

  /** Empty blocks left between plinths, so neighbours never read as one build. */
  private static final int AISLE = 6;

  /** Plinths per row before the gallery wraps to a new one. */
  private static final int PER_ROW = 6;

  /** One entry in the layout: a definition and the footprint it needs. */
  private record Plot(BuildingInfo info, StructureTemplate template, int sizeX, int sizeZ) {}

  /**
   * Builds the gallery with its north-west corner at {@code origin}.
   *
   * @return the number of definitions placed, or -1 if none are loaded
   */
  public static int build(ServerLevel level, BlockPos origin, Random random) {
    List<Plot> plots = collectPlots(level);
    if (plots.isEmpty()) {
      return -1;
    }

    int placed = 0;
    int cursorX = 0;
    int cursorZ = 0;
    int rowDepth = 0;
    int inRow = 0;

    for (Plot plot : plots) {
      if (inRow == PER_ROW) {
        cursorX = 0;
        cursorZ += rowDepth + AISLE;
        rowDepth = 0;
        inRow = 0;
      }

      BlockPos corner = origin.offset(cursorX, 0, cursorZ);
      layPlinth(level, corner, plot.sizeX(), plot.sizeZ());
      placeLabel(level, corner.offset(0, 1, -2), plot.info());

      if (place(level, plot.info(), corner, plot.sizeX(), plot.sizeZ(), random)) {
        placed++;
      }

      cursorX += plot.sizeX() + AISLE;
      rowDepth = Math.max(rowDepth, plot.sizeZ());
      inRow++;
    }

    return placed;
  }

  /** Loads every definition's template, dropping any whose structure file is missing. */
  private static List<Plot> collectPlots(ServerLevel level) {
    List<Plot> plots = new ArrayList<>();
    for (BuildingInfo info : Buildings.allBuildings().values()) {
      ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, info.getPath());
      StructureTemplate template = level.getStructureManager().get(id).orElse(null);
      if (template == null) {
        Kithkyn.LOGGER.warn("Gallery: no structure file for {}, skipping", info.getName());
        continue;
      }
      Vec3i size = template.getSize();
      plots.add(new Plot(info, template, size.getX(), size.getZ()));
    }

    plots.sort(Comparator.comparing((Plot p) -> p.info().getCategory())
        .thenComparing(p -> p.info().getVariant())
        .thenComparingInt(p -> p.info().getLevel()));
    return plots;
  }

  /** A one-block stone slab under the footprint, so builds sit level regardless of terrain. */
  private static void layPlinth(ServerLevel level, BlockPos corner, int sizeX, int sizeZ) {
    for (int x = -1; x <= sizeX; x++) {
      for (int z = -1; z <= sizeZ; z++) {
        level.setBlock(corner.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        level.setBlock(corner.offset(x, 0, z), Blocks.AIR.defaultBlockState(), 2);
      }
    }
  }

  /** A sign in front of each plinth carrying the definition's id and shape. */
  private static void placeLabel(ServerLevel level, BlockPos pos, BuildingInfo info) {
    level.setBlock(pos.below(), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
    level.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState(), 3);
    if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
      SignText text = sign.getFrontText()
          .setMessage(0, Component.literal(info.getCategory()))
          .setMessage(1, Component.literal(info.getVariant()))
          .setMessage(2, Component.literal("level " + info.getLevel()))
          .setMessage(3, Component.literal(info.getBedLocations().size() + " bed, "
              + info.getWorkLocations().size() + " job"));
      sign.setText(text, true);
      sign.setChanged();
    }
  }

  /** Places one definition through the normal instant-build path. */
  private static boolean place(ServerLevel level, BuildingInfo info, BlockPos corner,
      int sizeX, int sizeZ, Random random) {
    Building building = new Building(corner, info.getName(), Rotation.NONE);
    BlockPos center = corner.offset(sizeX / 2, 0, sizeZ / 2);
    InstantBuildStructure structure =
        new InstantBuildStructure(building, random, level).setOriginLocation(center, new HashSet<>());
    return structure.buildInstantly();
  }

}
