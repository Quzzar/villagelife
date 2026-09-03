package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.quzzar.villagelife.village.Occupation;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

/**
 * A mine's shaft as a place a villager can be, and its ramp as the way in and
 * out.
 *
 * <p>The shaft is the corridor the miner's cursor digs (MineStep): {@link #RADIUS}
 * either side of the mouth's centre line, ramping one block down for every
 * block forward, so the walk cell of column {@code z} sits {@code z + 2} below
 * the mouth. In the mine's own frame the mouth is the origin, forward is +z,
 * and a world position is read into that frame by undoing the building's
 * rotation.
 *
 * <p>Why the ramp has to be spelled out to the navigator: a villager's
 * pathfinder only expands nodes within their follow range, twenty blocks, of
 * where they stand, and past that it hands back a partial path to whichever
 * node lies closest to the target as the crow flies. For a face twenty layers
 * down that node is the surface directly above the shaft bottom, which is
 * where the miner kept walking, standing over his own work with no way down;
 * and from the face the walk to bed or the storehouse failed the same way in
 * reverse, until the stranded recovery teleported him home. {@link #waypoint}
 * gives the navigator the ramp a hop at a time instead: in, the mouth and then
 * down the walk cells; out, up the walk cells to the mouth; between two points
 * of one shaft, straight when they are within a hop of each other. Every
 * goal gets it, since it sits under {@code moveTo}
 * ({@code entities/ai/PersonPathNavigation}).
 */
public final class MineShaft {

  /** Cells either side of the centre line the shaft is dug to: five wide. */
  public static final int RADIUS = 2;

  /**
   * Ramp columns one hop spans. A column is one block down and one forward, so
   * eight columns is about eleven blocks as the crow flies, well inside the
   * pathfinder's twenty, and the hop lands on a walk cell the ramp has always
   * already dug, since the face is the deepest point of it.
   */
  private static final int HOP = 8;

  /** How near the mouth counts as standing at it, squared. */
  private static final double AT_MOUTH_SQR = 9.0D;

  private final BlockPos mouth;
  private final Rotation rotation;

  private MineShaft(BlockPos mouth, Rotation rotation) {
    this.mouth = mouth;
    this.rotation = rotation;
  }

  /**
   * Whether a cell in the mine's frame lies in the corridor the cursor digs:
   * {@link #RADIUS} to either side of the centre line, from the entrance
   * (local z) down and forward. The one definition of the shaft's shape;
   * MineStep digs to it, and the navigator reads it.
   */
  public static boolean withinCorridor(BlockPos local) {
    return Math.abs(local.getX()) <= RADIUS
        && local.getY() <= -1
        && local.getZ() >= -(RADIUS - 1);
  }

  /** Every shaft in the village: one per miner station of every mine standing. */
  public static List<MineShaft> of(Village village) {
    List<MineShaft> out = new ArrayList<>();
    for (Building building : village.getBuildings()) {
      BuildingInfo info = building == null ? null : building.getInfo();
      if (info == null) {
        continue;
      }
      for (Map.Entry<Long, Occupation> station : info.getWorkLocations().entrySet()) {
        if (station.getValue() != Occupation.MINER) {
          continue;
        }
        BlockPos mouth = BlockPos.of(building.getOriginLocation())
            .offset(BlockPos.of(station.getKey()).rotate(building.getRotation()));
        out.add(new MineShaft(mouth, building.getRotation()));
      }
    }
    return out;
  }

  /**
   * The next place to walk toward on the way from {@code from} to {@code to}
   * when either lies down a shaft of this village, or null when the ordinary
   * path will do: neither is in a shaft, or both are in the same one within a
   * hop of each other.
   */
  @Nullable
  public static BlockPos waypoint(Village village, BlockPos from, BlockPos to) {
    for (MineShaft shaft : of(village)) {
      BlockPos hop = shaft.hop(from, to);
      if (hop != null) {
        return hop;
      }
    }
    return null;
  }

  @Nullable
  private BlockPos hop(BlockPos from, BlockPos to) {
    BlockPos localFrom = local(from);
    BlockPos localTo = local(to);
    boolean fromIn = withinCorridor(localFrom);
    boolean toIn = withinCorridor(localTo);
    if (!fromIn && !toIn) {
      return null;
    }
    if (fromIn && toIn) {
      int ahead = localTo.getZ() - localFrom.getZ();
      if (Math.abs(ahead) <= HOP) {
        return null; // the ordinary path reaches it
      }
      return walkCell(localFrom.getZ() + (ahead > 0 ? HOP : -HOP));
    }
    if (fromIn) {
      // Climbing out: up the ramp a hop at a time, and within a hop of the mouth
      // hand back to the ordinary path so it climbs the last steps out to the real
      // target. Returning this.mouth here pinned anyone standing below the mouth:
      // the walk to the mouth is a no-op once they reach it, and the goal's real
      // up-top target (a bed, the day's work) never got a path, so a villager who
      // wandered onto the ramp could never climb back out of the shaft.
      int z = localFrom.getZ() - HOP;
      return z <= -(RADIUS - 1) ? null : walkCell(z);
    }
    // Going in: the mouth first, from wherever they are, then down the ramp.
    if (from.distSqr(this.mouth) > AT_MOUTH_SQR) {
      return this.mouth;
    }
    return walkCell(Math.min(localTo.getZ(), -(RADIUS - 1) + HOP));
  }

  /** The cell a walker's feet occupy on the ramp at column {@code z}, in the world. */
  private BlockPos walkCell(int z) {
    int column = Math.max(z, -(RADIUS - 1));
    int y = column < 0 ? -1 : -(column + 2);
    return this.mouth.offset(new BlockPos(0, y, column).rotate(this.rotation));
  }

  /** A world position in the mine's frame: the mouth at the origin, forward along +z. */
  private BlockPos local(BlockPos world) {
    return world.subtract(this.mouth).rotate(inverse(this.rotation));
  }

  private static Rotation inverse(Rotation rotation) {
    return switch (rotation) {
      case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
      case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
      default -> rotation;
    };
  }
}
