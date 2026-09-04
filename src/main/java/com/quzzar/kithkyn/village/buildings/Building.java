package com.quzzar.kithkyn.village.buildings;

import java.util.UUID;
import java.util.List;

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
      Rotation.CODEC.fieldOf("rotation").forGetter(Building::getRotation),
      MaterialAmount.CODEC.listOf().optionalFieldOf("investment", List.of()).forGetter(Building::getInvestment),
      Codec.LONG.optionalFieldOf("completed_at", -1L).forGetter(Building::getCompletedAt)
  ).apply(inst, Building::new));

  private UUID id;
  private String name;
  private long centerLoc;
  private long originLoc;
  private double radius;
  private Rotation rotation;
  private List<MaterialAmount> investment = List.of();
  private long completedAt = -1;

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

  /**
   * The same building, one level up: identity and orientation kept, definition
   * swapped. The origin may slide so a larger footprint can extend into the side
   * that has room. Keeping the id is what lets a worker hold their job through
   * an upgrade (docs/building-spec.md): assignments point at a building by id
   * and station index, so a new id would release every one of them.
   */
  public static Building upgradeOf(Building from, String newName, BlockPos origin, Rotation rotation) {
    return new Building(from.getUUID(), newName, from.getCenterLocation(),
        origin.asLong(), from.getRadius(), rotation, from.investment, from.completedAt);
  }

  private Building(UUID id, String name, long centerLoc, long originLoc, double radius, Rotation rotation,
      List<MaterialAmount> investment, long completedAt) {
    this.id = id;
    this.name = name;
    this.centerLoc = centerLoc;
    this.originLoc = originLoc;
    this.radius = radius;
    this.rotation = rotation;
    this.investment = List.copyOf(investment);
    this.completedAt = completedAt;
  }

  /** Actual paid materials; founding, developer placement, and legacy saves begin with no credit. */
  public List<MaterialAmount> getInvestment() {
    return investment;
  }

  public void recordInvestment(List<MaterialAmount> payment) {
    investment = MaterialAmount.combine(investment, payment);
  }

  public long getCompletedAt() {
    return completedAt;
  }

  public void markCompletedAt(long gameTime) {
    completedAt = gameTime;
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
