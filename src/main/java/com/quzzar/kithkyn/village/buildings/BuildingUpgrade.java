package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.village.Occupation;
import com.quzzar.kithkyn.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

/**
 * Improving a building the village already has (docs/building-spec.md, "How
 * upgrading works"). Higher levels may also be built fresh elsewhere; this is
 * the cheaper reuse path for a compatible building already standing.
 *
 * An upgrade is ordinary construction with the new template over the old. It
 * keeps the standing orientation, but the larger footprint may slide in either
 * horizontal direction as long as it fully contains the old footprint. That is
 * how a house can extend west when its east side is blocked. Two things have to
 * be true before one can start: some containing placement has to fit, and
 * whatever the old building was storing has to have somewhere else to go.
 *
 * The upgraded building keeps its identity. That is the whole reason a smith
 * who was a smith yesterday is still one tomorrow: assignments point at a
 * building by id, so replacing rather than re-founding it holds every worker in
 * place while the station lists reconcile around them.
 */
public final class BuildingUpgrade {

  /** The exact directional fit chosen for an upgrade around a standing building. */
  public record Placement(Building standing, BlockPos ground, Rotation rotation,
      BoundingBox bounds, SitePreparation.SiteCost cost, int centreShiftSqr) {
  }

  private BuildingUpgrade() {
  }

  /**
   * What a building actually costs to raise here, now. An upgrade pays the
   * positive increase from its predecessor's recipe. A fresh higher-level build
   * pays the base recipe plus every upgrade increase through its target.
   *
   * This is what makes upgrading a genuine good deal: extending a warehouse
   * costs the few materials the extension needs, not a second warehouse's worth,
   * so a village prefers the upgrade on plain cost rather than on any thumb on
   * the scale.
   */
  public static List<ItemStack> effectiveCost(Village village, BuildingInfo info) {
    ConstructionMode mode = standingSource(village, info) == null
        ? ConstructionMode.FRESH
        : ConstructionMode.UPGRADE;
    return effectiveCost(info, mode);
  }

  /** The stable price for an explicit construction mode. */
  public static List<ItemStack> effectiveCost(BuildingInfo info, ConstructionMode mode) {
    if (mode == ConstructionMode.FRESH) {
      return combinedCost(info);
    }
    BuildingInfo previous = Buildings.getByName(info.getUpgradesFrom());
    if (previous == null) {
      Kithkyn.LOGGER.warn("Cannot price upgrade '{}' because predecessor '{}' is not loaded",
          info.getName(), info.getUpgradesFrom());
      return info.getMaterialCost();
    }
    return incrementalCost(previous.getMaterialCost(), info.getMaterialCost());
  }

  /** Adds the base recipe to each positive recipe increase in the upgrade chain. */
  private static List<ItemStack> combinedCost(BuildingInfo target) {
    List<BuildingInfo> lineage = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    BuildingInfo step = target;
    while (step != null) {
      if (!visited.add(step.getName())) {
        Kithkyn.LOGGER.warn("Cannot fully price fresh '{}' because its upgrade chain contains a cycle",
            target.getName());
        break;
      }
      lineage.add(step);
      String previousName = step.getUpgradesFrom();
      if (previousName == null) {
        break;
      }
      step = Buildings.getByName(previousName);
      if (step == null) {
        Kithkyn.LOGGER.warn("Cannot fully price fresh '{}' because predecessor '{}' is not loaded",
            target.getName(), previousName);
      }
    }
    Collections.reverse(lineage);
    List<Map<Item, Integer>> steps = new ArrayList<>();
    for (int index = 0; index < lineage.size(); index++) {
      List<ItemStack> stepCost = index == 0
          ? lineage.get(index).getMaterialCost()
          : incrementalCost(lineage.get(index - 1).getMaterialCost(), lineage.get(index).getMaterialCost());
      Map<Item, Integer> cost = new LinkedHashMap<>();
      for (ItemStack item : stepCost) {
        cost.merge(item.getItem(), item.getCount(), Integer::sum);
      }
      steps.add(cost);
    }
    return CostSequence.combine(steps).entrySet().stream()
        .map(entry -> new ItemStack(entry.getKey(), entry.getValue()))
        .toList();
  }

