package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;

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

  private BlockPos offset;
  private Block block;
  private int inward = 1;
  private int breakTime;
  private int lastProgress = -1;
  private int sinceTorch;

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
    // deepens. The face is still broken relative to the job location (see act),
    // so if no footing near it can be found the miner falls back to the mouth
    // and the shaft still advances: the descent never stalls the digging.
    BlockPos stand = standToMine(person, face(mouth, rotation(person)));
    return stand != null ? stand : mouth;
  }

  @Override
  public boolean act(RealPerson person, BlockPos standTarget) {
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
      maybeTorch(person, face);
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
   * Every so many blocks, stand a torch in the space just opened so the shaft
   * does not go dark. Physical: the torch comes out of the miner's pack (kept
   * stocked from village stores when they turn in for the night), so an unlit
   * mine reads as a village with no torches to spare rather than a bug. The
   * cursor skips torches, so one placed here is never mistaken for stone later.
   */
  private void maybeTorch(RealPerson person, BlockPos face) {
    if (++this.sinceTorch < BLOCKS_PER_TORCH) {
      return;
    }
    this.sinceTorch = 0; // one attempt per interval, whatever its outcome
    Level level = person.level();
    if (level.getMaxLocalRawBrightness(face) >= 8
        || !level.getBlockState(face.below()).isFaceSturdy(level, face.below(), Direction.UP)) {
      return;
    }
    if (person.removeItem(Items.TORCH, 1).getCount() < 1) {
      return; // out of torches; the shaft stays dark until the miner restocks
    }
    level.setBlock(face, Blocks.TORCH.defaultBlockState(), 3);
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
   * tool, never filled or consumed. A wall with no cobblestone to spare is left as
   * it is, so the leak surfaces on the impassable path rather than being opened up.
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
      } else if (person.removeItem(Items.COBBLESTONE, 1).getCount() == 1) {
        level.setBlock(world, Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.playSound((Player) null, world.getX(), world.getY(), world.getZ(),
            Blocks.COBBLESTONE.defaultBlockState().getSoundType().getPlaceSound(),
            SoundSource.BLOCKS, 1.0F, 0.8F);
      }
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
      if ((this.block == Blocks.WATER || this.block == Blocks.LAVA) && person.hasItem(Items.BUCKET)) {
        waterproof(person, mouth, rotation, this.offset);
        this.block = person.level().getBlockState(facePos).getBlock();
      }
      // Never mine a light source, and never undermine a torch or lantern standing on
      // the block below -- the cursor steps down into its own supports as the shaft
      // deepens, so this is what keeps a dug shaft lit. Any emitter counts (soul,
      // redstone and wall torches, lanterns), not just a plain torch.
    } while (this.block == Blocks.AIR
        || isLight(person.level(), facePos)
        || isLight(person.level(), facePos.above()));

    // Seal any liquid on the walls around the solid block about to be dug, so a
    // pocket beside the frontier is cobbled off before it can flood the shaft.
    if (person.hasItem(Items.BUCKET)) {
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
      person.logIssue("lava is blocking my mine and I cannot dig past it", Optional.empty());
    } else if (this.block == Blocks.WATER) {
      person.logIssue("water keeps flooding my mine", Optional.empty());
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
