package com.quzzar.villagelife.village.buildings;

import java.util.Random;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.VillageManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public class LocationValidator {

  public static final int SEARCH_RADIUS_PER_4_BUILDINGS = 20;
  public static final int SEARCH_INTERVAL = 2;

  public static final int[] SEARCH_HEIGHTS = new int[]{0, 1, -1, 2, -2, 3, -3, 4, -4};

  public static BlockPos findValidLocation(BlockPos centerPos, BoundingBox bounds, Village village, Random random) {

    // n = ((SEARCH_RADIUS_PER_4_BUILDINGS * buildingsCount*0.25) / SEARCH_INTERVAL) * SEARCH_HEIGHTS.length
    int searchRadius = SEARCH_RADIUS_PER_4_BUILDINGS * (int) Math.ceil(village.getBuildings().size() / 4.0D);

    for (int rel_y : SEARCH_HEIGHTS) {
      for (int n = 1; n <= searchRadius; n += SEARCH_INTERVAL) {

        int rel_x = (int) (Math.max(random.nextInt(n), random.nextInt(n)) + village.getTownCenter().getRadius());
        int rel_z = (int) (Math.max(random.nextInt(n), random.nextInt(n)) + village.getTownCenter().getRadius());

        if (random.nextBoolean()) {
          rel_x *= -1;
        }
        if (random.nextBoolean()) {
          rel_z *= -1;
        }

        Villagelife.LOGGER.debug("Checking at: "+rel_x+", "+rel_z);

        boolean isValid = isValidPlacement(centerPos, bounds, village, rel_x, rel_y, rel_z);
        if (isValid) {
          return centerPos.offset(rel_x, rel_y, rel_z);
        }

      }
    }

    return BlockPos.ZERO;

  }

  private static boolean isValidPlacement(BlockPos centerPos, BoundingBox bounds, Village village, int rel_x, int rel_y, int rel_z) {

    BlockPos center = bounds.getCenter();

    // Check if center is valid
    if (!LocationValidator.isValidLocation(VillageManager.getLevelAccessor(), village, centerPos.offset(rel_x + center.getX(), rel_y, rel_z + center.getZ()))) {
      return false;
    }

    // Check if four corners are valid
    if (!LocationValidator.isValidLocation(VillageManager.getLevelAccessor(), village, centerPos.offset(rel_x + center.getX()*0.5, rel_y, rel_z + center.getZ()*0.5))) {
      return false;
    }
    if (!LocationValidator.isValidLocation(VillageManager.getLevelAccessor(), village, centerPos.offset(rel_x + center.getX()*1.5, rel_y, rel_z + center.getZ()*1.5))) {
      return false;
    }
    if (!LocationValidator.isValidLocation(VillageManager.getLevelAccessor(), village, centerPos.offset(rel_x + center.getX()*0.5, rel_y, rel_z + center.getZ()*1.5))) {
      return false;
    }
    if (!LocationValidator.isValidLocation(VillageManager.getLevelAccessor(), village, centerPos.offset(rel_x + center.getX()*1.5, rel_y, rel_z + center.getZ()*0.5))) {
      return false;
    }

    // Check if sides are valid
    for (int x = bounds.minX(); x <= bounds.maxX(); x++) {

      if (!LocationValidator.isValidLocation(VillageManager.getLevelAccessor(), village,
          centerPos.offset(rel_x + x, rel_y, rel_z + bounds.minZ()))) {
        return false;
      }

    }

    for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {

      if (!LocationValidator.isValidLocation(VillageManager.getLevelAccessor(), village,
          centerPos.offset(rel_x + bounds.minX(), rel_y, rel_z + z))) {
        return false;
      }

    }

    for (int x = bounds.minX(); x <= bounds.maxX(); x++) {

      if (!LocationValidator.isValidLocation(VillageManager.getLevelAccessor(), village,
          centerPos.offset(rel_x + x, rel_y, rel_z + bounds.maxZ()))) {
        return false;
      }

    }

    for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {

      if (!LocationValidator.isValidLocation(VillageManager.getLevelAccessor(), village,
          centerPos.offset(rel_x + bounds.maxX(), rel_y, rel_z + z))) {
        return false;
      }

    }

    return true;

  }

  public static boolean isValidLocation(ServerLevelAccessor levelAccess, Village village, BlockPos loc) {

    // TODO, remove

    if (village.hasClaimed(loc)) {
      // Claimed.
      //VillageManager.getLevelAccessor().setBlock(loc, Blocks.RED_WOOL.defaultBlockState(), 2);
      return false;
    }

    double d = levelAccess.getBlockFloorHeight(loc);
    if (d >= 0) {
      BlockState blockState = levelAccess.getBlockState(loc.above(2));
      if (blockState != null) {
        if(!blockState.getMaterial().isSolid() || blockState.getBlock() instanceof LeavesBlock){
          // Block not solid (like grass) or leaves
          //VillageManager.getLevelAccessor().setBlock(loc, Blocks.GREEN_WOOL.defaultBlockState(), 2);
          return true;
        } else {
          // Block is solid, ground
          //VillageManager.getLevelAccessor().setBlock(loc, Blocks.MAGENTA_WOOL.defaultBlockState(), 2);
          return false;
        }
        //return !blockState.getMaterial().isSolid(); Use this instead <-
      } else {
        // No block state, good? Air?
        //VillageManager.getLevelAccessor().setBlock(loc, Blocks.BLUE_WOOL.defaultBlockState(), 2);
        return true;
      }
    } else {
      // D too large?
      //VillageManager.getLevelAccessor().setBlock(loc, Blocks.ORANGE_WOOL.defaultBlockState(), 2);
      return false;
    }

  }

  public static double getBuildingRadius(BoundingBox bounds){
    return Math.max(bounds.getXSpan()/2, bounds.getZSpan()/2);
  }



}
