package com.quzzar.kithkyn.entities.ai.goals.work;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.savedata.PlacedBlockStore;
import com.quzzar.kithkyn.village.LocationManager;
import com.quzzar.kithkyn.village.buildings.Building;
import com.quzzar.kithkyn.village.buildings.MineShaft;
import com.quzzar.kithkyn.village.buildings.MineSupportMaterials;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.common.Tags;

/**
 * Driving the shaft: a BREAK step with the only genuinely algorithmic selector
 * in the set.
 *
 * The cursor sweeps a row across the shaft face, wraps to the next row, steps
 * down a level when the face is done, and pushes one deeper each time - which
 * is exactly the sort of thing the docs are right to say cannot be expressed as
 * datapack JSON without inventing a scripting language. The job definition can
 * name this selector; it cannot describe it.
 *
 * The miner walks DOWN into the shaft to work the face, rather than reaching it
 * from the mouth. The trick that keeps that from stalling: the block being
 * broken is always computed from the job location and the cursor, never from
 * where the miner is standing, so the loop's TARGET is free to be a foothold
 * beside the face ({@link #standToMine}). The shaft therefore advances the same
 * way it always did, and the descent is layered on top.
 *
 * <p>When the shaft breaks into a cave, the rule is the one a player follows:
 * complete the missing floor. A column of the ramp whose bottom opens into void
 * gets one dirt or stone support laid under it - PLACE work selected by the same cursor
 * that selects BREAK work, done standing on the floor already there (the ramp
 * behind, or the cell floored a moment ago), never by walking out over the gap.
 * The cave is crossed edge by edge as the sweep advances, darkness gets a torch
 * like anywhere else, and a miner out of support material logs it and stands down at
 * the mouth until restocked. Nothing here reasons about the void itself: no
 * bridging mode, no walking-and-planking - both were tried, and both ended with
 * blocks littered far outside the mine.
 *
 * <p>Water and lava are second-stage work. The sweep first closes every reachable
 * air or fluid breach in the flooded pocket's floor, walls, and ceiling with the
 * dirt or stone the miner carries. If a water source is farther down a flooded
 * ramp than the miner can reach, she lays a temporary dirt or stone working front
 * through the water until the real perimeter comes within arm's reach. Those
 * supports remain in place while they touch the flooded pocket, so the next sweep
 * cannot immediately dig them back out. A flooded rib is instead closed at its
 * doorway. Only after the boundary is sound does a miner with a bucket walk down,
 * move it into the off hand, and bail the connected water in one act; once the
 * water is gone, ordinary mining removes the temporary front. Clearing water first
 * lost the race to sources flowing back between picks.
 *
 * <p>Light is work the same way. The sweep that re-walks the shaft from the mouth
 * on every pick reads the light in each open head-height cell along the ramp's
 * edge, and the first it finds dim, deep enough to be dark, with a wall beside it
 * and a torch in the pack, is the pick: she walks there and hangs the torch. A
 * fresh layer is lit as it opens, one layer behind the face, and a shaft dug dark
 * by a miner with no torches, or inherited from one, is lit from the top down the
 * moment a miner with torches walks in, since the sweep meets the dark before it
 * meets the face. Torches used to go up only in the act of breaking a block,
 * which lit nothing behind the miner and left an inherited shaft dark for good.
 *
 * <p>Every pick re-walks the shaft from the mouth. An open cell costs one block
 * read, so auditing a thousand of them is nothing, and it is what makes a cell
 * an interruption skipped, or gravel refilled behind the miner, get dug on the
 * next pass rather than never. Ore is taken from the shaft's own walls, floor
 * and ceiling around the miner, the full width of the corridor, and followed
 * into the rock only through the holes she opened herself.
 *
 * <p>When the ramp can drive no deeper - bedrock, or lava or water she has no
 * bucket for - she does not simply stand down. She fans short horizontal ribs out
 * of the ramp instead ({@link #selectFan}): one block wide, {@link #FAN_HEIGHT}
 * tall, at most {@link #FAN_LENGTH} out to each side, cut on a fixed grid every
 * {@link #FAN_PITCH} columns from the bottom of the ramp up, a player's branch mine.
 * The ribs are deliberately kept to one pathfinder hop, so the navigator needs no
 * waypoints of its own for them: from the far end of a rib the ordinary path always
 * reaches back onto the ramp, which does have waypoints (MineShaft). The ore a rib
 * wall exposes is worked by the same vein detour as the shaft's, since it reads a
 * rib cell as dug space, and a cut rib is lit with a torch; a rib that meets liquid
 * or a cave simply stops there, a prospect cut rather than a second shaft.
 */
public final class MineStep implements BlockWorkStep {

  /** The corridor's half-width, the one definition of the shaft's shape (MineShaft). */
  private static final int RADIUS = MineShaft.RADIUS;

  /**
   * How many blocks the cursor may step over in one search. The version this
   * replaces looped until it found something solid, with nothing to stop it:
   * a shaft opening into a cave, or one dug below the bottom of the world,
   * reads as air forever and would have hung the server inside a while loop.
   */
  private static final int MAX_CURSOR_STEPS = 4096;


  /**
   * Torches only start once the shaft has dropped this far below its mouth: the
   * entrance and the first steps down catch daylight, so torching there just
   * litters a lit approach. Below it the shaft is dark and actually wants light.
   */
  private static final int TORCH_MIN_DEPTH = 10;

  /**
   * The light in an open head-height cell of the shaft below which it wants a
   * torch: a player's spacing. A torch gives 14 and light falls one per block, the
   * ramp costs about two per layer, so 4 hangs one roughly every six layers, twelve
   * blocks of ramp, and the dimmest stretch between two torches never nears the
   * dark that spawns monsters. At 8 the shaft was lit every three or four layers,
   * brighter than any player bothers with.
   */
  private static final int TORCH_LIGHT_FLOOR = 4;

  /**
   * Most flooded ramp cells one bail clears, so a whole drowned shaft cannot
   * hang the tick; a bigger flood is bailed pocket by pocket, one per pick.
   */
  private static final int BAIL_CAP = 128;

  /**
   * Ticks to keep the bucket out in the off hand after a seal or a clear, so the
   * waterproofing is a beat you can watch rather than an instant. About two
   * seconds; a wider leak reseals cell by cell and holds it out longer on its own.
   */
  private static final int BUCKET_SHOWN_TICKS = 40;

  /**
   * How many ore blocks the miner pulls from one wall vein before it stops
   * following and seals the vein back up. Bounds a single detour, so a rich seam
   * cannot turn the miner into a tunnel-boring machine.
   */
  private static final int VEIN_CAP = 8;

  /**
   * Rows of the shaft either side of the miner, and blocks above and below, that
   * the ore scan covers. The scan runs in the mine's own frame and always spans
   * the corridor's full width, so ore in the far wall is seen from either side; a
   * reach measured from the miner's feet made the far wall of a five-wide shaft a
   * coin flip, and a miner walked past copper and iron she had a pick for.
   */
  private static final int VEIN_SCAN = 3;

  /**
   * How far a fan-out rib reaches beyond the corridor edge, in blocks. Kept to one
   * pathfinder hop (MineShaft plans no waypoints for a rib, so the ordinary path has
   * to reach from the rib's far end back onto the ramp on its own; eight blocks is
   * well inside the twenty a villager's navigator expands). A rib is one block wide
   * and {@link #FAN_HEIGHT} tall, so its walls, floor and ceiling put a lot of fresh
   * rock on show for the vein detour to work.
   */
  private static final int FAN_LENGTH = MineTopology.FAN_LENGTH;

  /**
   * Columns between one rib and the next along the ramp: a rib is one column wide, so
   * a pitch of four leaves three solid columns between cuts, a player's branch-mine
   * spacing. Ribs sit on the fixed grid {@code z % FAN_PITCH == 0} so {@link #inRib}
   * can tell a rib cell from the rock around it without tracking which ones are cut.
   */
  private static final int FAN_PITCH = MineTopology.FAN_PITCH;

  /** A rib's height: three tall, headroom to walk plus a third row of ore in each wall. */
  private static final int FAN_HEIGHT = MineTopology.FAN_HEIGHT;

  /**
   * The shallowest ramp column a rib is cut from: shallower than this the ramp is near
   * the lit mouth and still in soil, not the stone a rib is meant to prospect. A column
   * at forward distance z sits about {@code z + 2} below the mouth, so four is roughly
   * six blocks down.
   */
  private static final int FAN_MIN_LINE = MineTopology.FAN_MIN_LINE;

  /** A hard cap on how deep {@link #deepestDugColumn} scans, well below any real shaft. */
  private static final int FAN_SCAN_MAX = 384;

  private BlockPos offset;
  private Block block;
  private int inward = 1;
  private int breakTime;
  private int lastProgress = -1;
  private int bucketShownTicks;

  /**
   * True when the cursor cell is an open head-height cell on the ramp's edge that
   * reads dim: the act hangs a torch there instead of digging.
   */
  private boolean placeTorch;

  /**
   * True when the cursor cell's column has no floor under it: the act lays one
   * dirt or stone support at face-below instead of digging the face.
   */
  private boolean placeFloor;

