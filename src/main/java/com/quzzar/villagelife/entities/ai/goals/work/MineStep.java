package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.buildings.Building;
import com.quzzar.villagelife.village.buildings.MineShaft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.WallTorchBlock;

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
 * gets one cobblestone laid under it - PLACE work selected by the same cursor
 * that selects BREAK work, done standing on the floor already there (the ramp
 * behind, or the cell floored a moment ago), never by walking out over the gap.
 * The cave is crossed edge by edge as the sweep advances, darkness gets a torch
 * like anywhere else, and a miner out of cobblestone logs it and stands down at
 * the mouth until restocked. Nothing here reasons about the void itself: no
 * bridging mode, no walking-and-planking - both were tried, and both ended with
 * cobble littered far outside the mine.
 *
 * <p>Water and lava are work, not an emergency, for a miner with a bucket. A
 * flooded stretch of the ramp is picked like any other cell: she walks down to
 * it and, standing at the face, bails the whole connected pocket in one act,
 * clearing every flooded ramp cell and plugging every liquid source off the
 * ramp, wall, floor or ceiling, with cobblestone, so an aquifer becomes a
 * cobbled tube. Clearing one cell at a time from wherever she happened to stand,
 * which is what this did before, lost the race to the water flowing back between
 * picks and looked from outside like a miner ignoring her bucket.
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
   * cobblestone at face-below instead of digging the face.
   */
  private boolean placeFloor;

  /**
   * True when the cursor cell holds water or lava and the miner has a bucket:
   * the act bails the flooded stretch from the face instead of digging.
   */
  private boolean bailWater;


  // A wall vein under extraction: ore already pulled and awaiting its cobblestone
  // plug (sealed deepest-first, so the miner backs out toward the shaft as it
  // seals), plus the ore being broken and the cell being sealed this cycle. All
  // empty or null while the miner is simply driving the shaft.
  private final Deque<BlockPos> veinToSeal = new ArrayDeque<>();
  private BlockPos oreTarget;
  private BlockPos sealTarget;
  private int veinTaken;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    BlockPos mouth = LocationManager.getJobLocation(person);
    if (mouth == BlockPos.ZERO) {
      return null;
    }
    // A wall vein takes priority over driving the shaft on: pull the exposed ore,
    // then plug the holes with cobblestone, before the descent picks back up.
    BlockPos veinStand = selectVein(person, mouth, rotation(person));
    if (veinStand != null) {
      return veinStand;
    }
    if (!locateNext(person, mouth, rotation(person))) {
      return null;
    }
    // Stand next to the face and work it, walking down into the shaft as it
    // deepens. No footing reachable - a hole in the ramp behind, say - falls
    // back to the mouth, and the shaft waits.
    BlockPos face = face(mouth, rotation(person));
    BlockPos stand = standToMine(person, face);
    return stand != null ? stand : mouth;
  }

  @Override
  public boolean act(RealPerson person, BlockPos standTarget) {
    tickBucket(person);
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
      return false; // floored (or out of cobble); the next select re-scans
    }
    if (this.placeTorch) {
      hangTorch(person, rotation(person), face);
      return false; // lit (or out of torches); the next select re-scans
    }
    if (this.bailWater) {
      bailAct(person, mouth, rotation(person), face);
      return false; // dry (or out of cobble); the next select re-scans
    }
    return breakAct(person, mouth, face, false);
  }

  /**
   * Break {@code pos} over {@link #breakTicks} ticks, then take its drops and clear
   * the crack overlay. A wall ore ({@code vein} true) joins {@link #veinToSeal}, to
   * be plugged with cobblestone once the vein is worked out; a shaft face is simply
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
      // A pocket beside the block about to fall is cobbled off first, standing
      // right here, so it cannot flood the shaft the moment the block goes.
      if (carriesBucket(person)) {
        sealAround(person, mouth, rotation(person), pos);
      }
      List<ItemStack> drops = Block.getDrops(this.block.defaultBlockState(),
          (ServerLevel) person.level(), pos, person.level().getBlockEntity(pos), person,
          person.getMainHandItem());
      person.level().removeBlock(pos, false);
      person.addItems(drops);
      person.level().playSound((Player) null, pos.getX(), pos.getY(), pos.getZ(),
          this.block.defaultBlockState().getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F,
          person.getRandom().nextFloat() * 0.4F + 0.8F);
      if (vein) {
        this.veinToSeal.push(pos);
        this.veinTaken++;
        Villagelife.LOGGER.info("[mine] {} pulled {} from the wall at {}",
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

  /** Close, so the miner stands at the face they are working, deep in the shaft. */
  @Override
  public double reachSqr(RealPerson person) {
    return 6.0D;
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

  /** Air to occupy, air above for headroom, and a sturdy top below to stand on. */
  private boolean standable(Level level, BlockPos pos) {
    return level.getBlockState(pos).isAir()
        && level.getBlockState(pos.above()).isAir()
        && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
  }

  /**
   * Lay the one cobblestone the cursor cell's column is missing under it. The
   * miner is already standing on real footing (select found it before act ran),
   * so this is placing a block at arm's length, exactly as digging is breaking
   * one - never a walk out over the void. Out of cobblestone is logged and
   * resets the shaft; the sweep finds the same hole again once restocked.
   */
  private void layFloor(RealPerson person, BlockPos face) {
    this.placeFloor = false;
    BlockPos floor = face.below();
    Level level = person.level();
    person.getLookControl().setLookAt(floor.getX(), floor.getY(), floor.getZ(), 30.0F, 30.0F);
    if (!level.getBlockState(floor).isAir()) {
      return; // floored in the meantime (water sealed it, another pass laid it)
    }
    if (person.removeItem(Items.COBBLESTONE, 1).getCount() != 1) {
      person.logIssue("I ran out of cobblestone to floor the cave in my mine", Optional.empty());
      resetShaft();
      return;
    }
    level.setBlock(floor, Blocks.COBBLESTONE.defaultBlockState(), 3);
    level.playSound((Player) null, floor.getX(), floor.getY(), floor.getZ(),
        Blocks.COBBLESTONE.defaultBlockState().getSoundType().getPlaceSound(), SoundSource.BLOCKS,
        1.0F, person.getRandom().nextFloat() * 0.4F + 0.8F);
    Villagelife.LOGGER.info("[mine] {} floored the shaft at {}",
        person.getName().getString(), floor.toShortString());
    // The next pick re-walks the shaft and meets this cell again, now floored.
  }

  /**
   * The vein detour: pull the ore a shaft or cave wall has exposed, then seal the
   * holes back up. Returns a foothold to work from while there is vein to mine or to
   * seal, or null when there is none and the shaft should drive on. It only ever
   * starts a vein it can seal - with no cobblestone to spare it leaves the ore in the
   * wall (the "always seal" rule), and it grinds cobble out of stone constantly, so
   * that rarely bites.
   */
  @Nullable
  private BlockPos selectVein(RealPerson person, BlockPos mouth, Rotation rotation) {
    this.oreTarget = null;
    this.sealTarget = null;

    // Still pulling ore: the nearest exposed vein block we have both footing to
    // reach and cobblestone to seal, until the cap. Each pull opens the next, so the
    // vein follows itself without a flood fill.
    if (this.veinTaken < VEIN_CAP && person.hasItem(Items.COBBLESTONE)) {
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
   * The nearest ore on the shaft's own surfaces that the miner can actually
   * work: in the mine's frame, the corridor's full width plus its walls, the
   * rows within {@link #VEIN_SCAN} of where she stands and as far up and down,
   * showing an open face into the shaft, and mineable with the tool in hand (an
   * iron vein under a stone pick is left, not wasted). Null when nothing is on
   * show.
   */
  @Nullable
  private BlockPos nearestShaftOre(RealPerson person, BlockPos mouth, Rotation rotation) {
    Level level = person.level();
    BlockPos at = person.blockPosition();
    BlockPos local = at.subtract(mouth).rotate(inverse(rotation));
    BlockPos best = null;
    double bestDist = Double.MAX_VALUE;
    for (int lx = -(RADIUS + 1); lx <= RADIUS + 1; lx++) {
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
      if ((onRamp(neighbour) || this.veinToSeal.contains(world))
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
    return MineShaft.withinCorridor(local)
        && local.getY() >= -(local.getZ() + 2)
        && local.getY() <= -(local.getZ() - 2);
  }

  /** Whether the miner's held tool would actually drop {@code pos}, not shatter it for nothing. */
  private boolean canHarvest(RealPerson person, BlockPos pos) {
    net.minecraft.world.level.block.state.BlockState state = person.level().getBlockState(pos);
    return !state.requiresCorrectToolForDrops()
        || person.getMainHandItem().isCorrectToolForDrops(state);
  }

  /**
   * Plug one mined-out vein cell with cobblestone from the miner's stock and drop it
   * from the seal list. One cell per visit, so the miner walks the vein back out
   * sealing as it goes; if the cobble is somehow gone it leaves the cell open rather
   * than stalling the loop.
   */
  private boolean sealAct(RealPerson person) {
    BlockPos cell = this.sealTarget;
    this.sealTarget = null;
    this.veinToSeal.remove(cell);
    if (person.removeItem(Items.COBBLESTONE, 1).getCount() == 1) {
      Level level = person.level();
      level.setBlock(cell, Blocks.COBBLESTONE.defaultBlockState(), 3);
      level.playSound((Player) null, cell.getX(), cell.getY(), cell.getZ(),
          Blocks.COBBLESTONE.defaultBlockState().getSoundType().getPlaceSound(),
          SoundSource.BLOCKS, 1.0F, 0.8F);
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
    Villagelife.LOGGER.info("[mine] {} lit the shaft at {}", person.getName().getString(),
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
   * Bail the flooded stretch of the ramp the cursor found, standing at it. A
   * bounded flood fill from the face over ramp cells holding water or lava
   * clears every one of them to air and plugs every liquid neighbour OFF the
   * ramp, wall, floor or ceiling, with cobblestone from the miner's own mined
   * stock, so an aquifer becomes a cobbled tube and nothing flows back. One act
   * takes the whole pocket, because clearing one cell at a time lost the race to
   * the water flowing back between picks. The bucket is a tool, out for a beat
   * ({@link #showBucket}), never filled. Out of cobblestone mid-pocket logs the
   * shortage and stands the shaft down; the next pick finds the water again
   * once the pack is restocked.
   */
  private void bailAct(RealPerson person, BlockPos mouth, Rotation rotation, BlockPos face) {
    this.bailWater = false;
    Level level = person.level();
    person.getLookControl().setLookAt(face.getX(), face.getY(), face.getZ(), 30.0F, 30.0F);
    if (!carriesBucket(person)) {
      return; // handed away since the pick; the next pick treats it as an obstacle
    }
    Deque<BlockPos> frontier = new ArrayDeque<>();
    Set<BlockPos> seen = new HashSet<>();
    BlockPos start = face.subtract(mouth).rotate(inverse(rotation));
    frontier.add(start);
    seen.add(start);
    int cleared = 0;
    int sealed = 0;
    while (!frontier.isEmpty() && cleared < BAIL_CAP) {
      BlockPos local = frontier.poll();
      BlockPos world = mouth.offset(local.rotate(rotation));
      if (!isLiquid(level, world)) {
        continue;
      }
      // Plug the sources first, then open the cell, so nothing pours in between.
      for (Direction d : Direction.values()) {
        BlockPos next = local.relative(d);
        BlockPos nextWorld = mouth.offset(next.rotate(rotation));
        if (!isLiquid(level, nextWorld)) {
          continue;
        }
        if (onRamp(next)) {
          if (seen.add(next)) {
            frontier.add(next);
          }
          continue;
        }
        if (person.removeItem(Items.COBBLESTONE, 1).getCount() != 1) {
          person.logIssue("I ran out of cobblestone to seal the water in my mine", Optional.empty());
          Villagelife.LOGGER.info("[mine] {} ran out of cobblestone bailing the shaft at {} ({} cleared, {} sealed)",
              person.getName().getString(), face.toShortString(), cleared, sealed);
          resetShaft();
          return;
        }
        level.setBlock(nextWorld, Blocks.COBBLESTONE.defaultBlockState(), 3);
        sealed++;
      }
      level.setBlock(world, Blocks.AIR.defaultBlockState(), 3);
      cleared++;
    }
    showBucket(person);
    level.playSound((Player) null, face.getX(), face.getY(), face.getZ(),
        Blocks.COBBLESTONE.defaultBlockState().getSoundType().getPlaceSound(),
        SoundSource.BLOCKS, 1.0F, 0.8F);
    Villagelife.LOGGER.info("[mine] {} bailed the shaft at {}: {} cell(s) cleared, {} leak(s) sealed",
        person.getName().getString(), face.toShortString(), cleared, sealed);
  }

  /**
   * Cobble off any liquid touching the block about to be broken that lies off
   * the ramp, so opening it cannot pour water or lava into the shaft. Done
   * standing at the face, like the break itself. A liquid neighbour ON the ramp
   * is left: the sweep meets it as a cell and bails it properly.
   */
  private void sealAround(RealPerson person, BlockPos mouth, Rotation rotation, BlockPos world) {
    Level level = person.level();
    BlockPos local = world.subtract(mouth).rotate(inverse(rotation));
    for (Direction d : Direction.values()) {
      BlockPos next = local.relative(d);
      BlockPos nextWorld = mouth.offset(next.rotate(rotation));
      if (onRamp(next) || !isLiquid(level, nextWorld)) {
        continue;
      }
      if (person.removeItem(Items.COBBLESTONE, 1).getCount() == 1) {
        level.setBlock(nextWorld, Blocks.COBBLESTONE.defaultBlockState(), 3);
        showBucket(person);
      }
    }
  }

  /** Water or lava, flowing or still. */
  private static boolean isLiquid(Level level, BlockPos pos) {
    Block block = level.getBlockState(pos).getBlock();
    return block == Blocks.WATER || block == Blocks.LAVA;
  }

  /**
   * The miner counts as carrying a bucket when one is in the pack, the main hand, or
   * the off hand -- the bucket is a persistent TOOL that enables clearing, so it
   * counts wherever it sits (restocked into the pack, handed over directly, or out on
   * show while sealing). It is never filled or consumed.
   */
  private boolean carriesBucket(RealPerson person) {
    return person.hasItem(Items.BUCKET)
        || person.getMainHandItem().is(Items.BUCKET)
        || person.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.BUCKET);
  }

  /**
   * Bring the miner's own bucket out into the off hand and keep it there for a
   * beat, so a seal or a clear reads as bailing with a bucket rather than magic.
   * Moved from the pack, never copied: hand drop chance is 100% (see Person), so a
   * display copy would drop a second bucket on death. The main hand keeps its
   * pickaxe; the beat refreshes on every reseal. Logs the moment the bucket comes
   * to hand, so a seal is greppable in the server log ([mine]).
   */
  private void showBucket(RealPerson person) {
    this.bucketShownTicks = BUCKET_SHOWN_TICKS;
    if (!person.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) {
      return; // already on show, or the hand is busy - never stack a second
    }
    ItemStack pulled = person.removeItem(Items.BUCKET, 1);
    if (pulled.getCount() == 1) {
      person.setItemSlot(EquipmentSlot.OFFHAND, pulled);
      Villagelife.LOGGER.info("[mine] {} brought its bucket to its off hand",
          person.getName().getString());
    }
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

  /** Run down the show timer each tick and stow the bucket when it lapses. */
  private void tickBucket(RealPerson person) {
    if (this.bucketShownTicks > 0) {
      this.bucketShownTicks--;
    } else {
      stowBucket(person);
    }
  }

  /**
   * Advances the cursor to the next block worth breaking. False when the miner
   * has hit something they cannot get through, which resets the shaft and is
   * written into their personal log - it surfaces in conversation ("the mine is
   * full of lava") and is the seed of a quest anyone or no one may resolve.
   */
  private boolean locateNext(RealPerson person, BlockPos mouth, Rotation rotation) {
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
    int steps = 0;
    BlockPos facePos = null;
    do {
      if (++steps > MAX_CURSOR_STEPS) {
        resetShaft();
        return false; // nothing solid within reach of the pattern; try again later
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
      // Liquid at the cursor with a bucket in the pack is work, not an obstacle:
      // the miner walks down to it and bails the flooded stretch standing at the
      // face (bailAct). Nothing is cleared from here; the sweep only chooses.
      // With no bucket the liquid stays and drops to the sponge/impassable path.
      if ((this.block == Blocks.WATER || this.block == Blocks.LAVA) && carriesBucket(person)) {
        this.bailWater = true;
        return true;
      }
      // The shaft has broken into a cave here: this cell is the bottom of its
      // column's dug span and there is nothing under it to walk on. Completing
      // the floor IS the work at this cell - one cobblestone along the cave's
      // edge, before the sweep goes anywhere else. Without cobblestone the hole
      // is left, and the ramp's own footing rules decide what stays reachable.
      if (columnBottom(this.offset)
          && person.level().getBlockState(facePos.below()).isAir()
          && person.hasItem(Items.COBBLESTONE)) {
        this.placeFloor = true;
        return true;
      }
      // An open cell of the shaft gone dark is work too: hang a torch there before
      // the sweep goes on toward the face. Read on the way down on every pick, so a
      // shaft dug dark, or inherited dark, is lit from the top.
      if (this.block == Blocks.AIR && wantsTorch(person, mouth, rotation, facePos)) {
        this.placeTorch = true;
        return true;
      }
      // Never mine a light source -- skip any emitter (torch, lantern, glowstone) so
      // the cursor leaves the shaft's own lighting alone. Torches now hang on the
      // walls (see hangTorch), so the block BELOW a torch no longer needs
      // protecting: digging it cannot knock a wall torch out, and skipping it was
      // exactly what left a hole under every torch.
    } while (this.block == Blocks.AIR
        || isLight(person.level(), facePos));

    if (impassable(person)) {
      if (this.block == Blocks.WATER && person.removeItem(Items.SPONGE, 1).getCount() == 1) {
        person.level().setBlock(face(mouth, rotation), Blocks.SPONGE.defaultBlockState(), 2);
      }
      logObstacle(person);
      resetShaft();
      return false;
    }
    return true;
  }

  private boolean impassable(RealPerson person) {
    return this.block == Blocks.WATER || this.block == Blocks.LAVA || this.block == Blocks.BEDROCK
        || (this.block.defaultBlockState().requiresCorrectToolForDrops()
            && !person.getMainHandItem().isCorrectToolForDrops(this.block.defaultBlockState()));
  }

  private void logObstacle(RealPerson person) {
    if (this.block == Blocks.LAVA) {
      person.logIssue("lava is blocking my mine - I could seal it off if I had a bucket", Optional.empty());
    } else if (this.block == Blocks.WATER) {
      person.logIssue("water keeps flooding my mine - a bucket would let me seal it", Optional.empty());
    } else if (this.block != Blocks.BEDROCK) {
      person.logIssue("the stone in my mine is too hard for my tool", Optional.empty());
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
  }

}
