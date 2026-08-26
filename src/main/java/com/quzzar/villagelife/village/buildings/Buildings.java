package com.quzzar.villagelife.village.buildings;

import java.util.Map;

import javax.annotation.Nullable;

/**
 * Registry of building definitions, populated from datapack JSON under
 * {@code data/<namespace>/villagelife/buildings/*.json} by {@link BuildingDefinitionLoader}.
 */
public class Buildings {

  /**
   * The founding set (docs/building-spec.md, "How a village starts"): the
   * village center plus its two companions, all placed free as one camp plat.
   * The center must be loaded for founding to happen at all; a missing
   * companion is skipped loudly so a datapack without it still founds a camp.
   */
  public static final String VILLAGE_CENTER_NAME = "village_center_plains_1";
  public static final String FOUNDING_MINE_NAME = "mine_plains_1";
  public static final String FOUNDING_STOREHOUSE_NAME = "storehouse_plains_1";

  private static volatile Map<String, BuildingInfo> registry = Map.of();

  /** Replaces the whole registry; called on datapack (re)load. */
  public static void reload(Map<String, BuildingInfo> newRegistry) {
    registry = Map.copyOf(newRegistry);
  }

  public static Map<String, BuildingInfo> allBuildings() {
    return registry;
  }

  @Nullable
  public static BuildingInfo getByName(String name) {
    return registry.get(name);
  }

}