  /**
   * True when the cursor cell holds water or lava and the miner has a bucket:
   * the act bails the flooded stretch from the face instead of digging.
   */
  private boolean bailWater;

  /**
   * True when a cell of the tunnel's lining, a wall to the side or the ceiling
   * above, stands open into air or fluid: the act lays one support block at
   * {@link #sealCell} to close it, standing on the walkway exactly as flooring
   * does. Floor, walls and ceiling are the same work, a solid block placed at a
   * boundary the shaft would otherwise leave gaping into the cavern.
   */
  private boolean placeSeal;
  private BlockPos sealCell;
  private BlockPos sealFooting;
  private BlockPos sealStand;
  private boolean temporaryFloodSupport;


  // A wall vein under extraction: ore already pulled and awaiting its support
  // plug (sealed deepest-first, so the miner backs out toward the shaft as it
  // seals), plus the ore being broken and the cell being sealed this cycle. All
  // empty or null while the miner is simply driving the shaft.
  private final Deque<BlockPos> veinToSeal = new ArrayDeque<>();
  private BlockPos oreTarget;
  private BlockPos sealTarget;
  private int veinTaken;

  /**
   * True once the ramp has driven as deep as it can and the miner has switched to
   * fanning ribs out of it ({@link #selectFan}). Latched so the obstacle that ended
   * the descent is logged once, not on every pick; cleared the moment the ramp yields
   * work again, so a leak the miner later gets a bucket for resumes the descent.
   */
  private boolean fanning;

