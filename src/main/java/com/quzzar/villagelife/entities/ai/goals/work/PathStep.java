package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;

/**
 * Wearing a route between two buildings: a PLACE step whose act belongs to the
 * journey rather than to the destination.
 *
 * The only step that lays as it walks, and the reason {@link WorkStep} has a
 * travelling variant at all. The builder picks two buildings, walks quietly to
 * the first, and then treads a path to the second, turning ground to dirt path
 * beneath and beside them as they go.
 *
 * Paths are what a builder does when there is nothing to build, so the step
 * finds no work at all while a project stands. Refusing outright rather than
 * relying on goal priority keeps the two duties in the order the village cares
 * about: the movement flag alone would let path-laying take over the moment
 * construction paused for a tick.
 *
 * <b>This also ends.</b> The goal it replaces finished a path by calling
 * {@code stop()} from inside {@code tick()}, which cleared its two buildings
 * but did not end anything - {@code canContinueToUse} still said yes, so the
 * builder held the movement flag and did nothing at all until nightfall or the
 * next project.
 */
public final class PathStep implements BlockWorkStep {

  private static final List<Block> PAVEABLE = Arrays.asList(
      Blocks.GRASS_BLOCK, Blocks.MYCELIUM, Blocks.STONE, Blocks.SAND, Blocks.RED_SAND,
      Blocks.GRAVEL, Blocks.CLAY, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT,
      Blocks.PODZOL);

  /** Fraction of a building's radius that counts as having arrived at it. */
  private static final double ARRIVAL_FRACTION = 0.4D;

  private Building nearEnd;
  private Building destination;
  private boolean laying;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    Village village = person.getVillage();
    if (village == null || (village.getCurrentProject() != null && person.isConstructionLead())) {
      return null; // there is something to build; paths can wait
    }
    List<Building> buildings = new ArrayList<>(village.getBuildings());
    if (buildings.size() < 2) {
      return null; // a path runs between two buildings
    }
    if (this.laying && this.destination != null) {
      return BlockPos.of(this.destination.getCenterLocation()); // still walking the route
    }

    Building from = buildings.get(person.getRandom().nextInt(buildings.size()));
    Building to = buildings.get(person.getRandom().nextInt(buildings.size()));
    if (from == to) {
      return null; // try again next scan
    }
    this.nearEnd = from;
    this.destination = to;
    this.laying = false;
    return BlockPos.of(from.getCenterLocation());
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    if (!this.laying) {
      // Arrived at the near end: from here the walking itself is the work.
      this.laying = true;
      return false;
    }
    if (arrived(person)) {
      this.laying = false;
      this.nearEnd = null;
      this.destination = null;
      return false; // route complete; the next scan picks a fresh pair
    }
    pave(person);
    return true;
  }

  @Override
  public void released(RealPerson person, BlockPos target) {
    if (!this.laying) {
      this.nearEnd = null;
      this.destination = null;
    }
  }

  @Override
  public String describe() {
    return "the way between our buildings";
  }

  @Override
  public String activity() {
    return "laying paths between our buildings";
  }

  /** Laying happens on the move; walking to the near end does not. */
  @Override
  public boolean actWhileTravelling() {
    return this.laying;
  }

  @Override
  public int actEveryTicks() {
    return 10;
  }

  /**
   * A builder is at a building once well inside its radius - and which building
   * that is depends on which half of the trip they are on. Measuring the near
   * end against the FAR end's radius, as this did at first, means a builder
   * setting off from a large building toward a small one has to reach a point
   * they may not be able to stand on, never arrives, and is eventually
   * teleported home as stranded.
   */
  @Override
  public double reachSqr(RealPerson person) {
    Building at = this.laying ? this.destination : this.nearEnd;
    if (at == null) {
      return 9.0D;
    }
    double radius = at.getRadius();
    return radius * radius * ARRIVAL_FRACTION;
  }

  @Override
  public double speed() {
    return this.laying ? 0.3D : 0.5D; // a path is trodden slowly
  }

  private boolean arrived(RealPerson person) {
    if (this.destination == null) {
      return true;
    }
    BlockPos centre = BlockPos.of(this.destination.getCenterLocation());
    double radius = this.destination.getRadius();
    return centre.distSqr(person.blockPosition()) <= radius * radius * ARRIVAL_FRACTION;
  }

  /** Turns one square under or beside the builder into path, if it may be turned. */
  private void pave(RealPerson person) {
    BlockPos ground = switch (person.getRandom().nextInt(5)) {
      case 1 -> person.getOnPos().relative(Direction.EAST);
      case 2 -> person.getOnPos().relative(Direction.NORTH);
      case 3 -> person.getOnPos().relative(Direction.SOUTH);
      case 4 -> person.getOnPos().relative(Direction.WEST);
      default -> person.getOnPos();
    };
    if (!PAVEABLE.contains(person.level().getBlockState(ground).getBlock())) {
      return;
    }
    // Never pave out something that is growing.
    Block above = person.level().getBlockState(ground.above()).getBlock();
    if (above instanceof SaplingBlock || above instanceof CropBlock) {
      return;
    }
    person.level().setBlock(ground, Blocks.DIRT_PATH.defaultBlockState(), 2);
    person.level().playSound((Player) null, ground.getX(), ground.getY(), ground.getZ(),
        SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1.0F,
        person.getRandom().nextFloat() * 0.4F + 0.8F);
  }

}
