package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.bookkeeping.NoResourceBookkeepingEvent;
import com.quzzar.villagelife.village.buildings.Materials;
import com.quzzar.villagelife.village.buildings.WallProject;
import com.quzzar.villagelife.village.buildings.WallRaiser;
import com.quzzar.villagelife.village.buildings.WallTier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Builds the village wall one explicit construction cell at a time.
 *
 * A {@link WallProject} is divided into short, persistent sections. Each builder
 * leases a different section, walks to its next cell, and places that cell with
 * one swing. The result grows like a building instead of teleporting a complete
 * wall column into existence on one right-click.
 */
public final class WallStep implements BlockWorkStep {

  private static final int LOAD_PER_TRIP = 64;
  private static final int LOW_WATER = 8;

  /** Paid-for wall cells left from the current abstract material item. */
  private int credit;
  @Nullable
  private WallRaiser.WallWork targetedWork;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    Village village = person.getVillage();
    if (village == null || (village.getCurrentProject() != null && person.isConstructionLead())) {
      return null;
    }
    WallProject wall = village.getWallProject();
    if (wall == null || wall.isComplete()) {
      return null;
    }

    WallRaiser.WallWork work = WallRaiser.nextWork(
        person.level(), wall, person.getUUID(), person.blockPosition());
    if (work == null) {
      return null;
    }
    int itemsNeeded = this.credit > 0 ? 0 : 1;
    int carried = PackLogistics.carried(person, wall.getTier().material());
    if (itemsNeeded > 0 && carried < LOW_WATER) {
      BlockPos source = PackLogistics.chestHolding(person, village,
          List.of(new ItemStack(wall.getTier().material(), LOAD_PER_TRIP)));
      if (source != null) {
        this.targetedWork = null;
        return source;
      }
    }
    if (carried < itemsNeeded) {
      return null;
    }
    this.targetedWork = work;
    return standFor(person, village, work.block().pos());
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    Village village = person.getVillage();
    if (village == null) {
      return false;
    }
    WallProject wall = village.getWallProject();
    if (wall == null || wall.isComplete()) {
      return false;
    }
    Container chest = PackLogistics.containerAt(person, target);
    if (chest != null) {
      PackLogistics.pullWanted(person, chest,
          List.of(new ItemStack(wall.getTier().material(), LOAD_PER_TRIP)), "BUILDER");
      return false;
    }
    WallRaiser.WallWork work = this.targetedWork;
    if (work == null || !WallRaiser.isCurrent(wall, person.getUUID(), work)) {
      return false;
    }
    if (WallRaiser.isSatisfied(person.level(), work.block(), wall.getTier())) {
      wall.advance(person.getUUID(), work.section());
      return false;
    }
    if (!payForCell(person, village, wall.getTier())) {
      return false;
    }

    WallRaiser.place(person.level(), work.block(), wall.getTier());
    wall.advance(person.getUUID(), work.section());
    BlockPos pos = work.block().pos();
    person.level().playSound((Player) null, pos,
        work.block().desiredState(wall.getTier()).getSoundType().getPlaceSound(),
        SoundSource.BLOCKS, 1.0F, person.getRandom().nextFloat() * 0.3F + 0.85F);
    return false;
  }

  private boolean payForCell(RealPerson person, Village village, WallTier tier) {
    if (this.credit > 0) {
      this.credit--;
      return true;
    }
    if (PackLogistics.carried(person, tier.material()) < 1) {
      if (Materials.counted(village.stockTally(), tier.material()) == 0) {
        village.logEvent(new NoResourceBookkeepingEvent(tier.material(), 1));
        person.logBlocker("we are short of materials to raise the village wall");
      }
      return false;
    }
    person.clearBlocker("we are short of materials to raise the village wall");
    Materials.take(person.personMainInv, tier.material(), 1);
    this.credit = WallTier.BLOCKS_PER_ITEM - 1;
    return true;
  }

  /** Postpones one inaccessible section while every other section remains available. */
  @Override
  public void unreachable(RealPerson person, BlockPos target) {
    WallRaiser.WallWork work = this.targetedWork;
    Village village = person.getVillage();
    WallProject wall = village == null ? null : village.getWallProject();
    if (work == null || wall == null || !WallRaiser.isCurrent(wall, person.getUUID(), work)) {
      return;
    }
    wall.defer(person.getUUID(), work.section(), person.level().getGameTime());
    BlockPos pos = work.block().pos();
    Villagelife.LOGGER.debug("[wall] {} deferred unreachable {} section at {}, {}",
        person.getFullName(), wall.section(work.section()).kind().name().toLowerCase(),
        pos.getX(), pos.getZ());
  }

  @Override
  public void released(RealPerson person, BlockPos target) {
    this.targetedWork = null;
  }

  @Override
  public String describe() {
    return "the village wall";
  }

  @Override
  public String activity() {
    return "raising the village wall";
  }

  @Override
  public int actEveryTicks() {
    return 10;
  }

  @Override
  public double speed() {
    return 0.45D;
  }

  @Override
  public double reachSqr(RealPerson person) {
    return 6.0D;
  }

  /** Chooses a reachable foothold on the village side of a planned cell. */
  @Nullable
  private BlockPos standFor(RealPerson person, Village village, BlockPos wallCell) {
    Level level = person.level();
    int x = wallCell.getX();
    int z = wallCell.getZ();
    BlockPos centre = village.getTownCenter() == null
        ? new BlockPos(x, 0, z)
        : BlockPos.of(village.getTownCenter().getCenterLocation());
    int dx = Integer.signum(centre.getX() - x);
    int dz = Integer.signum(centre.getZ() - z);
    int inwardX = dx == 0 ? 1 : dx;
    int inwardZ = dz == 0 ? 1 : dz;

    WallWorkPlanner.Offset reachable = WallWorkPlanner.choose(inwardX, inwardZ, offset -> {
      BlockPos spot = standingSpot(level, x + offset.x(), z + offset.z());
      return spot != null && reachable(person, spot);
    });
    if (reachable != null) {
      return standingSpot(level, x + reachable.x(), z + reachable.z());
    }
    WallWorkPlanner.Offset standable = WallWorkPlanner.choose(inwardX, inwardZ,
        offset -> standingSpot(level, x + offset.x(), z + offset.z()) != null);
    if (standable != null) {
      return standingSpot(level, x + standable.x(), z + standable.z());
    }
    return new BlockPos(x + inwardX, WallRaiser.surfaceY(level, x + inwardX, z + inwardZ),
        z + inwardZ);
  }

  @Nullable
  private static BlockPos standingSpot(Level level, int x, int z) {
    BlockPos feet = new BlockPos(x, WallRaiser.surfaceY(level, x, z), z);
    BlockPos ground = feet.below();
    if (!level.hasChunkAt(feet)
        || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
        || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
        || !level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) {
      return null;
    }
    return feet;
  }

  private static boolean reachable(RealPerson person, BlockPos spot) {
    if (person.blockPosition().distSqr(spot) <= 1.0D) {
      return true;
    }
    Path path = person.getNavigation().createPath(spot, 1);
    return path != null && path.canReach();
  }
}