  /** The positive material increase between two complete structure recipes. */
  static List<ItemStack> incrementalCost(List<ItemStack> previousCost, List<ItemStack> targetCost) {
    Map<Item, Integer> previous = new LinkedHashMap<>();
    for (ItemStack item : previousCost) {
      previous.merge(item.getItem(), item.getCount(), Integer::sum);
    }
    List<ItemStack> increase = new ArrayList<>();
    for (ItemStack item : targetCost) {
      int added = CostSequence.increase(Materials.counted(previous, item.getItem()), item.getCount());
      if (added > 0) {
        increase.add(new ItemStack(item.getItem(), added));
      }
    }
    return increase;
  }

  @Nullable
  public static Building standingSource(Village village, BuildingInfo info) {
    Placement placement = findPlacement(village, info);
    return placement == null ? null : placement.standing();
  }

  /**
   * Best containing placement among every compatible standing building. Ground
   * work wins first; equally cheap fits expand most evenly around the old one.
   */
  @Nullable
  public static Placement findPlacement(Village village, BuildingInfo info) {
    String from = info.getUpgradesFrom();
    if (from == null) {
      return null;
    }
    Placement best = null;
    boolean foundSource = false;
    for (Building building : village.getBuildings()) {
      if (!from.equals(building.getName())) {
        continue;
      }
      foundSource = true;
      Placement placement = placementAround(village, building, info);
      if (placement != null && (best == null
          || placement.cost().blocksMoved() < best.cost().blocksMoved()
          || placement.cost().blocksMoved() == best.cost().blocksMoved()
              && placement.centreShiftSqr() < best.centreShiftSqr())) {
        best = placement;
      }
    }
    if (best == null && foundSource) {
      Kithkyn.LOGGER.debug("Village '{}' cannot fit {} around any standing {}",
          village.getName(), info.getName(), from);
    }
    return best;
  }

  /**
   * Whether the larger footprint has room. Only the ring the new template
   * reaches beyond the old one is checked: the ground under the old building is
   * not an obstacle, it is what is being replaced.
   */
  public static boolean fits(Village village, Building from, BuildingInfo to) {
    return placementAround(village, from, to) != null;
  }

  /** Finds the least disruptive directional extension around one building. */
  @Nullable
  private static Placement placementAround(Village village, Building from, BuildingInfo to) {
    ServerLevelAccessor level = village.getLevel();
    if (level == null) {
      return null;
    }
    BoundingBox standing = footprintOf(level, from.getName(), from);
    BoundingBox wanted = footprintOf(level, to.getPath(), from.getRotation());
    if (standing == null || wanted == null || from.getInfo() == null) {
      return null;
    }
    BlockPos standingOrigin = BlockPos.of(from.getOriginLocation());
    BlockPos standingGround = standingOrigin.above(from.getInfo().getSink());
    TownLayout.Footprint standingWorld = new TownLayout.Footprint(
        standingOrigin.getX() + standing.minX(), standingOrigin.getZ() + standing.minZ(),
        standingOrigin.getX() + standing.maxX(), standingOrigin.getZ() + standing.maxZ());
    TownLayout.Footprint targetLocal = new TownLayout.Footprint(
        wanted.minX(), wanted.minZ(), wanted.maxX(), wanted.maxZ());

    Placement best = null;
    for (TownLayout.Origin origin : TownLayout.containingOrigins(standingWorld, targetLocal)) {
      // A mine owns a runtime-dug shaft below its template. Re-seating the
      // headframe would strand that shaft, so mines retain the authored origin
      // and use the fresh higher-level path when that one direction is blocked.
      if (Buildings.FOUNDING_MINE_CATEGORY.equals(from.getInfo().getCategory())
          && (origin.x() != standingOrigin.getX() || origin.z() != standingOrigin.getZ())) {
        continue;
      }
      TownLayout.Footprint targetWorld = targetLocal.moved(origin);
      if (touchesOtherClaim(village, targetWorld, standingWorld, LocationValidator.MIN_GAP)) {
        continue;
      }
      BoundingBox occupied = new BoundingBox(
          standingWorld.minX() - origin.x(), 0, standingWorld.minZ() - origin.z(),
          standingWorld.maxX() - origin.x(), 0, standingWorld.maxZ() - origin.z());
      BlockPos ground = new BlockPos(origin.x(), standingGround.getY(), origin.z());
      SitePreparation.SiteCost cost = SitePreparation.score(level, village, ground, wanted, occupied);
      if (cost.impossible()) {
        continue;
      }
      int centreShiftSqr = centreShiftSqr(standingWorld, targetWorld);
      Placement placement = new Placement(from, ground, from.getRotation(), wanted, cost,
          centreShiftSqr);
      if (cost.isFree()) {
        // containingOrigins is centred-first, and no preparation can beat zero
        return placement;
      }
      if (best == null || cost.blocksMoved() < best.cost().blocksMoved()
          || cost.blocksMoved() == best.cost().blocksMoved()
              && centreShiftSqr < best.centreShiftSqr()) {
        best = placement;
      }
    }
    return best;
  }

