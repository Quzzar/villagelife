package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.buildings.BuildingUpgrade;
import com.quzzar.villagelife.village.buildings.Materials;
import com.quzzar.villagelife.village.buildings.StructureInProgress;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Carrying the recipe home before a build begins (docs/worker-loops.md): a CARRY
 * step, and the paying half of construction.
 *
 * A village used to pay for a building the instant it chose a site - materials
 * vanished from a chest and blocks appeared. Now the builder fetches the recipe
 * by hand: they walk to the village's chests, gather every item the build needs
 * into their pack, and only when the WHOLE recipe is in hand is it consumed and
 * the build committed ({@link StructureInProgress#commitFromBuilder}).
 *
 * The window between the first item lifted and the commit is real and
 * un-abusable: the materials are in the builder's pack, spent on nothing yet. A
 * builder killed there drops what they carried and the village is no poorer for
 * the attempt, its project still uncommitted. After the commit, construction
 * owes nothing and any builder finishes it - which is why {@link BuildStep}
 * yields entirely while this step runs.
 *
 * Emeralds are never a recipe item, so a build never draws on the treasury even
 * though the sweep would reach it; nothing here needs to special-case money.
 * What pays for each recipe line is {@link Materials}' rule, not the item's
 * name: a log cost is met by any log the village has.
 */
public final class GatherStep implements BlockWorkStep {

  /** Dead container positions to step past before giving up on a source. */
  private static final int MAX_STALE_CHESTS = 12;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    Village village = person.getVillage();
    if (village == null) {
      return null;
    }
    StructureInProgress project = village.getCurrentProject();
    if (project == null || !project.isGathering()) {
      return null; // not this project's phase; BuildStep or nobody owns it
    }
    // The whole recipe is in the pack: go to the site and commit it.
    if (!stillNeeds(person, project)) {
      return BlockPos.of(project.getBuilding().getCenterLocation());
    }
    // Otherwise fetch the next chest that holds something still wanted. None
    // found leaves the step dormant, and the village's abandon path reclaims a
    // project that can never gather its recipe.
    return chestWithNeed(person, village, project);
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    Village village = person.getVillage();
    if (village == null) {
      return false;
    }
    StructureInProgress project = village.getCurrentProject();
    if (project == null || !project.isGathering()) {
      return false; // committed or abandoned out from under us
    }
    if (person.level().getBlockEntity(target) instanceof Container chest) {
      pullNeeded(person, chest, project);
      return false; // re-select: more chests, or the site to commit
    }
    // Not a container: this is the build site, and the pack should be full.
    Villagelife.LOGGER.debug("[resource-flow] {} (BUILDER) delivered a full recipe and is raising the {}",
        person.getName().getString(), project.getBuilding().getName());
    project.commitFromBuilder(person.personMainInv, recipe(person, project));
    return false;
  }

  @Override
  public String describe() {
    return "gathering to build";
  }

  @Override
  public double reachSqr(RealPerson person) {
    return 6.0D;
  }

  /** The recipe still owed to raise this project: delta for an upgrade, whole otherwise. */
  private List<ItemStack> recipe(RealPerson person, StructureInProgress project) {
    return BuildingUpgrade.effectiveCost(person.getVillage(), project.getBuilding().getInfo());
  }

  /** Whether the pack is still short of any recipe item. */
  private boolean stillNeeds(RealPerson person, StructureInProgress project) {
    Container pack = person.personMainInv;
    for (ItemStack cost : recipe(person, project)) {
      if (Materials.held(pack, cost.getItem()) < cost.getCount()) {
        return true;
      }
    }
    return false;
  }

  /** Lift what this chest can offer toward the still-missing part of the recipe. */
  private void pullNeeded(RealPerson person, Container chest, StructureInProgress project) {
    Container pack = person.personMainInv;
    int pulledTotal = 0;
    for (ItemStack cost : recipe(person, project)) {
      int need = cost.getCount() - Materials.held(pack, cost.getItem());
      if (need <= 0) {
        continue;
      }
      List<ItemStack> pulled = Materials.take(chest, cost.getItem(), need);
      if (!pulled.isEmpty()) {
        Utils.insertItems(pack, pulled, person);
        pulledTotal += pulled.stream().mapToInt(ItemStack::getCount).sum();
      }
    }
    if (pulledTotal > 0) {
      Villagelife.LOGGER.debug("[resource-flow] {} (BUILDER) pulled {} material(s) for the {}",
          person.getName().getString(), pulledTotal, project.getBuilding().getName());
    }
  }

  /** The nearest village container holding something the recipe still wants. */
  @Nullable
  private BlockPos chestWithNeed(RealPerson person, Village village, StructureInProgress project) {
    if (!(person.level() instanceof ServerLevel level)) {
      return null;
    }
    List<BlockPos> skip = new ArrayList<>();
    for (int attempt = 0; attempt < MAX_STALE_CHESTS; attempt++) {
      BlockPos found = village.getNearestContainer(person.blockPosition(), skip);
      if (found.equals(BlockPos.ZERO)) {
        return null;
      }
      if (level.getBlockEntity(found) instanceof Container container
          && holdsSomethingNeeded(person, container, project)) {
        return found;
      }
      skip.add(found); // empty of what we need, or gone
    }
    return null;
  }

  private boolean holdsSomethingNeeded(RealPerson person, Container chest, StructureInProgress project) {
    Container pack = person.personMainInv;
    for (ItemStack cost : recipe(person, project)) {
      int need = cost.getCount() - Materials.held(pack, cost.getItem());
      if (need > 0 && Materials.held(chest, cost.getItem()) > 0) {
        return true;
      }
    }
    return false;
  }
}
