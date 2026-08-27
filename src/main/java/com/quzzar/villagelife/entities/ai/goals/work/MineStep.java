package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * The target given to the loop is the shaft ENTRANCE rather than the block
 * being broken. That is deliberate and matches what the goal this replaces did:
 * a miner stands at the mouth and works the face at reach, because the block
 * they are about to break is inside solid rock and walking to it is not
 * possible. Targeting the block itself would have every miner fail to path,
 * stall, and eventually be teleported home as stranded.
 */
public final class MineStep implements WorkStep {

  private static final int RADIUS = 2;

  /**
   * How many blocks the cursor may step over in one search. The version this
   * replaces looped until it found something solid, with nothing to stop it:
   * a shaft opening into a cave, or one dug below the bottom of the world,
   * reads as air forever and would have hung the server inside a while loop.
   */
  private static final int MAX_CURSOR_STEPS = 4096;

  private BlockPos offset;
  private Block block;
  private int inward = 1;
  private int breakTime;
  private int lastProgress = -1;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    BlockPos mouth = LocationManager.getJobLocation(person);
    if (mouth == BlockPos.ZERO) {
      return null;
    }
    return locateNext(person, mouth, rotation(person)) ? mouth : null;
  }

  @Override
  public boolean act(RealPerson person, BlockPos mouth) {
    if (this.block == null || this.offset == null) {
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
    }
    person.level().destroyBlockProgress(person.getId(), face, -1);
    this.breakTime = 0;
    this.lastProgress = -1;
    return false; // next block is chosen by the next select
  }

  @Override
  public void released(RealPerson person, BlockPos mouth) {
    if (this.offset != null) {
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

  /** Measured to the mouth of the shaft, which is where the miner stands. */
  @Override
  public double reachSqr(RealPerson person) {
    return 10.0D;
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
   * Advances the cursor to the next block worth breaking. False when the miner
   * has hit something they cannot get through, which resets the shaft and is
   * written into their personal log - it surfaces in conversation ("the mine is
   * full of lava") and is the seed of a quest anyone or no one may resolve.
   */
  private boolean locateNext(RealPerson person, BlockPos mouth, Rotation rotation) {
    if (this.offset == null) {
      this.offset = new BlockPos(-(RADIUS + 1), -1, RADIUS - 1);
    }
    int steps = 0;
    do {
      if (++steps > MAX_CURSOR_STEPS) {
        resetShaft();
        return false; // nothing solid within reach of the pattern; try again later
      }
      this.offset = this.offset.offset(1, 0, 0);
      if (this.offset.getX() >= RADIUS + 1) {
        this.offset = new BlockPos(-RADIUS, this.offset.getY(), this.offset.getZ() - 1);
      }
      if (this.offset.getZ() <= -(RADIUS + 1 + this.inward)) {
        this.offset = new BlockPos(-RADIUS, this.offset.getY() - 1, 1 - this.inward);
        this.inward++;
      }
      this.block = person.level().getBlockState(face(mouth, rotation)).getBlock();
    } while (this.block == Blocks.AIR || this.block == Blocks.TORCH
        || this.block == Blocks.WALL_TORCH || this.block == Blocks.LANTERN);

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
