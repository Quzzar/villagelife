package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.buildings.Building;

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
 * way it always did, and the descent is layered on top - when no footing near
 * the face can be found the target falls back to the mouth, so the digging can
 * never fail even if the footing search comes up empty.
 */
public final class MineStep implements BlockWorkStep {

  private static final int RADIUS = 2;

  /**
   * How many blocks the cursor may step over in one search. The version this
   * replaces looped until it found something solid, with nothing to stop it:
   * a shaft opening into a cave, or one dug below the bottom of the world,
   * reads as air forever and would have hung the server inside a while loop.
   */
  private static final int MAX_CURSOR_STEPS = 4096;

  /** Blocks broken between torches, so a deepening shaft does not go dark. */
  private static final int BLOCKS_PER_TORCH = 6;

  /**
   * Torches only start once the shaft has dropped this far below its mouth: the
   * entrance and the first steps down catch daylight, so torching there just
   * litters a lit approach. Below it the shaft is dark and actually wants light.
   */
  private static final int TORCH_MIN_DEPTH = 10;

  /**
   * Ticks to keep the bucket out in the off hand after a seal or a clear, so the
   * waterproofing is a beat you can watch rather than an instant. About two
   * seconds; a wider leak reseals cell by cell and holds it out longer on its own.
   */
  private static final int BUCKET_SHOWN_TICKS = 40;

