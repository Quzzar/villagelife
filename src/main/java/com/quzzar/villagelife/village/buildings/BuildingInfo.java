package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.quzzar.villagelife.village.Occupation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

public class BuildingInfo {

  private String path;
  private ArrayList<Long> bedLocs;
  private HashMap<Long, Occupation> workLocs;
  private ArrayList<Long> containerLocs;

  private List<ItemStack> materialCost;
  private List<Building.Benefit> benefits;

  public BuildingInfo(String path) {

    this.path = path;
    this.bedLocs = new ArrayList<>();
    this.workLocs = new HashMap<>();
    this.containerLocs = new ArrayList<>();
    this.materialCost = new ArrayList<>();

  }

  public String getName() {
    return path;
  }

  public String getPath() {
    return path;
  }

  public ArrayList<Long> getBedLocations() {
    return bedLocs;
  }

  public HashMap<Long, Occupation> getWorkLocations() {
    return workLocs;
  }

  public ArrayList<Long> getContainerLocations() {
    return containerLocs;
  }

  public BuildingInfo addBedLocation(int x, int y, int z) {
    bedLocs.add(BlockPos.asLong(x, y, z));
    return this;
  }

  public BuildingInfo addWorkLocation(int x, int y, int z, Occupation occupation) {
    workLocs.put(BlockPos.asLong(x, y, z), occupation);
    return this;
  }

  public BuildingInfo addContainerLocation(int x, int y, int z) {
    containerLocs.add(BlockPos.asLong(x, y, z));
    return this;
  }

  public List<ItemStack> getMaterialCost() {
    return materialCost;
  }

  public BuildingInfo setMaterialCost(List<ItemStack> materialCost) {
    this.materialCost = materialCost;
    return this;
  }

  public List<Building.Benefit> getBenefits() {
    return benefits;
  }

  public BuildingInfo setBenefits(List<Building.Benefit> benefits) {
    this.benefits = benefits;
    return this;
  }

}
