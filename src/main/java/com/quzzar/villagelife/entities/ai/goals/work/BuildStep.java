package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.Optional;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.bookkeeping.NoResourceBookkeepingEvent;
import com.quzzar.villagelife.village.buildings.BuildProgress;
import com.quzzar.villagelife.village.buildings.StructureInProgress;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

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
    if (project.getProgress() != BuildProgress.PREPARING) {
      project.updateBuilding();
      return true;
    }
    // Clearing and levelling the ground is the builder's first phase, not a
    // separate job (docs/site-selection.md).
    if (!project.prepareStep(person.getVillage(), person)) {
      person.getVillage().logEvent(new NoResourceBookkeepingEvent(Items.DIRT, 1));
      person.logIssue("We have no earth to level the ground for the new building.", Optional.empty());
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

  @Nullable
  private StructureInProgress project(RealPerson person) {
    Village village = person.getVillage();
    return village == null ? null : village.getCurrentProject();
  }

}