  /** What one sweep of the ramp found: work to do, a dead end, or a place to fan out. */
  private enum RampScan {
    /** A cell was chosen (dig, floor, torch, bail or seal); {@link #act} will work it. */
    WORK,
    /** The ramp is driven as deep as it can go: bedrock, or lava/water with no bucket. */
    BLOCKED,
    /** The cursor stepped over nothing solid within the pattern; try again later. */
    DRY_HOLE,
  }

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    BlockPos mouth = LocationManager.getJobLocation(person);
    if (mouth == BlockPos.ZERO) {
      return null;
    }
    Rotation rotation = rotation(person);
    // A wall vein takes priority over driving the shaft on: pull the exposed ore,
    // then plug the holes with a support block, before the descent picks back up. It
    // reads rib cells as dug space too, so ore a rib wall exposes is worked the same
    // way as ore in the shaft's own walls.
    BlockPos veinStand = selectVein(person, mouth, rotation);
    if (veinStand != null) {
      return veinStand;
    }
    RampScan scan = locateNext(person, mouth, rotation);
    if (scan == RampScan.WORK) {
      // Bridging a cave - laying a floor cell or lining a wall - is the one ramp work
      // that needs dirt or stone the miner may not have. With an empty pack, do not stand
      // down and wait on a restock a fresh cave outpaces: that was Aaron's deadlock, the
      // miner frozen at the cave, leaving the ore in its own corridor walls because
      // pulling ore is gated on support to seal too (selectVein). Quarry support
      // instead - fan short ribs into the solid rock beside the ramp, which
      // needs none and drops plenty (selectFan) - and let the next sweep resume the
      // bridge once the pack has refilled. Only support-gated work reroutes; a
      // diggable face still digs, since breaking stone is itself a support source.
      if ((this.placeFloor || this.placeSeal)
          && MineSupportMaterials.held(person.personMainInv) == 0) {
        BlockPos fanStand = selectFan(person, mouth, rotation);
        if (fanStand != null) {
          return fanStand;
        }
        // No rib left to cut either: fall through to standing down, so a mine with
        // nothing to quarry still waits on a restock the way it always did.
        resetShaft();
        return null;
      }
      this.fanning = false;
      // Stand next to the face and work it, walking down into the shaft as it
      // deepens. No footing reachable - a hole in the ramp behind, say - and the
      // shaft waits: nothing is picked, and nothing is dug from a distance. This
      // used to fall back to the mouth as the target, which handed act a face
      // twenty blocks off and had the miner dig it from the doorstep.
      BlockPos face = face(mouth, rotation);
      // Sealing a wall or the ceiling is done standing on the walkway, the reachable
      // footing at the column's floor, and placing the lining from there, the way a
      // player lines a tunnel from inside it. Everything else stands beside the face.
      if (this.placeSeal && this.sealStand != null) {
        return this.sealStand;
      }
      BlockPos footingAt = this.placeSeal ? this.sealFooting : face;
      if (footingAt == null) {
        resetShaft();
        return null;
      }
      BlockPos stand = this.placeFloor
          ? standToLayFloor(person, mouth, rotation, this.offset)
          : standToMine(person, footingAt);
      if (stand == null) {
        // The swept face has no reachable footing: a fresh mine with nothing dug to
        // stand in yet, or a face across an undug gap. Recover from the entrance
        // toward it, laying any reachable missing floor before carving the next
        // solid cell, until the swept face comes within reach.
        BlockPos frontierStand = carveFrontier(person, mouth, rotation);
        if (frontierStand != null) {
          return frontierStand;
        }
        resetShaft();
        return null;
      }
      return stand;
    }
    if (scan == RampScan.BLOCKED) {
      // The ramp can drive no deeper. Rather than stand down, fan short horizontal
      // ribs out of it to prospect the surrounding rock (see the class note). The
      // obstacle that ended the descent is logged once, on the way in.
      onRampBlocked(person, mouth, rotation);
      BlockPos fanStand = selectFan(person, mouth, rotation);
      if (fanStand != null) {
        return fanStand;
      }
      resetShaft();
      return null;
    }
    // DRY_HOLE.
    resetShaft();
    return null;
  }

  /**
   * Recover a rejected ramp face by scanning the planned corridor from its
   * entrance to that face. The first reachable floor gap is bridged; otherwise
   * the first reachable solid cell is carved. This depends on the shaft and its
   * existing footing, not on where an idle miner wandered.
   */
  @Nullable
  private BlockPos carveFrontier(RealPerson person, BlockPos mouth, Rotation rotation) {
    Level level = person.level();
    int throughZ = this.offset.getZ();
    // Walk the planned ramp from its entrance toward the rejected face. The
    // former four-block cube followed the miner, so once an idle miner wandered
    // back to the surface it never intersected a deep shaft and work vanished.
    // Entrance-first ordering finds the first solid cell that existing footing
    // can reach, then the ordinary mine waypoints bring the miner back down.
    for (BlockPos local : MineTopology.rampCellsThrough(throughZ)) {
      BlockPos world = mouth.offset(local.rotate(rotation));
      Block here = level.getBlockState(world).getBlock();
      // The diagonal sweep can encounter an upper block several columns ahead
      // before it reaches the bottom row of an intervening cave. If that upper
      // face has no footing, advance the earliest reachable floor edge first.
      // Otherwise the miner sees valid stone but rejects it forever while the
      // missing bridge work sits later in the cursor order.
      if (columnBottom(local) && needsSeal(level, world.below())) {
        BlockPos floorStand = standToLayFloor(person, mouth, rotation, local);
        if (floorStand != null) {
          this.offset = local;
          this.block = here;
          this.placeFloor = true;
          this.placeTorch = false;
          this.bailWater = false;
          this.placeSeal = false;
          return floorStand;
        }
      }
      if (here == Blocks.AIR || here == Blocks.WATER || here == Blocks.LAVA
          || here == Blocks.BEDROCK || isLight(level, world)) {
        continue;
      }
      if (level instanceof ServerLevel serverLevel
          && PlacedBlockStore.get(serverLevel).isVillagePlaced(world)
          && holdsBackFlood(serverLevel, mouth, rotation, local)) {
        continue;
      }
      BlockPos frontierStand = standToMine(person, world);
      if (frontierStand == null) {
        continue;
      }
      this.offset = local;
      this.block = here;
      this.placeFloor = false;
      this.placeTorch = false;
      this.bailWater = false;
      this.placeSeal = false;
      return frontierStand;
    }
    return null;
  }

  /**
   * Fan-out prospecting: once the ramp can drive no deeper, cut short horizontal
   * ribs straight out of it to put the surrounding rock on show. Each rib is one
   * block wide, {@link #FAN_HEIGHT} tall and at most {@link #FAN_LENGTH} out to
   * either side, sitting flush on a ramp column's floor so the miner walks in from
   * the corridor; ribs are cut on the fixed grid {@code z % FAN_PITCH == 0},
   * deepest-first, from the bottom of the ramp up to {@link #FAN_MIN_LINE}. The ore a
   * rib wall exposes is pulled by the same vein detour that works the shaft
   * ({@link #selectVein}, which reads rib cells as dug space), and a finished rib is
   * lit with a single wall torch. Returns a foothold to work the next bit of rib, or
   * null when every rib off the ramp is cut and the mine is worked out. Kept to one
   * hop out so the ordinary pathfinder always hands the miner back onto the ramp,
   * with no waypoints of its own (MineShaft plans none for a rib).
   */
  @Nullable
  private BlockPos selectFan(RealPerson person, BlockPos mouth, Rotation rotation) {
    int deepest = deepestDugColumn(person, mouth, rotation);
    for (int zr = deepest - (deepest % FAN_PITCH); zr >= FAN_MIN_LINE; zr -= FAN_PITCH) {
      if (!columnDug(person, mouth, rotation, zr)) {
        continue; // the ramp never reached this column (a cave swallowed it, say)
      }
      BlockPos stand = workRib(person, mouth, rotation, zr);
      if (stand != null) {
        return stand;
      }
    }
    return null;
  }

  /**
   * The forward distance of the deepest ramp column that has an open, standable walk
   * cell: where the fan starts and works up from. Scans down the ramp's centre line
   * and stops once the walk cells run out for good, so a disconnected cavern floor far
   * below cannot be mistaken for the ramp's bottom.
   */
  private int deepestDugColumn(RealPerson person, BlockPos mouth, Rotation rotation) {
    Level level = person.level();
    int deepest = 0;
    int gap = 0;
    for (int z = 0; z <= FAN_SCAN_MAX; z++) {
      if (standable(level, mouth.offset(new BlockPos(0, ribFloorY(z), z).rotate(rotation)))) {
        deepest = z;
        gap = 0;
      } else if (++gap > RADIUS + 1) {
        break; // past the end of the dug ramp
      }
    }
    return deepest;
  }

  /** Whether the ramp column at forward distance {@code z} has a standable walk cell. */
  private boolean columnDug(RealPerson person, BlockPos mouth, Rotation rotation, int z) {
    return standable(person.level(), mouth.offset(new BlockPos(0, ribFloorY(z), z).rotate(rotation)));
  }

  /**
   * Work one rib line off the ramp column at {@code zr}, one side at a time. Steps
   * out from the corridor edge digging the first solid cell it has footing for,
   * floor row first so the cells above it get something to stand on, and stops the
   * side at liquid, bedrock or a cave rather than bailing or boring through, the way
   * the ramp would. When a side is fully cut it is lit ({@link #ribTorch}). Returns
   * the foothold to work from, or null when both sides are cut and lit.
   */
  @Nullable
  private BlockPos workRib(RealPerson person, BlockPos mouth, Rotation rotation, int zr) {
    Level level = person.level();
    int floorY = ribFloorY(zr);
    for (int side = 1; side >= -1; side -= 2) {
      boolean cutting = true;
      for (int step = 1; step <= FAN_LENGTH && cutting; step++) {
        int x = side * (RADIUS + step);
        for (int dy = 0; dy < FAN_HEIGHT; dy++) {
          BlockPos local = new BlockPos(x, floorY + dy, zr);
          BlockPos world = mouth.offset(local.rotate(rotation));
          Block here = level.getBlockState(world).getBlock();
          if (here == Blocks.AIR || isLight(level, world)) {
            continue; // already open, or a torch: look higher, then step out
          }
          if (here == Blocks.WATER || here == Blocks.LAVA || here == Blocks.BEDROCK) {
            cutting = false; // a rib stops here; it is a prospect cut, not the shaft
            break;
          }
          if (level instanceof ServerLevel serverLevel
              && PlacedBlockStore.get(serverLevel).isVillagePlaced(world)) {
            cutting = false; // a waterproofing bulkhead deliberately closes this rib
            break;
          }
          if (dy == 0 && level.getBlockState(world.below()).isAir()) {
            cutting = false; // the rib has reached a cave: stop rather than floor it
            break;
          }
          BlockPos stand = standToMine(person, world);
          if (stand == null) {
            continue; // no footing yet; a lower or inner cell opens the way first
          }
          this.offset = local;
          this.block = here;
          this.placeFloor = false;
          this.placeTorch = false;
          this.bailWater = false;
          this.placeSeal = false;
          return stand;
        }
      }
      BlockPos lit = ribTorch(person, mouth, rotation, zr, side, floorY);
      if (lit != null) {
        return lit;
      }
    }
    return null;
  }

  /**
   * Light a fully-cut rib with one wall torch near its mouth: a torch gives fourteen
   * and a one-hop rib is eight long, so one near the corridor covers it end to end.
   * Offered only when that cell is open, dark, has a torch in the pack and a wall to
   * hang it on, exactly the guards {@link #wantsTorch} uses, so it is placed once and
   * never looped over. Points the cursor at the cell and returns the foothold; the
   * torch itself is hung by the shared {@link #hangTorch} through {@link #act}.
   */
  @Nullable
  private BlockPos ribTorch(RealPerson person, BlockPos mouth, Rotation rotation, int zr, int side, int floorY) {
    BlockPos cell = new BlockPos(side * (RADIUS + 2), floorY + 1, zr);
    BlockPos world = mouth.offset(cell.rotate(rotation));
    Level level = person.level();
    if (!level.getBlockState(world).isAir()
        || level.getMaxLocalRawBrightness(world) >= TORCH_LIGHT_FLOOR
        || !person.hasItem(Items.TORCH)) {
      return null; // undug, already lit enough, or nothing to hang
    }
    this.offset = cell; // torchWall reads the cursor to find the wall outside the rib
    if (torchWall(level, rotation, world) == null) {
      return null; // no sturdy wall beside the cell (open on every side into a cave)
    }
    BlockPos stand = standToMine(person, world);
    if (stand == null) {
      return null;
    }
    this.block = level.getBlockState(world).getBlock(); // air, so act's null-guard passes
    this.placeFloor = false;
    this.bailWater = false;
    this.placeSeal = false;
    this.placeTorch = true;
    return stand;
  }

  /** The walk-cell Y of the ramp column at forward distance {@code z}, the floor a rib sits on. */
  private static int ribFloorY(int z) {
    return MineTopology.floorY(z);
  }

  /**
   * Whether a local cell lies inside a rib's cross-section: on a rib grid line
   * ({@code z % FAN_PITCH == 0}, no shallower than {@link #FAN_MIN_LINE}), one to
   * {@link #FAN_LENGTH} blocks out past the corridor edge, and in the
   * {@link #FAN_HEIGHT}-tall span standing on that column's floor. A fixed geometric
   * test, like {@link #onRamp}, so the vein detour can tell rib air from cave air
   * without tracking which ribs have been cut.
   */
  private boolean inRib(BlockPos local) {
    return MineTopology.isRib(local);
  }

  /** The dug volume the vein detour follows ore into: the shaft's own corridor, or a rib. */
  private boolean isDugSpace(BlockPos local) {
    return MineTopology.isInterior(local);
  }

  /**
   * The walkway cell at the CENTRE of the cursor column's floor, the one footing all
   * of that column's lining is sealed from. Centre, not the cursor's own x, so a wall
   * on the left and a wall on the right are both placed from one spot instead of the
   * miner shuffling edge to edge; placement is a setBlock, so the middle reaches
   * either side fine. The seal is only ever picked when this cell is standable (see
   * locateNext), so the stand is never null here.
   */
  private BlockPos columnFloor(BlockPos mouth, Rotation rotation) {
    return columnFloor(mouth, rotation, this.offset);
  }

  private BlockPos columnFloor(BlockPos mouth, Rotation rotation, BlockPos local) {
    return mouth.offset(new BlockPos(0, -(local.getZ() + 2), local.getZ()).rotate(rotation));
  }

  @Override
  public boolean act(RealPerson person, BlockPos standTarget) {
    if (this.bucketShownTicks > 0) {
      this.bucketShownTicks--;
      if (this.bucketShownTicks > 0) {
        return true;
      }
      stowBucket(person);
      return false;
    }
    BlockPos mouth = LocationManager.getJobLocation(person);
    if (mouth == BlockPos.ZERO) {
      return false;
    }
    // A vein detour outranks the shaft: seal the cell chosen for plugging, or pull
    // the ore chosen for breaking, before the cursor's own cell is touched.
    if (this.sealTarget != null) {
      return sealAct(person);
    }
    if (this.oreTarget != null) {
      return breakAct(person, mouth, this.oreTarget, true);
    }
    if (this.block == null || this.offset == null) {
      return false;
    }
    BlockPos face = face(mouth, rotation(person));
    if (this.placeFloor) {
      layFloor(person, face);
      return false; // floored (or out of support); the next select re-scans
    }
    if (this.placeTorch) {
      hangTorch(person, rotation(person), face);
      return false; // lit (or out of torches); the next select re-scans
    }
    if (this.placeSeal) {
      laySeal(person, this.sealCell);
      return false; // walled (or out of support); the next select re-scans
    }
    if (this.bailWater) {
      return bailAct(person, mouth, rotation(person), face);
    }
    return breakAct(person, mouth, face, false);
  }

  @Override
  public void acquired(RealPerson person, BlockPos standTarget) {
    if (this.bailWater && showBucket(person)) {
      // Equip before the shared loop's first swing, but start the visible hold
      // only once the miner has arrived and actually cleared the pocket.
      this.bucketShownTicks = 0;
    }
  }

  @Override
  public InteractionHand animationHand(RealPerson person, BlockPos standTarget) {
    return this.bailWater || this.bucketShownTicks > 0
        ? InteractionHand.OFF_HAND
        : person.getUsedItemHand();
  }

  /**
   * Break {@code pos} over {@link #breakTicks} ticks, then take its drops and clear
   * the crack overlay. A wall ore ({@code vein} true) joins {@link #veinToSeal}, to
   * be plugged with a support block once the vein is worked out; a shaft face is simply
   * gone, and the sweep lights the descent behind it ({@link #wantsTorch}). True
   * while still breaking, false when the block comes down and the next select
   * should choose again.
   */
  private boolean breakAct(RealPerson person, BlockPos mouth, BlockPos pos, boolean vein) {
    person.getLookControl().setLookAt(pos.getX(), pos.getY(), pos.getZ(), 30.0F, 30.0F);

    this.breakTime++;
    int progress = (int) ((float) this.breakTime / breakTicks() * 10.0F);
    if (progress != this.lastProgress) {
      person.level().destroyBlockProgress(person.getId(), pos, progress);
      this.lastProgress = progress;
    }
    if (this.breakTime < breakTicks()) {
      return true;
    }

    if (!person.level().isClientSide) {
      // An open boundary beside the block about to fall is supported first, standing
      // right here, so it cannot flood the shaft the moment the block goes.
      if (!sealAround(person, mouth, rotation(person), pos)) {
        person.level().destroyBlockProgress(person.getId(), pos, -1);
        this.breakTime = 0;
        this.lastProgress = -1;
        return false;
      }
      List<ItemStack> drops = Block.getDrops(this.block.defaultBlockState(),
          (ServerLevel) person.level(), pos, person.level().getBlockEntity(pos), person,
          person.getMainHandItem());
      person.level().removeBlock(pos, false);
      PlacedBlockStore.get((ServerLevel) person.level()).clearPlaced(pos);
      person.addItems(drops);
      person.level().playSound((Player) null, pos.getX(), pos.getY(), pos.getZ(),
          this.block.defaultBlockState().getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F,
          person.getRandom().nextFloat() * 0.4F + 0.8F);
      if (vein) {
        this.veinToSeal.push(pos);
        this.veinTaken++;
        Kithkyn.LOGGER.info("[mine] {} pulled {} from the wall at {}",
            person.getName().getString(), this.block.getName().getString(), pos.toShortString());
      }
    }
    person.level().destroyBlockProgress(person.getId(), pos, -1);
    this.breakTime = 0;
    this.lastProgress = -1;
    return false; // next block is chosen by the next select
  }

  @Override
  public void released(RealPerson person, BlockPos standTarget) {
    BlockPos mouth = LocationManager.getJobLocation(person);
    BlockPos breaking = this.oreTarget != null ? this.oreTarget
        : (mouth != BlockPos.ZERO && this.offset != null ? face(mouth, rotation(person)) : null);
    if (breaking != null) {
      person.level().destroyBlockProgress(person.getId(), breaking, -1);
    }
    this.breakTime = 0;
    this.lastProgress = -1;
    stowBucket(person);
    this.bucketShownTicks = 0;
  }

  @Override
  public String describe() {
    return "the mine";
  }

  @Override
  public String activity() {
    return "digging in the mine";
  }

  /** Breaking is timed in ticks, so the act runs on every one of them. */
  @Override
  public int actEveryTicks() {
    return 1;
  }

  /**
   * Near, not touching. The miner works the face from a foothold that is close
   * but need not be the one cell against it: when the shaft breaks into a cave,
   * the cell right beside the floorless edge is out over the void, unreachable,
   * and demanding he stand exactly there stalls him a stride short of work he
   * could plainly reach from the ramp behind (Ember Hill, 2026-09-02). Placing a
   * a support block or breaking a face is done at the block, computed from the cursor,
   * not from where his feet are, so a slightly looser arrival only lets him bridge
   * the edge from the nearest solid footing instead of giving up. Still close
   * enough that he is in the shaft at the face, never digging from the doorstep.
   */
  @Override
  public double reachSqr(RealPerson person) {
    return 12.0D;
  }

  private int breakTicks() {
    return Math.max(1, (int) (this.block.defaultDestroyTime() * 10));
  }

  private BlockPos face(BlockPos mouth, Rotation rotation) {
    return mouth.offset(this.offset.rotate(rotation));
  }

  private Rotation rotation(RealPerson person) {
    Building building = LocationManager.getJobBuilding(person);
    return building == null ? Rotation.NONE : building.getRotation();
  }

  /**
   * A block the miner can stand on to work {@code face}: the one nearest to
   * where they already are, so each pick is a short reachable step deeper rather
   * than a leap the navigator would give up on. Null when nothing around the
   * face is footing, which sends the caller back to the mouth.
   */
  @Nullable
  private BlockPos standToMine(RealPerson person, BlockPos face) {
    Level level = person.level();
    // Beside and above the block, as before, and now the cells one to three
    // below it too. In a five-tall shaft the only footing is the walk cell at
    // the bottom, so a wall ore at head height or in the ceiling, or a face cell
    // high in its column, had no standable neighbour at its own level and was
    // silently dropped: that, not the scan's reach, is why a miner walked past
    // copper in the ceiling and iron mid-wall. Three below is a raised arm's
    // reach, and the same reach a player mines a ceiling with.
    java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
    candidates.add(face.above());
    for (Direction d : Direction.Plane.HORIZONTAL) {
      candidates.add(face.relative(d));
      candidates.add(face.above().relative(d));
    }
    for (int down = 1; down <= 3; down++) {
      BlockPos under = face.below(down);
      candidates.add(under);
      for (Direction d : Direction.Plane.HORIZONTAL) {
        candidates.add(under.relative(d));
      }
    }
    BlockPos best = null;
    double bestDist = Double.MAX_VALUE;
    for (BlockPos candidate : candidates) {
      if (!standable(level, candidate)) {
        continue;
      }
      double dist = candidate.distSqr(person.blockPosition());
      if (dist < bestDist) {
        bestDist = dist;
        best = candidate;
      }
    }
    return best;
  }

  /**
   * Existing ramp footing from which the miner can bridge a missing floor.
   * The general mining stand search deliberately reaches below a face so a
   * miner can work high walls and ceilings. That is unsafe for a floor gap: in
   * a cavern it selected the cave floor beneath the planned ramp, sent the
   * miner over the edge, and triggered rescue on every retry. Floor placement
   * stays on the built side of the ramp instead.
   */
  @Nullable
  private BlockPos standToLayFloor(RealPerson person, BlockPos mouth, Rotation rotation,
      BlockPos localFloorCell) {
    BlockPos best = null;
    double bestDist = Double.MAX_VALUE;
    for (BlockPos local : MineTopology.floorStandCandidates(localFloorCell)) {
      BlockPos candidate = mouth.offset(local.rotate(rotation));
      if (!standable(person.level(), candidate)) {
        continue;
      }
      double distance = candidate.distSqr(person.blockPosition());
      if (distance < bestDist) {
        bestDist = distance;
        best = candidate;
      }
    }
    return best;
  }

  /** Air to occupy, air above for headroom, and a sturdy top below to stand on. */
  private boolean standable(Level level, BlockPos pos) {
    return level.getBlockState(pos).isAir()
        && level.getBlockState(pos.above()).isAir()
        && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
  }

  /**
   * Lay one dirt or stone support the cursor cell's column is missing under it. The
   * miner is already standing on real footing (select found it before act ran),
   * so this is placing a block at arm's length, exactly as digging is breaking
   * one - never a walk out over the void. Out of support material is logged and
   * resets the shaft; the sweep finds the same hole again once restocked.
   */
  private void layFloor(RealPerson person, BlockPos face) {
    this.placeFloor = false;
    BlockPos floor = face.below();
    Level level = person.level();
    person.getLookControl().setLookAt(floor.getX(), floor.getY(), floor.getZ(), 30.0F, 30.0F);
    if (!needsSeal(level, floor)) {
      return; // floored in the meantime (water sealed it, another pass laid it)
    }
    if (!placeSupport(person, floor, "I ran out of dirt or stone to floor the cave in my mine")) {
      resetShaft();
      return;
    }
    Kithkyn.LOGGER.info("[mine] {} floored the shaft at {}",
        person.getName().getString(), floor.toShortString());
    // The next pick re-walks the shaft and meets this cell again, now floored.
  }

  /**
   * Lay one dirt or stone block that closes an open cell of the tunnel's lining, a
   * wall or the ceiling, from the walkway the miner stands on ({@link #columnFloor}
   * found the footing). The same placement the floor is, at a different edge of the
   * same tunnel. Out of support material is logged and resets the shaft; the sweep meets
   * the same gap again once restocked.
   */
  private void laySeal(RealPerson person, BlockPos cell) {
    this.placeSeal = false;
    boolean workingFront = this.temporaryFloodSupport;
    this.temporaryFloodSupport = false;
    if (cell == null) {
      return;
    }
    Level level = person.level();
    person.getLookControl().setLookAt(cell.getX(), cell.getY(), cell.getZ(), 30.0F, 30.0F);
    if (!needsSeal(level, cell)) {
      return; // sealed in the meantime
    }
    if (!placeSupport(person, cell, "I ran out of dirt or stone to wall off the cave in my mine")) {
      resetShaft();
      return;
    }
    if (workingFront) {
      Kithkyn.LOGGER.info("[mine] {} advanced the flooded shaft's working front at {}",
          person.getName().getString(), cell.toShortString());
    } else {
      Kithkyn.LOGGER.info("[mine] {} sealed the shaft lining at {}",
          person.getName().getString(), cell.toShortString());
    }
  }

  /** Spends and places the actual support block the miner carries. */
  private boolean placeSupport(RealPerson person, BlockPos cell, String shortage) {
    ItemStack support = MineSupportMaterials.takeOne(person.personMainInv);
    BlockState placed = MineSupportMaterials.blockState(support);
    if (support.isEmpty() || placed == null) {
      person.logBlocker(shortage);
      return false;
    }
    Level level = person.level();
    level.setBlock(cell, placed, 3);
    if (level instanceof ServerLevel serverLevel) {
      PlacedBlockStore.get(serverLevel).markVillagePlaced(cell);
    }
    level.playSound((Player) null, cell.getX(), cell.getY(), cell.getZ(),
        placed.getSoundType().getPlaceSound(), SoundSource.BLOCKS,
        1.0F, person.getRandom().nextFloat() * 0.4F + 0.8F);
    return true;
  }

  /**
   * Whether a village support inside the ramp still belongs to an active flooded
   * working front. The check walks the connected cluster of village blocks only
   * within the start cell's independently advancing front and stops as soon as
   * that cluster touches water in the same front. This makes the front persistent
   * across saves without inventing a second ownership store, lets the ordinary
   * cursor remove it once a bucket has cleared the water, and prevents a flooded
   * rib from preserving supports throughout the otherwise dry descending ramp.
   */
  private boolean holdsBackFlood(ServerLevel level, BlockPos mouth, Rotation rotation,
      BlockPos start) {
    PlacedBlockStore placed = PlacedBlockStore.get(level);
    return MineTopology.supportClusterTouchesWater(
        start,
        local -> placed.isVillagePlaced(mouth.offset(local.rotate(rotation))),
        local -> level.getBlockState(mouth.offset(local.rotate(rotation))).is(Blocks.WATER),
        BAIL_CAP);
  }

  /**
   * The vein detour: pull the ore a shaft or cave wall has exposed, then seal the
   * holes back up. Returns a foothold to work from while there is vein to mine or to
   * seal, or null when there is none and the shaft should drive on. It only ever
   * starts a vein it can seal - with no support block to spare it leaves the ore in the
   * wall (the "always seal" rule), and it mines new support constantly, so
   * that rarely bites.
   */
  @Nullable
  private BlockPos selectVein(RealPerson person, BlockPos mouth, Rotation rotation) {
    this.oreTarget = null;
    this.sealTarget = null;

    // Still pulling ore: the nearest exposed vein block we have both footing to
    // reach and dirt or stone to seal, until the cap. Each pull opens the next, so the
    // vein follows itself without a flood fill.
    if (this.veinTaken < VEIN_CAP && MineSupportMaterials.held(person.personMainInv) > 0) {
      BlockPos ore = nearestShaftOre(person, mouth, rotation);
      if (ore != null) {
        BlockPos stand = standToMine(person, ore);
        if (stand != null) {
          this.oreTarget = ore;
          this.block = person.level().getBlockState(ore).getBlock();
          return stand;
        }
      }
    }

    // Ore worked out: plug the cells back up, deepest first, so the miner seals the
    // vein as it backs out toward the shaft. A cell it cannot reach to seal from an
    // adjacent foothold is dropped rather than deadlocking the loop.
    while (!this.veinToSeal.isEmpty()) {
      BlockPos cell = this.veinToSeal.peek();
      BlockPos stand = standToMine(person, cell);
      if (stand != null) {
        this.sealTarget = cell;
        return stand;
      }
      this.veinToSeal.pop();
    }

    this.veinTaken = 0;
    return null;
  }

  /**
   * The nearest ore the miner can actually work, on any dug surface around her: a
   * cube of {@link #VEIN_SCAN} blocks to each side of where she stands, showing an
   * open face into the shaft or a rib ({@link #opensIntoShaft}) and mineable with the
   * tool in hand (an iron vein under a stone pick is left, not wasted). Scanning
   * around the miner rather than the corridor's fixed width is what lets a rib's own
   * walls be worked: out in a rib she is well past the corridor, and a scan pinned to
   * the corridor would never see the rock she just exposed. Null when nothing is on
   * show.
   */
  @Nullable
  private BlockPos nearestShaftOre(RealPerson person, BlockPos mouth, Rotation rotation) {
    Level level = person.level();
    BlockPos at = person.blockPosition();
    BlockPos local = at.subtract(mouth).rotate(inverse(rotation));
    BlockPos best = null;
    double bestDist = Double.MAX_VALUE;
    for (int lx = local.getX() - VEIN_SCAN; lx <= local.getX() + VEIN_SCAN; lx++) {
      for (int ly = local.getY() - VEIN_SCAN; ly <= local.getY() + VEIN_SCAN; ly++) {
        for (int lz = local.getZ() - VEIN_SCAN; lz <= local.getZ() + VEIN_SCAN; lz++) {
          BlockPos cell = new BlockPos(lx, ly, lz);
          BlockPos pos = mouth.offset(cell.rotate(rotation));
          if (!isOre(level, pos) || !opensIntoShaft(level, mouth, rotation, cell)
              || !canHarvest(person, pos)) {
            continue;
          }
          double dist = pos.distSqr(at);
          if (dist < bestDist) {
            bestDist = dist;
            best = pos;
          }
        }
      }
    }
    return best;
  }

  /** The rotation that maps world offsets back into the mine's local frame. */
  private static Rotation inverse(Rotation rotation) {
    return switch (rotation) {
      case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
      case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
      default -> rotation;
    };
  }

  /** Any ore, vanilla or modded: the common {@code c:ores} tag every ore block carries. */
  private boolean isOre(Level level, BlockPos pos) {
    return level.getBlockState(pos).is(Tags.Blocks.ORES);
  }

  /**
   * True when the ore (a cell in the mine's frame) shows an open face INTO the
   * shaft: a neighbour that is air and lies on the dug ramp itself, or is a hole
   * the miner opened pulling this vein. Ore open only to a cave, beyond the wall
   * or under the floor, is left where it is; walking out to that is a
   * prospector's job, not this one's. (The corridor test alone is not enough:
   * it spans the whole prism below the mouth, so ore beside a cave pocket three
   * blocks under the ramp floor read as shaft ore, and the miner shuttled after
   * a footing she could never reach until the stranded rule sent her home.)
   */
  private boolean opensIntoShaft(Level level, BlockPos mouth, Rotation rotation, BlockPos cell) {
    for (Direction d : Direction.values()) {
      BlockPos neighbour = cell.relative(d);
      BlockPos world = mouth.offset(neighbour.rotate(rotation));
      if ((isDugSpace(neighbour) || this.veinToSeal.contains(world))
          && level.getBlockState(world).isAir()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a local cell is one the sweep digs: inside the corridor and within
   * the five-cell span a column is dug to, from {@code -(z - 2)} at the ceiling
   * down to {@code -(z + 2)} at the floor (see {@link #columnBottom}).
   */
  private boolean onRamp(BlockPos local) {
    return MineTopology.isRamp(local);
  }

  /**
   * A cell of the lining beside {@code local} that has opened into a cave and
   * wants sealing: the ceiling block above the column's topmost dug cell, or the
   * wall just outside a corridor edge at this cell's own height, when either
   * stands open. Null when the lining here is already solid rock. The caller has
   * already checked the cursor cell is an open corridor cell, so a wall is sealed
   * only beside dug space and a ceiling only over it, never out in the cavern.
   */
  @Nullable
  private BlockPos openLining(Level level, BlockPos mouth, Rotation rotation, BlockPos worldCell, BlockPos local) {
    if (local.getY() == -(local.getZ() - 2) && needsSeal(level, worldCell.above())) {
      return worldCell.above();
    }
    if (Math.abs(local.getX()) == RADIUS) {
      int outward = local.getX() > 0 ? RADIUS + 1 : -(RADIUS + 1);
      BlockPos wallLocal = new BlockPos(outward, local.getY(), local.getZ());
      if (isDugSpace(wallLocal)) {
        return null; // an intentional rib doorway, not missing shaft lining
      }
      BlockPos wall = mouth.offset(wallLocal.rotate(rotation));
      if (needsSeal(level, wall)) {
        return wall;
      }
    }
    return null;
  }

  private record BoundaryBreach(BlockPos inside, BlockPos boundary, @Nullable BlockPos stand) {

    boolean reachable() {
      return stand != null;
    }
  }

  private record Bulkhead(BlockPos inside, BlockPos cell, BlockPos stand) {
  }

  private record Cofferdam(BlockPos inside, BlockPos cell, BlockPos stand) {
  }

  /**
   * Finds a floor, wall, or ceiling breach around the whole connected flooded
   * ramp pocket. The selector calls this before it permits a bucket action, so
   * every reachable edge is closed while the water is still present.
   */
  @Nullable
  private BoundaryBreach openBoundaryAroundFluidPocket(RealPerson person, BlockPos mouth,
      Rotation rotation, BlockPos start) {
    Level level = person.level();
    Deque<BlockPos> frontier = new ArrayDeque<>();
    Set<BlockPos> seen = new HashSet<>();
    frontier.add(start);
    seen.add(start);
    BoundaryBreach firstUnreachable = null;
    while (!frontier.isEmpty() && seen.size() <= BAIL_CAP) {
      BlockPos local = frontier.poll();
      BlockPos world = mouth.offset(local.rotate(rotation));
      if (!isDugSpace(local) || !isLiquid(level, world)) {
        continue;
      }
      BlockPos breach = openExteriorBoundary(level, mouth, rotation, local, true);
      if (breach != null) {
        BlockPos breachLocal = breach.subtract(mouth).rotate(inverse(rotation));
        BlockPos stand = nearestMineStand(person, mouth, rotation, breachLocal);
        BoundaryBreach candidate = new BoundaryBreach(local, breach, stand);
        if (candidate.reachable()) {
          return candidate;
        }
        if (firstUnreachable == null) {
          firstUnreachable = candidate;
        }
      }
      for (Direction direction : Direction.values()) {
        BlockPos next = local.relative(direction);
        BlockPos nextWorld = mouth.offset(next.rotate(rotation));
        if (isDugSpace(next) && isLiquid(level, nextWorld) && seen.add(next)) {
          frontier.add(next);
        }
      }
    }
    // An unreachable leak is still a leak. Returning it prevents the policy from
    // declaring the pocket closed and bailing water that will immediately refill.
    return firstUnreachable;
  }

  /**
   * The first open neighbour outside the unified ramp-and-rib interior. All six
   * directions matter because the ramp descends diagonally, so the next z cell
   * can be exterior lining at the current y. Air is included while auditing a
   * flooded pocket because water will flow into it as soon as the bucket clears
   * the adjacent interior cell. A pre-break audit can ask for fluid only, leaving
   * the deliberate open entrance alone.
   */
  @Nullable
  private BlockPos openExteriorBoundary(Level level, BlockPos mouth, Rotation rotation,
      BlockPos local, boolean includeAir) {
    for (Direction direction : Direction.values()) {
      BlockPos neighbour = local.relative(direction);
      if (!MineTopology.crossesExteriorBoundary(local, neighbour)) {
        continue;
      }
      BlockPos world = mouth.offset(neighbour.rotate(rotation));
      BlockState state = level.getBlockState(world);
      if (!state.getFluidState().isEmpty() || (includeAir && state.isAir())) {
        return world;
      }
    }
    return null;
  }

  /**
   * A flooded rib whose outside source cannot be reached is closed at the
   * doorway instead. The target remains part of the planned mine topology, so
   * this is deliberately narrower than ordinary boundary sealing: only a rib
   * doorway may be sacrificed, never a cell in the descending ramp.
   */
  @Nullable
  private Bulkhead reachableRibBulkhead(RealPerson person, BlockPos mouth,
      Rotation rotation, @Nullable BoundaryBreach breach) {
    if (breach == null || !inRib(breach.inside())) {
      return null;
    }
    BlockPos doorway = MineTopology.ribDoorway(breach.inside());
    BlockPos cell = mouth.offset(doorway.rotate(rotation));
    if (!isLiquid(person.level(), cell)) {
      return null;
    }
    BlockPos stand = nearestMineStand(person, mouth, rotation, doorway);
    return stand == null ? null : new Bulkhead(doorway, cell, stand);
  }

  /**
   * A water cell the miner can replace with support to advance a dry working
   * front toward an otherwise unreachable leak. Only ramp water is considered:
   * a flooded rib has its narrower doorway bulkhead, and lava remains an honest
   * stop rather than a surface to build through.
   */
  @Nullable
  private Cofferdam reachableCofferdam(RealPerson person, BlockPos mouth,
      Rotation rotation, BlockPos start) {
    Level level = person.level();
    Deque<BlockPos> frontier = new ArrayDeque<>();
    Set<BlockPos> seen = new HashSet<>();
    frontier.add(start);
    seen.add(start);
    while (!frontier.isEmpty() && seen.size() <= BAIL_CAP) {
      BlockPos local = frontier.poll();
      BlockPos world = mouth.offset(local.rotate(rotation));
      if (!isDugSpace(local) || !level.getBlockState(world).is(Blocks.WATER)) {
        continue;
      }
      if (onRamp(local)) {
        BlockPos stand = nearestMineStand(person, mouth, rotation, local);
        if (stand != null) {
          return new Cofferdam(local, world, stand);
        }
      }
      for (Direction direction : Direction.values()) {
        BlockPos next = local.relative(direction);
        BlockPos nextWorld = mouth.offset(next.rotate(rotation));
        if (isDugSpace(next) && isLiquid(level, nextWorld) && seen.add(next)) {
          frontier.add(next);
        }
      }
    }
    return null;
  }

  /** A dry planned mine cell within arm's reach of a target local cell. */
  @Nullable
  private BlockPos nearestMineStand(RealPerson person, BlockPos mouth,
      Rotation rotation, BlockPos targetLocal) {
    BlockPos target = mouth.offset(targetLocal.rotate(rotation));
    BlockPos best = null;
    double bestDist = Double.MAX_VALUE;
    int search = 3;
    for (int dz = -search; dz <= search; dz++) {
      for (int dy = -search; dy <= 1; dy++) {
        for (int dx = -search; dx <= search; dx++) {
          BlockPos local = targetLocal.offset(dx, dy, dz);
          BlockPos candidate = mouth.offset(local.rotate(rotation));
          if (!isDugSpace(local) || !standable(person.level(), candidate)
              || candidate.distSqr(target) > reachSqr(person)) {
            continue;
          }
          double distance = candidate.distSqr(person.blockPosition());
          if (distance < bestDist) {
            bestDist = distance;
            best = candidate;
          }
        }
      }
    }
    return best;
  }

  /** Any open floor, side wall, or ceiling cell around one corridor cell. */
  @Nullable
  private BlockPos openBoundary(Level level, BlockPos mouth, Rotation rotation,
      BlockPos worldCell, BlockPos local) {
    if (columnBottom(local) && needsSeal(level, worldCell.below())) {
      return worldCell.below();
    }
    return openLining(level, mouth, rotation, worldCell, local);
  }

  /** Whether the miner's held tool would actually drop {@code pos}, not shatter it for nothing. */
  private boolean canHarvest(RealPerson person, BlockPos pos) {
    net.minecraft.world.level.block.state.BlockState state = person.level().getBlockState(pos);
    return !state.requiresCorrectToolForDrops()
        || person.getMainHandItem().isCorrectToolForDrops(state);
  }

  /**
   * Plug one mined-out vein cell with dirt or stone from the miner's stock and drop it
   * from the seal list. One cell per visit, so the miner walks the vein back out
   * sealing as it goes; if support is gone it leaves the cell pending until restocked.
   */
  private boolean sealAct(RealPerson person) {
    BlockPos cell = this.sealTarget;
    this.sealTarget = null;
    if (placeSupport(person, cell, "I ran out of dirt or stone to seal the vein in my mine")) {
      this.veinToSeal.remove(cell);
    }
    return false; // one cell per cycle; the next select picks the next
  }

  /**
   * A non-fluid light source, which the cursor never breaks. Lava emits light too but
   * is a fluid, so it falls through to {@link #impassable} and stops the shaft as it
   * always did; every torch and lantern (soul, redstone, wall) reads as light and is
   * left alone.
   */
  private boolean isLight(Level level, BlockPos pos) {
    net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
    return state.getFluidState().isEmpty() && state.getLightEmission(level, pos) > 0;
  }

  /**
   * Whether the open cursor cell wants a torch: the head-height cell of a ramp
   * column on the corridor's edge (local x at the radius, y one above the column's
   * bottom), with the walk cell under it already open, deep enough below the lit
   * mouth ({@link #TORCH_MIN_DEPTH}) and reading dimmer than
   * {@link #TORCH_LIGHT_FLOOR}, while the pack holds a torch to hang and a wall
   * stands beside the cell to hang it on.
   *
   * <p>Only once the cell below is open, and the light read in the cell itself
   * rather than at the miner's feet: a cell's light is recomputed on the light
   * engine's own thread (ThreadedLevelLightEngine queues checkBlock), so a freshly
   * opened cell still reads the zero of the stone it was, and the first version of
   * this rule read every layer as dark and hung a torch on every step down. The
   * walk cell opens a full layer after the head cell, by when the head cell's
   * light has long settled, and it is where the miner stands to hang the torch, so
   * a fresh layer's torch is at most one layer behind the face. A player's spacing
   * falls out of the read: a torch gives 14, light drops one per block and the ramp
   * costs about two per layer, so at a floor of 4 the next torch hangs about six
   * layers on. A torch counts as light, so a lit cell is never picked twice.
   */
  private boolean wantsTorch(RealPerson person, BlockPos mouth, Rotation rotation, BlockPos cell) {
    BlockPos local = this.offset;
    if (Math.abs(local.getX()) != RADIUS || local.getY() != -(local.getZ() + 1)) {
      return false;
    }
    Level level = person.level();
    return mouth.getY() - cell.getY() >= TORCH_MIN_DEPTH
        && level.getBlockState(cell.below()).isAir()
        && level.getMaxLocalRawBrightness(cell) < TORCH_LIGHT_FLOOR
        && person.hasItem(Items.TORCH)
        && torchWall(level, rotation, cell) != null;
  }

  /**
   * The world-frame direction from the cursor cell to the wall a torch in it would
   * hang on: a sturdy face just OUTSIDE the dig corridor, which the cursor never
   * comes back to dig out, so the torch's own support stays put. Told from corridor
   * in the mine's local frame, then rotated to the world. Null while nothing sturdy
   * stands there, as beside a cave the shaft has broken into.
   */
  @Nullable
  private Direction torchWall(Level level, Rotation rotation, BlockPos cell) {
    for (Direction local : Direction.Plane.HORIZONTAL) {
      if (MineShaft.withinCorridor(this.offset.relative(local))) {
        continue;
      }
      Direction worldDir = rotation.rotate(local);
      BlockPos wall = cell.relative(worldDir);
      if (level.getBlockState(wall).isFaceSturdy(level, wall, worldDir.getOpposite())) {
        return worldDir;
      }
    }
    return null;
  }

  /**
   * Hang one torch from the pack in the cursor cell, on the wall beside it, at head
   * height where a player puts one. A WALL torch, not a floor one: a torch standing
   * on the floor sits in the dig path, and the block under it then has to be left
   * unbroken to avoid knocking it out, which is what riddled the shaft with holes.
   * The miner stands in the walk cell under it or beside it (select found the
   * footing before act ran), so this is placing a block at arm's length. Out of
   * torches leaves the cell dark until the pack is restocked, and the sweep meets
   * it again on the next pick.
   */
  private void hangTorch(RealPerson person, Rotation rotation, BlockPos cell) {
    this.placeTorch = false;
    Level level = person.level();
    person.getLookControl().setLookAt(cell.getX(), cell.getY(), cell.getZ(), 30.0F, 30.0F);
    Direction worldDir = torchWall(level, rotation, cell);
    if (!level.getBlockState(cell).isAir() || worldDir == null) {
      return; // filled or unwalled in the meantime; the next pick reads it afresh
    }
    if (person.removeItem(Items.TORCH, 1).getCount() < 1) {
      return; // handed away since the pick; the next pick asks the pack again
    }
    level.setBlock(cell, Blocks.WALL_TORCH.defaultBlockState()
        .setValue(WallTorchBlock.FACING, worldDir.getOpposite()), 3);
    level.playSound((Player) null, cell.getX(), cell.getY(), cell.getZ(),
        Blocks.WALL_TORCH.defaultBlockState().getSoundType().getPlaceSound(), SoundSource.BLOCKS,
        1.0F, person.getRandom().nextFloat() * 0.4F + 0.8F);
    Kithkyn.LOGGER.info("[mine] {} lit the shaft at {}", person.getName().getString(),
        cell.toShortString());
  }

  /**
   * Whether this cursor cell is the BOTTOM of its column's dug span - the cell
   * whose underside is the ramp's floor. A layer at depth m sweeps the five rows
   * z in [m-2, m+2], so a column at local z is dug down to y = -(z + 2), and
   * only cells on that diagonal ever need a floor laid beneath them. (First
   * shipped as -(z + 1), one above the true bottom: the "floor" landed in a cell
   * the next layer's own sweep dug back out, live-caught as the same cell being
   * floored twice.)
   */
  private boolean columnBottom(BlockPos local) {
    return local.getY() == -(local.getZ() + 2);
  }

  /**
   * Bail the flooded stretch of the ramp the cursor found, standing at it. The
   * selector has already closed the pocket's reachable floor, walls, and ceiling;
   * this bounded flood fill clears the connected interior in one act. The bucket
   * moves to the off hand before the shared work animation and remains visible for
   * a beat. It is a reusable tool and is never filled or consumed.
   */
  private boolean bailAct(RealPerson person, BlockPos mouth, Rotation rotation, BlockPos face) {
    this.bailWater = false;
    Level level = person.level();
    person.getLookControl().setLookAt(face.getX(), face.getY(), face.getZ(), 30.0F, 30.0F);
    if (!carriesBucket(person)) {
      return false; // handed away since the pick; the next pick treats it as an obstacle
    }
    BlockPos start = face.subtract(mouth).rotate(inverse(rotation));
    if (openBoundaryAroundFluidPocket(person, mouth, rotation, start) != null) {
      return false; // the world changed on the walk; the next selection seals it first
    }
    if (!showBucket(person)) {
      person.logBlocker("I need my off hand free to use the bucket in my mine");
      return false;
    }
    Deque<BlockPos> frontier = new ArrayDeque<>();
    Set<BlockPos> seen = new HashSet<>();
    frontier.add(start);
    seen.add(start);
    int cleared = 0;
    while (!frontier.isEmpty() && cleared < BAIL_CAP) {
      BlockPos local = frontier.poll();
      BlockPos world = mouth.offset(local.rotate(rotation));
      if (!isLiquid(level, world)) {
        continue;
      }
      for (Direction d : Direction.values()) {
        BlockPos next = local.relative(d);
        BlockPos nextWorld = mouth.offset(next.rotate(rotation));
        if (isDugSpace(next) && isLiquid(level, nextWorld) && seen.add(next)) {
          frontier.add(next);
        }
      }
      level.setBlock(world, Blocks.AIR.defaultBlockState(), 3);
      cleared++;
    }
    level.playSound((Player) null, face.getX(), face.getY(), face.getZ(),
        SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
    Kithkyn.LOGGER.info("[mine] {} bailed the sealed shaft at {}: {} cell(s) cleared",
        person.getName().getString(), face.toShortString(), cleared);
    return true; // hold this target while the off-hand bucket remains visible
  }

  /** Close an air or fluid boundary beside the next block before breaking it. */
  private boolean sealAround(RealPerson person, BlockPos mouth, Rotation rotation, BlockPos world) {
    Level level = person.level();
    BlockPos local = world.subtract(mouth).rotate(inverse(rotation));
    BlockPos breach;
    while ((breach = openExteriorBoundary(level, mouth, rotation, local, false)) != null) {
      if (!placeSupport(person, breach,
          "I ran out of dirt or stone to seal the leak before digging farther")) {
        resetShaft();
        return false;
      }
    }
    while ((breach = openBoundary(level, mouth, rotation, world, local)) != null) {
      if (!placeSupport(person, breach,
          "I ran out of dirt or stone to seal the wall before digging farther")) {
        resetShaft();
        return false;
      }
    }
    return true;
  }

  /** Any fluid occupying the cell, flowing or still. */
  private static boolean isLiquid(Level level, BlockPos pos) {
    return !level.getBlockState(pos).getFluidState().isEmpty();
  }

  /** Air or fluid at the shaft boundary needs a solid support block. */
  private static boolean needsSeal(Level level, BlockPos pos) {
    BlockState state = level.getBlockState(pos);
    return state.isAir() || !state.getFluidState().isEmpty();
  }

  /**
   * The miner counts as carrying a bucket when one is in the pack, the main hand, or
   * the off hand -- the bucket is a persistent TOOL that enables clearing, so it
   * counts wherever it sits (restocked into the pack, handed over directly, or out on
   * show while bailing). It is never filled or consumed.
   */
  private boolean carriesBucket(RealPerson person) {
    return person.hasItem(Items.BUCKET)
        || person.getMainHandItem().is(Items.BUCKET)
        || person.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.BUCKET);
  }

  /**
   * Bring the miner's own bucket out into the off hand and keep it there for a
   * beat, so clearing the pocket reads as bailing with a bucket rather than magic.
   * Moved from the pack, never copied: hand drop chance is 100% (see Person), so a
   * display copy would drop a second bucket on death. The main hand keeps its
   * pickaxe. Logs the moment the bucket comes to hand, so the action is greppable
   * in the server log ([mine]).
   */
  private boolean showBucket(RealPerson person) {
    ItemStack offhand = person.getItemBySlot(EquipmentSlot.OFFHAND);
    if (offhand.is(Items.BUCKET)) {
      this.bucketShownTicks = BUCKET_SHOWN_TICKS;
      return true;
    }
    if (!offhand.isEmpty()) {
      return false;
    }
    ItemStack mainhand = person.getMainHandItem();
    if (mainhand.is(Items.BUCKET)) {
      person.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
      person.setItemSlot(EquipmentSlot.OFFHAND, mainhand);
      this.bucketShownTicks = BUCKET_SHOWN_TICKS;
      return true;
    }
    ItemStack pulled = person.removeItem(Items.BUCKET, 1);
    if (pulled.getCount() == 1) {
      person.setItemSlot(EquipmentSlot.OFFHAND, pulled);
      this.bucketShownTicks = BUCKET_SHOWN_TICKS;
      Kithkyn.LOGGER.info("[mine] {} brought its bucket to its off hand",
          person.getName().getString());
      return true;
    }
    return false;
  }

  /**
   * Put the shown bucket back in the pack once the beat is over, on release, or on
   * the first tick after a save caught it mid-show. Kept in hand rather than
   * dropped when the pack has no room, so a full pack never litters the shaft with
   * buckets. Only ever touches a bucket, so a future off-hand tool would be safe.
   */
  private void stowBucket(RealPerson person) {
    ItemStack offhand = person.getItemBySlot(EquipmentSlot.OFFHAND);
    if (!offhand.is(Items.BUCKET) || person.isInventoryFull()) {
      return;
    }
    person.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    person.addItems(List.of(offhand));
  }

  /**
   * Advances the cursor to the next block worth breaking. {@code WORK} when a cell
   * is chosen; {@code BLOCKED} when the miner has hit something they cannot get
   * through (bedrock, or lava/water with no bucket), which is where the fan-out
   * takes over and where the obstacle is logged into their personal log, surfacing
   * in conversation ("the mine is full of lava") as the seed of a quest anyone or no
   * one may resolve; {@code DRY_HOLE} when nothing solid lay within the pattern.
   */
  private RampScan locateNext(RealPerson person, BlockPos mouth, Rotation rotation) {
    // The shaft ramps down and AWAY from the mouth, toward local +Z. Local -Z is
    // the mine's entrance/front, its opening and both chests sit there, so leaning
    // the descent toward +Z digs deeper into the ground behind the mine instead of
    // undermining its own entrance. Direction is in the mine's LOCAL frame; face()
    // rotates it into the world by the founding rotation, so it is correct in every
    // orientation. (Leaning -Z, which every prior angled version did, was Aaron's
    // long-standing "mining the wrong way" bug: the ramp ate back under the door.)
    // Every pick starts over at the mouth: the audit (see the class note).
    this.offset = new BlockPos(-(RADIUS + 1), -1, -(RADIUS - 1));
    this.inward = 1;
    this.placeFloor = false;
    this.bailWater = false;
    this.placeTorch = false;
    this.placeSeal = false;
    this.sealCell = null;
    this.sealFooting = null;
    this.sealStand = null;
    this.temporaryFloodSupport = false;
    int steps = 0;
    BlockPos facePos = null;
    do {
      if (++steps > MAX_CURSOR_STEPS) {
        return RampScan.DRY_HOLE; // nothing solid within reach of the pattern; try again later
      }
      this.offset = this.offset.offset(1, 0, 0);
      if (this.offset.getX() >= RADIUS + 1) {
        this.offset = new BlockPos(-RADIUS, this.offset.getY(), this.offset.getZ() + 1);
      }
      if (this.offset.getZ() >= RADIUS + 1 + this.inward) {
        this.offset = new BlockPos(-RADIUS, this.offset.getY() - 1, this.inward - 1);
        this.inward++;
      }
      facePos = face(mouth, rotation);
      this.block = person.level().getBlockState(facePos).getBlock();
      // A temporary working front is planned mine interior, but it is not a face
      // to dig while its connected support cluster still touches water. Treat it
      // as already-audited space and continue toward the flooded cells beyond it.
      // Once bailing removes that water, the same cursor sees the support as solid
      // again and ordinary mining dismantles it.
      if (person.level() instanceof ServerLevel serverLevel
          && onRamp(this.offset)
          && PlacedBlockStore.get(serverLevel).isVillagePlaced(facePos)
          && holdsBackFlood(serverLevel, mouth, rotation, this.offset)) {
        this.block = Blocks.AIR;
        continue;
      }
      // The shaft has broken into a cave here: this cell is the bottom of its
      // column's dug span and there is nothing under it to walk on. Completing
      // the floor IS the work at this cell - one dirt or stone block along the cave's
      // edge, before the sweep goes anywhere else - whether or not the pack
      // holds any: with none, layFloor logs the shortage and stands the shaft
      // down, and the fetch trip refills the pack from the stores. The sweep
      // used to skip the hole when the pack was empty and go on to a face
      // across the void that nothing could stand at, which from outside was a
      // miner who had simply stopped flooring.
      if (columnBottom(this.offset)
          && needsSeal(person.level(), facePos.below())) {
        this.placeFloor = true;
        return RampScan.WORK;
      }
      // The tunnel's lining where the shaft has broken into a cave: a wall to the
      // side of an open or flooded corridor cell, or the ceiling above one, standing
      // open into air or liquid. Seal it from the walkway before lighting or bailing,
      // so a shaft crosses a cave as an enclosed passage, not a bare catwalk.
      // Only sealed once the column has a walkway at its CENTRE to stand on: without
      // that footing the lining seal would return a null stand and reset the whole
      // shaft (Aaron's "shouldn't be that fragile", 2026-09-02), so instead the sweep
      // floors and digs this column first and comes back to its lining a pass later.
      boolean liquid = isLiquid(person.level(), facePos);
      if ((this.block == Blocks.AIR || liquid) && onRamp(this.offset)
          && standable(person.level(), columnFloor(mouth, rotation))) {
        BlockPos lining = openLining(person.level(), mouth, rotation, facePos, this.offset);
        if (lining != null) {
          this.sealCell = lining;
          this.sealFooting = columnFloor(mouth, rotation);
          this.placeSeal = true;
          return RampScan.WORK;
        }
      }
      if (liquid) {
        BoundaryBreach breach = openBoundaryAroundFluidPocket(person, mouth, rotation, this.offset);
        Bulkhead bulkhead = breach != null && !breach.reachable()
            ? reachableRibBulkhead(person, mouth, rotation, breach)
            : null;
        Cofferdam cofferdam = breach != null && !breach.reachable() && bulkhead == null
            ? reachableCofferdam(person, mouth, rotation, this.offset)
            : null;
        MineFluidPolicy.Action action = MineFluidPolicy.next(
            breach != null, breach != null && breach.reachable(), bulkhead != null,
            cofferdam != null, carriesBucket(person));
        if (action == MineFluidPolicy.Action.SEAL) {
          this.offset = breach.inside();
          this.block = person.level().getBlockState(face(mouth, rotation)).getBlock();
          this.sealCell = breach.boundary();
          this.sealStand = breach.stand();
          this.placeSeal = true;
          return RampScan.WORK;
        }
        if (action == MineFluidPolicy.Action.BULKHEAD) {
          this.offset = bulkhead.inside();
          this.block = person.level().getBlockState(bulkhead.cell()).getBlock();
          this.sealCell = bulkhead.cell();
          this.sealStand = bulkhead.stand();
          this.placeSeal = true;
          return RampScan.WORK;
        }
        if (action == MineFluidPolicy.Action.COFFERDAM) {
          this.offset = cofferdam.inside();
          this.block = person.level().getBlockState(cofferdam.cell()).getBlock();
          this.sealCell = cofferdam.cell();
          this.sealStand = cofferdam.stand();
          this.temporaryFloodSupport = true;
          this.placeSeal = true;
          return RampScan.WORK;
        }
        if (action == MineFluidPolicy.Action.BAIL) {
          this.bailWater = true;
          return RampScan.WORK;
        }
        return RampScan.BLOCKED;
      }
      // An open cell of the shaft gone dark is work after its lining is sound:
      // hang a torch there before the sweep goes on toward the face.
      if (this.block == Blocks.AIR && wantsTorch(person, mouth, rotation, facePos)) {
        this.placeTorch = true;
        return RampScan.WORK;
      }
      // Never mine a light source -- skip any emitter (torch, lantern, glowstone) so
      // the cursor leaves the shaft's own lighting alone. Torches now hang on the
      // walls (see hangTorch), so the block BELOW a torch no longer needs
      // protecting: digging it cannot knock a wall torch out, and skipping it was
      // exactly what left a hole under every torch.
    } while (this.block == Blocks.AIR
        || isLight(person.level(), facePos));

    if (impassable(person)) {
      return RampScan.BLOCKED; // the fan-out takes over; the obstacle is logged once, on the way in
    }
    return RampScan.WORK;
  }

  /**
   * Note the obstacle that ended the descent, the first pick the ramp comes back
   * blocked: log it into the miner's personal record (lava or water she has no
   * bucket for, the seed of a quest), and lay a sponge into a water face if she is
   * carrying one, which may soak the leak and let the descent resume. Bedrock is
   * the honest floor of the world and wants no log. Latched by {@link #fanning} so
   * it fires once, not on every pick while she is fanning out.
   */
  private void onRampBlocked(RealPerson person, BlockPos mouth, Rotation rotation) {
    if (this.fanning) {
      return;
    }
    this.fanning = true;
    if (this.block == Blocks.WATER && person.removeItem(Items.SPONGE, 1).getCount() == 1) {
      person.level().setBlock(face(mouth, rotation), Blocks.SPONGE.defaultBlockState(), 2);
    }
    logObstacle(person);
  }

  private boolean impassable(RealPerson person) {
    // Only what genuinely cannot be dug stops the shaft: liquid with no bucket, and
    // bedrock. The wrong tool does NOT: a miner who gave his pickaxe away, or never
    // had the right one, breaks the face by hand instead of standing idle, slower
    // and dropping nothing until he is re-equipped, but working. Standing still
    // because a block wants a better tool was Aaron's "why would he stop" (2026-09-02).
    return this.block == Blocks.WATER || this.block == Blocks.LAVA || this.block == Blocks.BEDROCK;
  }

  private void logObstacle(RealPerson person) {
    if (this.block == Blocks.LAVA) {
      person.logBlocker("lava is blocking my mine - I could seal it off if I had a bucket");
    } else if (this.block == Blocks.WATER) {
      person.logBlocker("water is trapped inside my mine - I need a bucket to drain it");
    }
  }

  private void resetShaft() {
    this.block = null;
    this.offset = null;
    this.inward = 1;
    this.breakTime = 0;
    this.lastProgress = -1;
    this.placeFloor = false;
    this.bailWater = false;
    this.placeTorch = false;
    this.placeSeal = false;
    this.sealCell = null;
    this.sealFooting = null;
    this.sealStand = null;
    this.temporaryFloodSupport = false;
  }

}
