package com.quzzar.villagelife.entities.ai.goals;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Opening a fence gate on the way through, and closing it behind.
 *
 * The gate half of what {@link com.quzzar.villagelife.entities.ai.PersonPathNavigation}
 * starts: the route now runs through closed gates, and this is what turns the
 * bump against one into an open gate and, a second later, a closed one. It is
 * vanilla's {@code OpenDoorGoal} written for {@link FenceGateBlock}, which
 * that goal cannot be told about: it tests for {@code DoorBlock} by class and
 * looks one block up for the door's top half, where a gate has nothing.
 *
 * The gate swings away from whoever opens it, as it does for a player, and
 * closes twenty ticks after opening whether or not the person is through,
 * which is the door's rule too: a pen gate left standing open is a pen with
 * no animals in it by evening.
 */
public final class OpenFenceGateGoal extends Goal {

  /** Ticks the gate stays open after the person reaches it, as for doors. */
  private static final int OPEN_TICKS = 20;

  /** Within this of a gate on the path counts as at it; vanilla's door figure. */
  private static final double AT_GATE_SQR = 2.25D;

  private final Mob mob;
  private BlockPos gatePos = BlockPos.ZERO;
  private boolean hasGate;
  private boolean passed;
  private float openDirX;
  private float openDirZ;
  private int openTicks;

  public OpenFenceGateGoal(Mob mob) {
    if (!(mob.getNavigation() instanceof GroundPathNavigation)) {
      throw new IllegalArgumentException("Unsupported mob type for OpenFenceGateGoal");
    }
    this.mob = mob;
  }

  @Override
  public boolean canUse() {
    if (!this.mob.horizontalCollision) {
      return false; // not up against anything yet
    }
    GroundPathNavigation navigation = (GroundPathNavigation) this.mob.getNavigation();
    Path path = navigation.getPath();
    if (path == null || path.isDone() || !navigation.canOpenDoors()) {
      return false;
    }
    // A gate stands at foot level, so the node itself is the gate's block;
    // the door goal looks one up, for the top half a gate does not have.
    for (int i = 0; i < Math.min(path.getNextNodeIndex() + 2, path.getNodeCount()); i++) {
      Node node = path.getNode(i);
      this.gatePos = new BlockPos(node.x, node.y, node.z);
      if (this.mob.distanceToSqr(this.gatePos.getX(), this.mob.getY(), this.gatePos.getZ()) <= AT_GATE_SQR) {
        this.hasGate = isGate(this.gatePos);
        if (this.hasGate) {
          return true;
        }
      }
    }
    this.gatePos = this.mob.blockPosition();
    this.hasGate = isGate(this.gatePos);
    return this.hasGate;
  }

  @Override
  public boolean canContinueToUse() {
    return this.openTicks > 0 && !this.passed;
  }

  @Override
  public void start() {
    this.passed = false;
    this.openTicks = OPEN_TICKS;
    this.openDirX = (float) (this.gatePos.getX() + 0.5D - this.mob.getX());
    this.openDirZ = (float) (this.gatePos.getZ() + 0.5D - this.mob.getZ());
    this.mob.swing(InteractionHand.MAIN_HAND);
    setOpen(true);
  }

  @Override
  public void stop() {
    setOpen(false);
  }

  @Override
  public boolean requiresUpdateEveryTick() {
    return true;
  }

  @Override
  public void tick() {
    this.openTicks--;
    // Passed once the gate is behind them: the same dot-product test the door
    // goal uses, against the direction they were facing when they opened it.
    float toX = (float) (this.gatePos.getX() + 0.5D - this.mob.getX());
    float toZ = (float) (this.gatePos.getZ() + 0.5D - this.mob.getZ());
    if (this.openDirX * toX + this.openDirZ * toZ < 0.0F) {
      this.passed = true;
    }
  }

  private boolean isGate(BlockPos pos) {
    return this.mob.level().getBlockState(pos).getBlock() instanceof FenceGateBlock;
  }

  /** Swing the gate the way vanilla does for a hand: away from the opener. */
  private void setOpen(boolean open) {
    if (!this.hasGate) {
      return;
    }
    BlockState state = this.mob.level().getBlockState(this.gatePos);
    if (!(state.getBlock() instanceof FenceGateBlock) || state.getValue(FenceGateBlock.OPEN) == open) {
      return;
    }
    if (open) {
      Direction facing = this.mob.getDirection();
      if (state.getValue(FenceGateBlock.FACING) == facing.getOpposite()) {
        state = state.setValue(FenceGateBlock.FACING, facing);
      }
    }
    state = state.setValue(FenceGateBlock.OPEN, open);
    this.mob.level().setBlock(this.gatePos, state, 10);
    this.mob.level().playSound(null, this.gatePos,
        open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS,
        1.0F, this.mob.getRandom().nextFloat() * 0.1F + 0.9F);
    this.mob.level().gameEvent(this.mob, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, this.gatePos);
  }
}
