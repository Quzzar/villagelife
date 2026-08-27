package com.quzzar.villagelife.village.buildings;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.block.Rotation;

public class Building {

  public static final Codec<Building> CODEC = RecordCodecBuilder.create(inst -> inst.group(
      UUIDUtil.CODEC.fieldOf("id").forGetter(Building::getUUID),
      Codec.STRING.fieldOf("name").forGetter(Building::getName),
      Codec.LONG.fieldOf("center").forGetter(Building::getCenterLocation),
      Codec.LONG.fieldOf("origin").forGetter(Building::getOriginLocation),
      Codec.DOUBLE.fieldOf("radius").forGetter(Building::getRadius),
      Rotation.CODEC.fieldOf("rotation").forGetter(Building::getRotation)
  ).apply(inst, Building::new));

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

  private Building(UUID id, String name, long centerLoc, long originLoc, double radius, Rotation rotation) {
    this.id = id;
    this.name = name;
    this.centerLoc = centerLoc;
    this.originLoc = originLoc;
    this.radius = radius;
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


}
