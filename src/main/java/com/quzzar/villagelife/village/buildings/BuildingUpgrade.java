package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.village.Occupation;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

/**
 * Improving a building the village already has (docs/building-spec.md, "How
 * upgrading works"). A level above 1 is only ever reached this way: nothing is
 * built fresh at level 2.
 *
 * An upgrade is ordinary construction with the new template over the old, on
 * the same ground and in the same orientation, so you watch it happen and the
 * building is unusable while it does. Two things have to be true before one can
 * start — the larger footprint has to fit, and whatever the old building was
 * storing has to have somewhere else to go — and this class answers both.
 *
 * The upgraded building keeps its identity. That is the whole reason a smith
 * who was a smith yesterday is still one tomorrow: assignments point at a
 * building by id, so replacing rather than re-founding it holds every worker in
 * place while the station lists reconcile around them.
 */
public final class BuildingUpgrade {

  private BuildingUpgrade() {
  }

  /**
   * The building this definition would replace: the best-placed instance the
   * village has of the level below. Prefers one the larger footprint actually
   * fits around, so a village with two farms upgrades the one with room.
   */
  @Nullable
  /**
   * What a building actually costs to raise HERE, now. A fresh build costs its
   * whole recipe; an upgrade costs only what the new tier ADDS over the one it
   * replaces, because the standing structure is reused rather than rebuilt.
   *
   * This is what makes upgrading a genuine good deal: extending a warehouse
   * costs the few materials the extension needs, not a second warehouse's worth,
   * so a village prefers the upgrade on plain cost rather than on any thumb on
   * the scale.
   */
  public static List<ItemStack> effectiveCost(Village village, BuildingInfo info) {
    Building standing = standingSource(village, info);
    if (standing == null || standing.getInfo() == null) {
      return info.getMaterialCost();
    }
    java.util.Map<net.minecraft.world.item.Item, Integer> already = new java.util.HashMap<>();
    for (ItemStack held : standing.getInfo().getMaterialCost()) {
      already.merge(held.getItem(), held.getCount(), Integer::sum);
    }
    List<ItemStack> delta = new ArrayList<>();
    for (ItemStack cost : info.getMaterialCost()) {
      int extra = cost.getCount() - Materials.counted(already, cost.getItem());
      if (extra > 0) {
        delta.add(new ItemStack(cost.getItem(), extra));
      }
    }
    return delta;
  }

  public static Building standingSource(Village village, BuildingInfo info) {
    String from = info.getUpgradesFrom();
    if (from == null) {
      return null;
    }
    Building cramped = null;
    for (Building building : village.getBuildings()) {
      if (!from.equals(building.getName())) {
        continue;
      }
      if (fits(village, building, info)) {
        return building;
      }
      cramped = building;
    }
    if (cramped != null) {
      Villagelife.LOGGER.debug("Village '{}' cannot fit {} around its {}",
          village.getName(), info.getName(), cramped.getName());
    }
    return null;
  }

  /**
   * Whether the larger footprint has room. Only the ring the new template
   * reaches beyond the old one is checked: the ground under the old building is
   * not an obstacle, it is what is being replaced.
   */
  public static boolean fits(Village village, Building from, BuildingInfo to) {
    ServerLevelAccessor level = village.getLevel();
    if (level == null) {
      return false;
    }
    BoundingBox standing = footprintOf(level, from.getName(), from);
    BoundingBox wanted = footprintOf(level, to.getName(), from);
    if (standing == null || wanted == null) {
      return false;
    }
    int sink = from.getInfo() == null ? 0 : from.getInfo().getSink();
    SitePreparation.SiteCost cost = SitePreparation.score(level, village,
        BlockPos.of(from.getOriginLocation()).above(sink), wanted, standing);
    return !cost.impossible();
  }

  /**
   * Carries everything the old building was storing out to the rest of the
   * village before a block is touched. Returns false when it will not all fit,
   * which is a storage shortage rather than a reason to destroy someone's
   * items: the upgrade waits instead.
   */
  public static boolean clearStorage(Village village, Building from) {
    ServerLevelAccessor level = village.getLevel();
    if (level == null) {
      return false;
    }
    BuildingInfo info = from.getInfo();
    if (info == null) {
      return true;
    }
    List<BlockPos> sources = new ArrayList<>();
    for (long offset : info.getContainerLocations()) {
      sources.add(BlockPos.of(from.getOriginLocation())
          .offset(BlockPos.of(offset).rotate(from.getRotation())));
    }
    // The residents' own chest goes out with the rest: the rebuild replaces its
    // block, and the alternative is their things on the floor of a building
    // site. They come back as village stores, not as theirs (docs/building-spec.md).
    sources.addAll(com.quzzar.villagelife.village.PersonalChest.chests(from));
    for (BlockPos source : sources) {
      if (!(level.getBlockEntity(source) instanceof Container container)) {
        continue;
      }
      for (int slot = 0; slot < container.getContainerSize(); slot++) {
        ItemStack stack = container.getItem(slot);
        if (stack.isEmpty()) {
          continue;
        }
        ItemStack leftover = village.storeAwayFrom(stack.copy(), sources);
        if (!leftover.isEmpty()) {
          Villagelife.LOGGER.debug("Village '{}' cannot empty its {} for an upgrade: nowhere to put {}",
              village.getName(), from.getName(), stack);
          return false;
        }
        container.setItem(slot, ItemStack.EMPTY);
      }
    }
    return true;
  }

  /** What the village would gain, in the model's own terms. */
  public static String describe(Building from, BuildingInfo to) {
    BuildingInfo current = from.getInfo();
    String name = to.displayLabel();
    if (current == null) {
      return "a better " + name;
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
        ? "a better " + name
        : "a bigger " + name + " (" + String.join(", ", gains) + ")";
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
    var template = level.getLevel().getStructureManager().get(
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, definition));
    if (template.isEmpty()) {
      return null;
    }
    return template.get().getBoundingBox(
        new StructurePlaceSettings().setRotation(placed.getRotation()), BlockPos.ZERO);
  }

}
