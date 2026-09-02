package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;

import net.minecraft.world.item.ItemStack;

/**
 * Registry of building definitions, populated from datapack JSON under
 * {@code data/<namespace>/villagelife/buildings/*.json} by {@link BuildingDefinitionLoader}.
 *
 * Definitions come in regional variants, {@code <category>_<style>_<level>}, and
 * a village builds in one style for life ({@link VillageStyle}). Everything that
 * asks "what can this village build" goes through {@link #resolve} or
 * {@link #catalogue}, which hand back that style's variant and fall back to
 * plains where a category has no other, so no caller ever sees five look-alike
 * lodges.
 */
public class Buildings {

  /**
   * The founding set (docs/building-spec.md, "How a village starts"): the
   * village center plus its two companions, all placed free as one camp plat,
   * each in the founding village's style. The center must be loaded for
   * founding to happen at all; a missing companion is skipped loudly so a
   * datapack without it still founds a camp.
   */
  public static final String VILLAGE_CENTER_CATEGORY = "village_center";
  public static final String FOUNDING_MINE_CATEGORY = "mine";
  public static final String FOUNDING_STOREHOUSE_CATEGORY = "storehouse";

  private static volatile Map<String, BuildingInfo> registry = Map.of();

  /** Replaces the whole registry; called on datapack (re)load. */
  public static void reload(Map<String, BuildingInfo> newRegistry) {
    registry = Map.copyOf(newRegistry);
    warnOnDivergentRecipes();
  }

  public static Map<String, BuildingInfo> allBuildings() {
    return registry;
  }

  @Nullable
  public static BuildingInfo getByName(String name) {
    return registry.get(name);
  }

  /**
   * The variant of a category and level a village of this style builds: the
   * style's own when the datapack has one, the plains variant otherwise. Null
   * when neither exists.
   */
  @Nullable
  public static BuildingInfo resolve(String category, int level, VillageStyle style) {
    BuildingInfo own = registry.get(category + "_" + style.id() + "_" + level);
    if (own != null) {
      return own;
    }
    return registry.get(category + "_" + VillageStyle.PLAINS.id() + "_" + level);
  }

  /**
   * The catalogue as one style sees it: a single level-1 building per category
   * (that style's variant, or plains), plus every upgrade level. Upgrades stay
   * in full because an upgrade follows whatever variant is already standing,
   * and the upgrade check filters them by that. Definitions whose id does not
   * parse pass through untouched.
   */
  public static List<BuildingInfo> catalogue(VillageStyle style) {
    List<BuildingInfo> out = new ArrayList<>();
    for (BuildingInfo info : registry.values()) {
      if (!info.hasWellFormedId() || info.getLevel() > 1
          || info == resolve(info.getCategory(), 1, style)) {
        out.add(info);
      }
    }
    return out;
  }

  /**
   * Recipes do not vary by style (docs/building-spec.md): every variant of a
   * category and level costs what its plains variant costs. A datapack that
   * breaks that is loaded anyway, but says so, because the planner and the
   * builder both assume the price of a building is the price of its category.
   */
  private static void warnOnDivergentRecipes() {
    Map<String, BuildingInfo> firstSeen = new HashMap<>();
    for (BuildingInfo info : registry.values()) {
      if (!info.hasWellFormedId()) {
        continue;
      }
      String key = info.getCategory() + "_" + info.getLevel();
      BuildingInfo other = firstSeen.putIfAbsent(key, info);
      if (other != null && !sameRecipe(other.getMaterialCost(), info.getMaterialCost())) {
        Villagelife.LOGGER.warn("Building variants '{}' and '{}' are priced differently; variants of one building should share a recipe",
            other.getName(), info.getName());
      }
    }
  }

  private static boolean sameRecipe(List<ItemStack> a, List<ItemStack> b) {
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      if (a.get(i).getItem() != b.get(i).getItem() || a.get(i).getCount() != b.get(i).getCount()) {
        return false;
      }
    }
    return true;
  }

}
