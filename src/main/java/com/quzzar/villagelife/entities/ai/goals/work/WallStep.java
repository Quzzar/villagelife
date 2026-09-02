package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.bookkeeping.NoResourceBookkeepingEvent;
import com.quzzar.villagelife.village.buildings.WallProject;
import com.quzzar.villagelife.village.buildings.WallRaiser;
import com.quzzar.villagelife.village.buildings.Materials;
import com.quzzar.villagelife.village.buildings.WallTier;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Raising the village wall (docs/walls.md): a PLACE step that walks a ring and
 * lays a segment at each column, on whatever surface it finds there, so the wall
 * steps with the terrain rather than flattening it.
 *
 * The wall is not a {@code StructureInProgress}, so {@link BuildStep} never
 * touches it; this step owns it instead, engaging only while the village has a
 * wall to raise. It is the perimeter analogue of {@link PathStep}: where PathStep
 * wears a path between two buildings as the builder walks, this rings the whole
 * village. The shape of each segment lives in
 * {@link com.quzzar.villagelife.village.buildings.WallRaiser}, shared with the
 * dev command that rings a village at once.
 *
 * <b>Materials ride on the builder's back.</b> A load is fetched from a real
 * chest into the pack, and the ring is walked with it, column by column, until
 * the pack runs dry and the next load is fetched (docs/worker-loops.md,
 * "Nothing teleports"). A wall that outruns the village's timber simply waits
 * at the course it reached until more is cut, and never leaves a half-height
 * gap.
 */
public final class WallStep implements BlockWorkStep {

  /** Material carried per fetch trip: a stack, several columns' worth. */
  private static final int LOAD_PER_TRIP = 64;

  /**
   * Below this many carried, top up before walking the ring: no single column
   * needs more, so a pack above the line never stalls mid-column.
   */
  private static final int LOW_WATER = 8;

  /**
   * Wall blocks already paid for by an item drawn on an earlier column. One
   * item raises {@link WallTier#BLOCKS_PER_ITEM} blocks and a column rarely
   * needs a round number, so the remainder carries over. This builder's own
   * and never saved: a builder swapped mid-ring forfeits at most an item.
   */
  private int credit;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    Village village = person.getVillage();
    if (village == null || (village.getCurrentProject() != null && person.isConstructionLead())) {
      return null; // a building project owns the builder; the wall waits its turn
    }
    WallProject wall = village.getWallProject();
    if (wall == null || wall.isComplete()) {
      return null;
    }
    // Top up below the low-water line so no column stalls mid-height, then walk
    // the ring with what is carried. A part-load with nothing left in the
    // chests is still walked out: it may finish a short column.
    int carried = PackLogistics.carried(person, wall.getTier().material());
    if (carried < LOW_WATER) {
      BlockPos source = PackLogistics.chestHolding(person, village,
          List.of(new ItemStack(wall.getTier().material(), LOAD_PER_TRIP)));
      if (source != null) {
        return source;
      }
    }
    return carried > 0 ? standFor(person, village, wall.nextColumn()) : null;
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
      return false; // re-select: the ring if loaded, another chest if not
    }
    if (!raiseColumn(person, village, wall)) {
      return false; // short of material: re-select fetches or waits
    }
    wall.advance();
    return false; // on to the next column
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

  /** Close enough to lay the next segment beside where the builder stands. */
  @Override
  public double reachSqr(RealPerson person) {
    return 6.0D;
  }

  /**
   * A foothold one step inside the ring, so the builder lays the wall beside
   * itself rather than in its own square and never seals itself in.
   */
  private BlockPos standFor(RealPerson person, Village village, long column) {
    Level level = person.level();
    int x = BlockPos.getX(column);
    int z = BlockPos.getZ(column);
    BlockPos centre = village.getTownCenter() == null
        ? new BlockPos(x, 0, z)
        : BlockPos.of(village.getTownCenter().getCenterLocation());
    int dx = Integer.signum(centre.getX() - x);
    int dz = Integer.signum(centre.getZ() - z);
    int sx = x + (dx == 0 ? 1 : dx);
    int sz = z + (dz == 0 ? 1 : dz);
    return new BlockPos(sx, WallRaiser.surfaceY(level, sx, sz), sz);
  }

  /** Raises one column's segment from the pack, or reports it could not. */
  private boolean raiseColumn(RealPerson person, Village village, WallProject wall) {
    Level level = person.level();
    long column = wall.nextColumn();
    int x = BlockPos.getX(column);
    int z = BlockPos.getZ(column);
    WallTier tier = wall.getTier();
    boolean gate = wall.isGate(column);
    int base;
    int floor;
    if (wall.hasGround()) {
      int i = wall.getCursor();
      base = wall.groundAt(i);
      floor = wall.seamFloor(i);
    } else {
      base = WallRaiser.surfaceY(level, x, z); // a wall saved before the ground profile existed
      floor = base;
    }
    int[] range = WallRaiser.segmentRange(level, x, z, tier, gate, base, floor);
    if (range == null) {
      return true; // nothing to place here; treat the column as done
    }
    int count = range[1] - range[0] + 1;
    int owed = Math.max(0, count - credit);
    int items = Math.ceilDiv(owed, WallTier.BLOCKS_PER_ITEM);

    if (PackLogistics.carried(person, tier.material()) < items) {
      // What is carried stays carried for the next column; the shortage is only
      // real when the village's chests have none left to fetch either.
      if (Materials.counted(village.stockTally(), tier.material()) == 0) {
        village.logEvent(new NoResourceBookkeepingEvent(tier.material(), items));
        person.logIssue("we are short of materials to raise the village wall", Optional.empty());
      }
      return false;
    }
    Materials.take(person.personMainInv, tier.material(), items);
    credit += items * WallTier.BLOCKS_PER_ITEM - count;

    WallRaiser.place(level, x, z, range, tier);
    if (gate) {
      BlockPos centre = village.getTownCenter() == null ? new BlockPos(x, 0, z)
          : BlockPos.of(village.getTownCenter().getCenterLocation());
      WallRaiser.placeGateDoor(level, x, z, range[2], WallRaiser.gateFacing(x, z, centre));
    }
    level.playSound((Player) null, x, range[2], z,
        tier.block().defaultBlockState().getSoundType().getPlaceSound(),
        SoundSource.BLOCKS, 1.0F, person.getRandom().nextFloat() * 0.3F + 0.85F);
    return true;
  }
}
