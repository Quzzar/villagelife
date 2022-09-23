package com.quzzar.villagelife.village.buildings;

import java.io.Serializable;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

public class Building implements Serializable {

  private UUID id;
  private String name;
  private long centerLoc;
  private long originLoc;
  private double radius;
  private Rotation rotation;

  public Building(String name, Rotation rotation) {
    this.id = UUID.randomUUID();
    this.name = name;
    this.rotation = rotation;
  }

  public Building(BlockPos originLoc, String name, Rotation rotation) {
    this.id = UUID.randomUUID();
    this.originLoc = originLoc.asLong();
    this.name = name;
    this.rotation = rotation;
  }

  public UUID getUUID() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setOriginLocation(long location) {
    this.originLoc = location;
  }
  public long getOriginLocation() {
    return originLoc;
  }

  public void setCenterLocation(long location) {
    this.centerLoc = location;
  }
  public long getCenterLocation() {
    return centerLoc;
  }

  public void setRadius(double radius) {
    this.radius = radius;
  }
  public double getRadius() {
    return radius;
  }

  public Rotation getRotation() {
    return rotation;
  }

  public BuildingInfo getInfo() {
    return Buildings.getByName(name);
  }

  public enum Benefit {
    WATER,
    LOGS,
    PLANKS,
    STONE,
    ORES,
    STORAGE,
    PROTECTION,
    SMELTING,
    REPAIR,
    HEALING,
    ENCHANTING,
  }

}
