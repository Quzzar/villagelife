package com.quzzar.villagelife.village;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.savedata.VillageManagerSaveData;

import net.minecraft.server.level.ServerLevel;

/**
 * Access point for the server-wide village registry. Villages live in the
 * overworld's save data regardless of which level asks, so people can find
 * their village from any dimension.
 */
public final class VillageManager {

  private VillageManager() {
  }

  public static VillageManagerSaveData get(ServerLevel level) {
    ServerLevel overworld = level.getServer().overworld();
    VillageManagerSaveData data = overworld.getDataStorage()
        .computeIfAbsent(VillageManagerSaveData.FACTORY, Villagelife.MODID + "~villages");
    data.attach(overworld);
    return data;
  }

}
