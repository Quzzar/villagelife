package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.quzzar.villagelife.village.Village;

import net.minecraft.world.item.ItemStack;

public class UrbanPlanner {

  public static BuildingInfo getNextProject(Village village){

    List<BuildingInfo> bestBuildings = findBestBuildingList();
    for(BuildingInfo build : bestBuildings){

      if(hasMaterialsToConstruct(village, build)){
        return build;
      }

    }

    return null;

  }

  private static boolean hasMaterialsToConstruct(Village village, BuildingInfo build){
    for(ItemStack itemCost : build.getMaterialCost()){
      if(!village.hasItemStackInVillage(itemCost)){
        return false;
      }
    }
    return true;
  }

  public static boolean payForBuilding(Village village, BuildingInfo build){
    boolean paidFullCost = true;
    for(ItemStack itemCost : build.getMaterialCost()){

      ItemStack gatheredStack = village.gatherItemStackFromVillage(itemCost);
      if(gatheredStack.getCount() < itemCost.getCount()){
        paidFullCost = false;
      }
      
    }
    return paidFullCost;
  }

  private static List<BuildingInfo> findBestBuildingList(){

    // Depending on need, rank buildings to make.
    // Once a building is created, lower the desire for each of the benefits it provides.
    // Also add some other cobble-only buildings? A well / statue?
    return Arrays.asList(Buildings.LUMBERJACK);

  }

}