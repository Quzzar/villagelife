package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.buildings.ConstructionQuote;
import com.quzzar.villagelife.village.buildings.Materials;
import com.quzzar.villagelife.village.buildings.StructureInProgress;

import net.minecraft.core.BlockPos;
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
 * the build committed ({@link StructureInProgress#commitFromBuilder}). The chest
 * search and the pack filling are {@link PackLogistics}' shared mechanics; this
 * step only decides what the recipe is and when it is complete.
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
    List<ItemStack> recipe = recipe(person, project);
    // The whole recipe is in the pack: go to the site and commit it.
    if (!PackLogistics.packShort(person, recipe)) {
      return BlockPos.of(project.getBuilding().getCenterLocation());
    }
    // Otherwise fetch the next chest that holds something still wanted. None
    // found leaves the step dormant, and the village's abandon path reclaims a
    // project that can never gather its recipe.
    return PackLogistics.chestHolding(person, village, recipe);
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
    Container chest = PackLogistics.containerAt(person, target);
    if (chest != null) {
      PackLogistics.pullWanted(person, chest, recipe(person, project), "BUILDER");
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
  public String activity() {
    return "gathering materials for the build";
  }

  @Override
  public double reachSqr(RealPerson person) {
    return 6.0D;
  }

  /** The recipe still owed to raise this project: delta for an upgrade, whole otherwise. */
  private List<ItemStack> recipe(RealPerson person, StructureInProgress project) {
    return ConstructionQuote.requiredFor(person.getVillage(), project.getBuilding().getInfo());
  }
}