  /** Target padding may touch the source itself, but no other village footprint. */
  private static boolean touchesOtherClaim(Village village, TownLayout.Footprint target,
      TownLayout.Footprint source, int padding) {
    for (int x = target.minX() - padding; x <= target.maxX() + padding; x++) {
      for (int z = target.minZ() - padding; z <= target.maxZ() + padding; z++) {
        boolean sourceColumn = x >= source.minX() && x <= source.maxX()
            && z >= source.minZ() && z <= source.maxZ();
        if (!sourceColumn && village.hasClaimed(new BlockPos(x, 0, z))) {
          return true;
        }
      }
    }
    return false;
  }

  private static int centreShiftSqr(TownLayout.Footprint first, TownLayout.Footprint second) {
    int deltaX = first.minX() + first.maxX() - second.minX() - second.maxX();
    int deltaZ = first.minZ() + first.maxZ() - second.minZ() - second.maxZ();
    return deltaX * deltaX + deltaZ * deltaZ;
  }

  /**
   * Carries everything the old building was storing out to the rest of the
   * village before a block is touched. Returns false when it will not all fit,
   * which is a storage shortage rather than a reason to destroy someone's
   * items: the upgrade waits instead.
   */
  public static boolean clearStorage(Village village, Building from) {
    return StorageEvacuation.evacuate(village, List.of(from));
  }

  /** What the village would gain, in the model's own terms. */
  public static String describe(Building from, BuildingInfo to) {
    BuildingInfo current = from.getInfo();
    String name = to.displayLabel();
    String target = to.hasWellFormedId() ? "level " + to.getLevel() + " " + name : name;
    if (current == null) {
      return "an on-site " + target + " upgrade";
    }
    List<String> gains = new ArrayList<>();
    int beds = to.getBedLocations().size() - current.getBedLocations().size();
    if (beds > 0) {
      gains.add(beds + (beds == 1 ? " more bed" : " more beds"));
    }
    int stores = to.getContainerLocations().size() - current.getContainerLocations().size();
    if (stores > 0) {
      gains.add(stores == 1 ? "another store" : stores + " more stores");
    }
    for (Occupation occupation : to.getWorkLocations().values().stream().distinct().toList()) {
      int added = count(to, occupation) - count(current, occupation);
      if (added > 0) {
        gains.add("work for " + (added == 1 ? "another " : added + " more ")
            + occupation.name().toLowerCase());
      }
    }
    return gains.isEmpty()
        ? "an on-site " + target + " upgrade"
        : "an on-site " + target + " upgrade (" + String.join(", ", gains) + ")";
  }

  private static int count(BuildingInfo info, Occupation occupation) {
    int count = 0;
    for (Occupation station : info.getWorkLocations().values()) {
      if (station == occupation) {
        count++;
      }
    }
    return count;
  }

  /** The footprint a standing building actually occupies, in its own frame. */
  @Nullable
  public static BoundingBox footprintOf(ServerLevelAccessor level, Building building) {
    return footprintOf(level, building.getName(), building);
  }

  /** A definition's footprint in the frame of a standing building's origin. */
  @Nullable
  private static BoundingBox footprintOf(ServerLevelAccessor level, String definition, Building placed) {
    return footprintOf(level, definition, placed.getRotation());
  }

  /** A definition's footprint in a selected orientation. */
  @Nullable
  private static BoundingBox footprintOf(ServerLevelAccessor level, String definition, Rotation rotation) {
    var template = level.getLevel().getStructureManager().get(
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, definition));
    if (template.isEmpty()) {
      return null;
    }
    return template.get().getBoundingBox(
        new StructurePlaceSettings().setRotation(rotation), BlockPos.ZERO);
  }

}