  private BlockPos offset;
  private Block block;
  private int inward = 1;
  private int breakTime;
  private int lastProgress = -1;
  private int sinceTorch;
  private int bucketShownTicks;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    BlockPos mouth = LocationManager.getJobLocation(person);
    if (mouth == BlockPos.ZERO) {
      return null;
    }
    if (!locateNext(person, mouth, rotation(person))) {
      return null;
    }
    // Stand next to the face and work it, walking down into the shaft as it
    // deepens. When no footing near the face can be found, the descent has opened
    // onto a void, so lay a single foothold to carry the path on (bridgeFooting).
    // Failing that, fall back to the mouth and the shaft still advances, so the
    // descent never stalls the digging.
    BlockPos face = face(mouth, rotation(person));
    BlockPos stand = standToMine(person, face);
    if (stand == null) {
      stand = bridgeFooting(person, face);
    }
    return stand != null ? stand : mouth;
  }

  @Override
  public boolean act(RealPerson person, BlockPos standTarget) {
    tickBucket(person);
    BlockPos mouth = LocationManager.getJobLocation(person);
    if (mouth == BlockPos.ZERO || this.block == null || this.offset == null) {
      return false;
    }
    BlockPos face = face(mouth, rotation(person));
    person.getLookControl().setLookAt(face.getX(), face.getY(), face.getZ(), 30.0F, 30.0F);

    this.breakTime++;
    int progress = (int) ((float) this.breakTime / breakTicks() * 10.0F);
    if (progress != this.lastProgress) {
      person.level().destroyBlockProgress(person.getId(), face, progress);
      this.lastProgress = progress;
    }
    if (this.breakTime < breakTicks()) {
      return true;
    }

    if (!person.level().isClientSide) {
      List<ItemStack> drops = Block.getDrops(this.block.defaultBlockState(),
          (ServerLevel) person.level(), face, person.level().getBlockEntity(face), person,
          person.getMainHandItem());
      person.level().removeBlock(face, false);
      person.addItems(drops);
      person.level().playSound((Player) null, face.getX(), face.getY(), face.getZ(),
          this.block.defaultBlockState().getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F,
          person.getRandom().nextFloat() * 0.4F + 0.8F);
      maybeTorch(person, mouth, rotation(person), face);
    }
    person.level().destroyBlockProgress(person.getId(), face, -1);
    this.breakTime = 0;
    this.lastProgress = -1;
    return false; // next block is chosen by the next select
  }

  @Override
  public void released(RealPerson person, BlockPos standTarget) {
    BlockPos mouth = LocationManager.getJobLocation(person);
    if (mouth != BlockPos.ZERO && this.offset != null) {
      person.level().destroyBlockProgress(person.getId(), face(mouth, rotation(person)), -1);
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
    BlockPos[] candidates = {
        face.above(),
        face.north(), face.south(), face.east(), face.west(),
        face.above().north(), face.above().south(), face.above().east(), face.above().west(),
    };
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
   * Make a foothold to work {@code face} when {@link #standToMine} found none: the
   * descent has opened onto a void, a cave undercutting the walkway. The nearest spot
   * that is open with headroom but has nothing to stand on gets a SINGLE cobblestone
   * under it, and the miner stands there. One block, only where a foot needs to go, so
   * a cave is carried across a step at a time rather than flooded with a whole floor.
   * Null when the miner has no cobblestone to spare or nothing around the face is
   * floorable, which sends the caller back to the mouth as a missing foothold always
   * did.
   */
  @Nullable
  private BlockPos bridgeFooting(RealPerson person, BlockPos face) {
    Level level = person.level();
    BlockPos[] candidates = {
        face.above(),
        face.north(), face.south(), face.east(), face.west(),
        face.above().north(), face.above().south(), face.above().east(), face.above().west(),
    };
    BlockPos best = null;
    double bestDist = Double.MAX_VALUE;
    for (BlockPos candidate : candidates) {
      // A foothold but for its missing floor: open, headroom above, a void below.
      boolean floorless = level.getBlockState(candidate).isAir()
          && level.getBlockState(candidate.above()).isAir()
          && !level.getBlockState(candidate.below())
              .isFaceSturdy(level, candidate.below(), Direction.UP);
      if (floorless && candidate.distSqr(person.blockPosition()) < bestDist) {
        bestDist = candidate.distSqr(person.blockPosition());
        best = candidate;
      }
    }
    if (best == null || person.removeItem(Items.COBBLESTONE, 1).getCount() != 1) {
      return null;
    }
    level.setBlock(best.below(), Blocks.COBBLESTONE.defaultBlockState(), 3);
    Villagelife.LOGGER.info("[mine] {} laid a foothold at {} to carry the shaft across a void",
        person.getName().getString(), best.toShortString());
    return best;
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
   * Every so many blocks, hang a torch on a shaft wall so the descent does not go
   * dark. Physical: the torch comes out of the miner's pack (kept stocked from
   * village stores when they turn in for the night), so an unlit mine reads as a
   * village with no torches to spare rather than a bug. The cursor skips torches,
   * so one placed here is never mistaken for stone later.
   *
   * A WALL torch, not a floor one: a torch standing on the floor sits in the dig
   * path, and the block under it then has to be left unbroken to avoid knocking it
   * out -- which is what riddled the shaft with holes. And only once the shaft has
   * dropped {@link #TORCH_MIN_DEPTH} below its lit mouth, and only where it is dark.
   */
  private void maybeTorch(RealPerson person, BlockPos mouth, Rotation rotation, BlockPos face) {
    if (++this.sinceTorch < BLOCKS_PER_TORCH) {
      return;
    }
    Level level = person.level();
    // Already lit, or still in the daylit approach near the mouth -> no torch wanted
    // here; reset and wait out another full interval before trying again.
    if (level.getMaxLocalRawBrightness(face) >= 8 || mouth.getY() - face.getY() < TORCH_MIN_DEPTH) {
      this.sinceTorch = 0;
      return;
    }
    // Hang it on a wall that STAYS put -- a solid cell just outside the dig corridor,
    // so the cursor never comes back and digs the torch's own support out. Work in the
    // mine's local frame to tell corridor (will be dug) from wall, then rotate to the
    // world for the block ops. A centre cell has no such wall: leave the counter hot
    // and try again next block rather than skipping a whole interval into the dark.
    for (Direction local : Direction.Plane.HORIZONTAL) {
      if (withinCorridor(this.offset.relative(local))) {
        continue;
      }
      Direction worldDir = rotation.rotate(local);
      BlockPos wall = face.relative(worldDir);
      if (!level.getBlockState(wall).isFaceSturdy(level, wall, worldDir.getOpposite())) {
        continue;
      }
      if (person.removeItem(Items.TORCH, 1).getCount() >= 1) {
        level.setBlock(face, Blocks.WALL_TORCH.defaultBlockState()
            .setValue(WallTorchBlock.FACING, worldDir.getOpposite()), 3);
      }
      this.sinceTorch = 0; // placed, or out of torches -> reset either way
      return;
    }
  }

  /**
   * The shaft corridor the cursor actually digs: {@code RADIUS} to either side of
   * the centre line, from the entrance (local z) down and forward. Liquid inside it
   * is cleared to air; liquid outside it (the sides, the ceiling, behind the mouth)
   * is a wall to seal, so the two cases in {@link #waterproof} split on this.
   */
  private boolean withinCorridor(BlockPos local) {
    return Math.abs(local.getX()) <= RADIUS
        && local.getY() <= -1
        && local.getZ() >= -(RADIUS - 1);
  }

  /**
   * Keeps the shaft dry around {@code centre} (a cursor offset in the mine's local
   * frame, so it stays orientation-correct once rotated): water or lava on the
   * corridor walls is sealed with cobblestone from the miner's own mined stock, and
   * liquid inside the corridor is cleared to air. The bucket that gates this stays a
   * tool, never filled or consumed; each seal brings it out into the miner's off hand
   * for a beat ({@link #showBucket}), so the clearing is something you can watch. A
   * wall with no cobblestone to spare is left as it is, so the leak surfaces on the
   * impassable path rather than being opened up.
   */
  private void waterproof(RealPerson person, BlockPos mouth, Rotation rotation, BlockPos centre) {
    Level level = person.level();
    BlockPos[] cells = {
        centre, centre.above(), centre.below(),
        centre.north(), centre.south(), centre.east(), centre.west(),
    };
    for (BlockPos local : cells) {
      BlockPos world = mouth.offset(local.rotate(rotation));
      Block found = level.getBlockState(world).getBlock();
      if (found != Blocks.WATER && found != Blocks.LAVA) {
        continue;
      }
      if (withinCorridor(local)) {
        level.setBlock(world, Blocks.AIR.defaultBlockState(), 2);
        sealShell(person, mouth, rotation, local);
        showBucket(person);
      } else if (person.removeItem(Items.COBBLESTONE, 1).getCount() == 1) {
        level.setBlock(world, Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.playSound((Player) null, world.getX(), world.getY(), world.getZ(),
            Blocks.COBBLESTONE.defaultBlockState().getSoundType().getPlaceSound(),
            SoundSource.BLOCKS, 1.0F, 0.8F);
        showBucket(person);
      }
    }
  }

  /**
   * Seal the wall of an interior cell just cleared to air, so lava or water cannot
   * flow back into the walkway and re-strand the miner: only the shell (cells the
   * shaft does not dig) is cobbled, the walkable interior stays air. Cobblestone
   * comes from the miner's own mined stock; the bucket is a tool, never spent.
   */
  private void sealShell(RealPerson person, BlockPos mouth, Rotation rotation, BlockPos interior) {
    Level level = person.level();
    for (Direction d : Direction.values()) {
      BlockPos neighbor = interior.relative(d);
      if (withinCorridor(neighbor)) {
        continue; // walkway -> stays air, never walled off
      }
      BlockPos world = mouth.offset(neighbor.rotate(rotation));
      Block found = level.getBlockState(world).getBlock();
      if ((found == Blocks.WATER || found == Blocks.LAVA)
          && person.removeItem(Items.COBBLESTONE, 1).getCount() == 1) {
        level.setBlock(world, Blocks.COBBLESTONE.defaultBlockState(), 3);
      }
    }
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
      Villagelife.LOGGER.info("[mine] {} brought its bucket to its off hand to seal a leak at {}",
          person.getName().getString(), person.blockPosition().toShortString());
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
    if (this.offset == null) {
      this.offset = new BlockPos(-(RADIUS + 1), -1, -(RADIUS - 1));
    }
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
      // With a bucket in the pack, keep the shaft dry rather than abandoning it at a
      // leak: seal liquid on the walls with cobblestone and clear liquid inside the
      // corridor to air (see waterproof). The bucket is only a tool, never filled or
      // consumed. Clearing the face to air lets the loop scan on; with no bucket the
      // liquid stays and drops to the sponge/impassable path below.
      if ((this.block == Blocks.WATER || this.block == Blocks.LAVA) && carriesBucket(person)) {
        waterproof(person, mouth, rotation, this.offset);
        this.block = person.level().getBlockState(facePos).getBlock();
      }
      // Never mine a light source -- skip any emitter (torch, lantern, glowstone) so
      // the cursor leaves the shaft's own lighting alone. Torches now hang on the
      // walls (see maybeTorch), so the block BELOW a torch no longer needs
      // protecting: digging it cannot knock a wall torch out, and skipping it was
      // exactly what left a hole under every torch.
    } while (this.block == Blocks.AIR
        || isLight(person.level(), facePos));

    // Seal any liquid on the walls around the solid block about to be dug, so a
    // pocket beside the frontier is cobbled off before it can flood the shaft.
    if (carriesBucket(person)) {
      waterproof(person, mouth, rotation, this.offset);
    }

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
  }

}
