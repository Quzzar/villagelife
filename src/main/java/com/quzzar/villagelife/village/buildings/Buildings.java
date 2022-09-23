package com.quzzar.villagelife.village.buildings;

import java.util.Arrays;
import java.util.HashMap;

import com.quzzar.villagelife.village.Occupation;
import com.quzzar.villagelife.village.buildings.Building.Benefit;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class Buildings {

  private static final HashMap<String, BuildingInfo> registry = new HashMap<>();

  public static BuildingInfo register(BuildingInfo info) {
    return register(info.getPath(), info);
  }

  private static BuildingInfo register(String name, BuildingInfo info) {
    registry.put(name, info);
    return info;
  }

  public static HashMap<String, BuildingInfo> allBuildings() {
    return registry;
  }

  public static BuildingInfo getByName(String name) {
    return registry.get(name);
  }

  // public static final BuildingInfo HOUSE_2 = register(new
  // BuildingInfo("house_2"));

  /*
   * public static final BuildingInfo LUMBERJACK_HOME = register(new
   * BuildingInfo("lumberjack_home")
   * .addBedLocation(7, 1, 8)
   * .addWorkLocation(5, 1, 2, Occupation.LUMBERJACK)
   * .addContainerLocation(3, 1, 9)
   * .setMaterialCost(Arrays.asList(new ItemStack(Items.COBBLESTONE, 48))));
   */

  public static final BuildingInfo TOWN_CENTER_1 = register(new BuildingInfo("town_center_1")
      .addBedLocation(17, 0, 10)
      .addBedLocation(17, 0, 12)
      .addWorkLocation(7, 0, 3, Occupation.MINER)
      .addWorkLocation(10, 1, 9, Occupation.GUARD)
      .addWorkLocation(4, 1, 11, Occupation.BUILDER)
      .addContainerLocation(5, 1, 5)
      .addContainerLocation(5, 0, 5)
      .addContainerLocation(15, 1, 9)
      .addContainerLocation(15, 1, 13)
      .addContainerLocation(4, 0, 17)
      .setMaterialCost(Arrays.asList()));

  public static final BuildingInfo LUMBERJACK = register(new BuildingInfo("lumberjack_house")
      .addBedLocation(7, 1, 7)
      .addWorkLocation(17, 1, 1, Occupation.LUMBERJACK)
      .addContainerLocation(8, 4, 7)
      .addContainerLocation(13, 0, 3)
      .setBenefits(Arrays.asList(Benefit.LOGS, Benefit.PLANKS))
      .setMaterialCost(Arrays.asList(new ItemStack(Items.COBBLESTONE, 64))));

  public static final BuildingInfo BLACKSMITH_1 = register(new BuildingInfo("blacksmith")
      .addBedLocation(4, 1, 7)
      .addWorkLocation(5, 1, 10, Occupation.BLACKSMITH)
      .addContainerLocation(5, 4, 7)
      .setBenefits(Arrays.asList(Benefit.REPAIR, Benefit.SMELTING))
      .setMaterialCost(Arrays.asList(
          new ItemStack(Items.COBBLESTONE, 48),
          new ItemStack(Items.OAK_LOG, 32),
          new ItemStack(Items.OAK_PLANKS, 64))));

  public static final BuildingInfo BLACKSMITH_2 = register(new BuildingInfo("blacksmith_2")
      .addBedLocation(3, 1, 4)
      .addWorkLocation(10, 1, 8, Occupation.BLACKSMITH)
      .addContainerLocation(7, 1, 10)
      .addContainerLocation(9, 1, 11)
      .setBenefits(Arrays.asList(Benefit.REPAIR, Benefit.SMELTING))
      .setMaterialCost(Arrays.asList(
          new ItemStack(Items.COBBLESTONE, 64 * 2),
          new ItemStack(Items.OAK_LOG, 64),
          new ItemStack(Items.OAK_PLANKS, 64 * 2))));

  public static final BuildingInfo CHURCH = register(new BuildingInfo("church")
      .addBedLocation(4, 3, 5)
      .addWorkLocation(8, 4, 8, Occupation.CLERIC)
      .addContainerLocation(2, 3, 7)
      .addContainerLocation(8, 5, 13)
      .addContainerLocation(8, 5, 3)
      .setBenefits(Arrays.asList(Benefit.ENCHANTING, Benefit.HEALING))
      .setMaterialCost(Arrays.asList(
          new ItemStack(Items.COBBLESTONE, 64 * 4),
          new ItemStack(Items.OAK_LOG, 32),
          new ItemStack(Items.OAK_PLANKS, 32),
          new ItemStack(Items.DIAMOND, 2))));

  public static final BuildingInfo GUARD_TOWER = register(new BuildingInfo("guard_tower")
      .addWorkLocation(2, 1, 2, Occupation.GUARD)
      .addWorkLocation(2, 1, 10, Occupation.GUARD)
      .addContainerLocation(6, 2, 4)
      .setBenefits(Arrays.asList(Benefit.PROTECTION))
      .setMaterialCost(Arrays.asList(
          new ItemStack(Items.COBBLESTONE, 48),
          new ItemStack(Items.OAK_PLANKS, 32),
          new ItemStack(Items.OAK_LOG, 64))));

  public static final BuildingInfo STOREHOUSE = register(new BuildingInfo("storehouse")
      .addContainerLocation(14, 1, 4)
      .addContainerLocation(12, 1, 4)
      .addContainerLocation(12, 2, 4)
      .addContainerLocation(12, 1, 7)
      .addContainerLocation(14, 1, 7)
      .addContainerLocation(10, 6, 7)
      .addContainerLocation(10, 5, 7)
      .addContainerLocation(10, 5, 6)
      .addContainerLocation(10, 5, 8)
      .addContainerLocation(8, 6, 9)
      .addContainerLocation(8, 5, 9)
      .setBenefits(Arrays.asList(Benefit.STORAGE))
      .setMaterialCost(Arrays.asList(
          new ItemStack(Items.COBBLESTONE, 64 * 2),
          new ItemStack(Items.OAK_LOG, 64 * 2),
          new ItemStack(Items.OAK_PLANKS, 64 * 4)))); // Add wheat to this?

  public static final BuildingInfo WELL_1 = register(new BuildingInfo("well_1")
      .setBenefits(Arrays.asList(Benefit.WATER))
      .setMaterialCost(Arrays.asList(
          new ItemStack(Items.OAK_LOG, 4),
          new ItemStack(Items.OAK_PLANKS, 16))));

  public static final BuildingInfo WELL_2 = register(new BuildingInfo("well_2")
      .setBenefits(Arrays.asList(Benefit.WATER))
      .setMaterialCost(Arrays.asList(
          new ItemStack(Items.COBBLESTONE, 32))));

}
