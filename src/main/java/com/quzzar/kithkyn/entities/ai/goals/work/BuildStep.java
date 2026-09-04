package com.quzzar.kithkyn.entities.ai.goals.work;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.village.Village;
import com.quzzar.kithkyn.village.bookkeeping.NoResourceBookkeepingEvent;
import com.quzzar.kithkyn.village.buildings.BuildProgress;
import com.quzzar.kithkyn.village.buildings.StructureInProgress;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Raising the village's current project: an ADVANCE step, which attends a site
 * and ticks a named subsystem rather than doing the work itself.
 *
 * The verb the docs' list was missing, and the one that keeps the framework
 * honest. Clearing ground, placing blocks and tracking progress all live in
 * {@link StructureInProgress}; a step of a hundred lines could not express any
 * of it and should not try. What arrives here is a site to stand at and a state
 * machine to advance twice a second.
 *
 * Every framework needs one of these. Naming it is what stops the other five
 * verbs quietly growing special cases until they become it.
 */
public final class BuildStep implements BlockWorkStep {

  /** A builder counts as on site once inside the building's radius, plus a little. */
  private static final double RADIUS_SLACK = 1.1D;
  private String demolitionBlocker = "";

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    StructureInProgress project = project(person);
    if (project == null || project.getProgress() == BuildProgress.COMPLETE) {
      return null;
    }
    // While the recipe is still being gathered, this step stays dormant: the
    // GatherStep owns the project, and acquiring here would call startBuilding()
    // on a build that has not been paid for.
    if (project.getProgress() == BuildProgress.GATHERING) {
      return null;
    }
    return BlockPos.of(project.getBuilding().getCenterLocation());
  }

  @Override
  public void acquired(RealPerson person, BlockPos target) {
    StructureInProgress project = project(person);
    if (project != null) {
      project.startBuilding();
    }
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    StructureInProgress project = project(person);
    if (project == null || project.getProgress() == BuildProgress.COMPLETE) {
      return false;
    }
    if (project.getProgress() == BuildProgress.DEMOLISHING) {
      String blocker = project.demolishStep(person.getVillage());
      if (!demolitionBlocker.isEmpty() && !demolitionBlocker.equals(blocker)) {
        person.clearBlocker(demolitionBlocker);
      }
      demolitionBlocker = blocker;
      if (!blocker.isEmpty()) {
        person.logBlocker(blocker);
      }
      return true;
    }
    String siteBlocker = project.siteBlocker();
    if (!demolitionBlocker.isEmpty() && !demolitionBlocker.equals(siteBlocker)) {
      person.clearBlocker(demolitionBlocker);
    }
    demolitionBlocker = siteBlocker;
    if (!siteBlocker.isEmpty()) {
      person.logBlocker(siteBlocker);
      return true;
    }
    if (project.getProgress() != BuildProgress.PREPARING) {
      keepBuilderClearOfNextBlock(person, project);
      project.updateBuilding();
      return true;
    }
    // Clearing and levelling the ground is the builder's first phase, not a
    // separate job (docs/site-selection.md).
    String blocker = "We have no earth to level the ground for the new building.";
    if (!project.prepareStep(person.getVillage(), person)) {
      person.getVillage().logEvent(new NoResourceBookkeepingEvent(Items.DIRT, 1));
      person.logBlocker(blocker);
    } else {
      person.clearBlocker(blocker);
    }
    if (project.remainingPrepWork() == 0) {
      project.startBuilding();
    }
    return true;
  }

  @Override
  public void released(RealPerson person, BlockPos target) {
    StructureInProgress project = project(person);
    if (project != null) {
      project.stopBuilding();
    }
  }

  @Override
  public String describe() {
    return "where the new building is going";
  }

  @Override
  public String activity() {
    return "putting up the new building";
  }

  /** Half a second, as the goal this replaces used. */
  @Override
  public int actEveryTicks() {
    return 10;
  }

  /**
   * Not a fixed distance: a site is as big as what is being raised on it, so
   * the builder is there once inside the building's own radius.
   */
  @Override
  public double reachSqr(RealPerson person) {
    StructureInProgress project = project(person);
    if (project == null) {
      return 9.0D;
    }
    double radius = project.getBuilding().getRadius();
    return radius * radius * RADIUS_SLACK;
  }

  /**
   * The builder used to stand where the next block landed and get walled into its own
   * building -- a solid block placed in your head suffocates you (Aaron). Before the
   * next block goes down, if it would materialise where the builder is standing, step
   * them one block aside to a clear, standable spot (up onto the last course if boxed
   * in). Only for blocks that actually collide; a torch or carpet in your square is
   * harmless.
   */
  private void keepBuilderClearOfNextBlock(RealPerson person, StructureInProgress project) {
    BlockPos next = project.peekNextBlockPos();
    BlockPos feet = person.blockPosition();
    if (next == null || (!next.equals(feet) && !next.equals(feet.above()))) {
      return;
    }
    BlockState state = project.peekNextBlockState();
    if (state == null || state.getCollisionShape(person.level(), next).isEmpty()) {
      return;
    }
    for (Direction dir : Direction.Plane.HORIZONTAL) {
      BlockPos cand = feet.relative(dir);
      if (!cand.equals(next) && !cand.above().equals(next) && standable(person, cand)) {
        person.teleportTo(cand.getX() + 0.5D, cand.getY(), cand.getZ() + 0.5D);
        return;
      }
    }
    // Boxed in on all sides: step up onto the course just laid rather than into it.
    person.teleportTo(feet.getX() + 0.5D, feet.getY() + 1.0D, feet.getZ() + 0.5D);
  }

  /** A spot the builder can stand: body space clear, solid ground underfoot. */
  private boolean standable(RealPerson person, BlockPos at) {
    var level = person.level();
    return level.getBlockState(at).getCollisionShape(level, at).isEmpty()
        && level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty()
        && !level.getBlockState(at.below()).getCollisionShape(level, at.below()).isEmpty();
  }

  @Nullable
  private StructureInProgress project(RealPerson person) {
    Village village = person.getVillage();
    return village == null ? null : village.getCurrentProject();
  }

}
