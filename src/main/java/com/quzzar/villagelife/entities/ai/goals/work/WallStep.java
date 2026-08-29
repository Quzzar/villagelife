package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.Optional;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.bookkeeping.NoResourceBookkeepingEvent;
import com.quzzar.villagelife.village.buildings.WallProject;
import com.quzzar.villagelife.village.buildings.WallRaiser;
import com.quzzar.villagelife.village.buildings.WallTier;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
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
 * Materials are pulled from village stores one column at a time, the way ground
 * fill is, so a wall that outruns the village's timber simply waits at the course
 * it reached until more is cut, and never leaves a half-height gap.
 */
public final class WallStep implements BlockWorkStep {

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    Village village = person.getVillage();
    if (village == null || village.getCurrentProject() != null) {
      return null; // a building project owns the builder; the wall waits its turn
    }
    WallProject wall = village.getWallProject();
    if (wall == null || wall.isComplete()) {
      return null;
    }
    return standFor(person, village, wall.nextColumn());
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
    if (!raiseColumn(person, village, wall)) {
      return true; // short of material: stay on this column and try again
    }
    wall.advance();
    return false; // on to the next column
  }

  @Override
  public String describe() {
    return "the village wall";
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

  /** Raises one column's segment, or reports it could not, for want of material. */
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

    ItemStack pulled = village.gatherItemStackFromVillage(new ItemStack(tier.material(), count));
    if (pulled.getCount() < count) {
      if (!pulled.isEmpty()) {
        village.placeItemStackIntoVillage(pulled, person); // put back what we cannot use yet
      }
      village.logEvent(new NoResourceBookkeepingEvent(tier.material(), count - pulled.getCount()));
      person.logIssue("we are short of materials to raise the village wall", Optional.empty());
      return false;
    }

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
