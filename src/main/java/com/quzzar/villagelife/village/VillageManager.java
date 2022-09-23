package com.quzzar.villagelife.village;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.savedata.VillageManagerSaveData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;

public class VillageManager {

  private static ServerLevelAccessor levelAccessor;

  private static int time = 0;
  private static VillageManagerSaveData data;
  private static HashMap<String, Village> villages = null;

  public static void init(ServerLevel level) {

    Villagelife.LOGGER.debug("Initting VillageManager, level exists ? " + (levelAccessor != null));

    levelAccessor = level;
    data = level.getDataStorage().computeIfAbsent(VillageManagerSaveData::load, VillageManagerSaveData::new,
        Villagelife.MODID + "~villages");
    villages = getVillages();
  }

  private static HashMap<String, Village> getVillages() {
    if (villages == null) {
      Villagelife.LOGGER.debug("Villages data is null, getting saved data.");
      villages = data.getVillages();
      if (villages == null) {
        Villagelife.LOGGER.debug("Getting villages data still returned null, setting to new.");
        villages = new HashMap<String, Village>();
      }
      saveVillages();
    }
    return villages;
  }

  public static void saveVillages() {
    Villagelife.LOGGER.debug("Saving villages.");
    if (villages == null) {
      getVillages();
    }
    data.setVillages(villages);
    data.setDirty();
  }

  public static void registerVillage(ServerLevelAccessor levelAccess, BlockPos location) {
    if (levelAccessor == null) {
      levelAccessor = levelAccess;
    }

    Village village = new Village("Test Village");
    villages.put(village.getID(), village);
    village.initNew(location);

  }

  public static void callUpdate() { // Every 1 second
    if (villages == null) {
      return;
    }
    villages.forEach((k, v) -> {
      v.update();
    });

    if (time % 300 == 0) {// Every 300 seconds
      saveVillages();
    }
    time++;
  }

  public static Village getVillageFromUUID(String uuid) {
    return villages.get(uuid);
  }

  public static ServerLevelAccessor getLevelAccessor() {
    return levelAccessor;
  }

}

/*
 * try {
 * 
 * CompoundTag tag =
 * NbtIo.readCompressed(Villagelife.class.getClassLoader().getResourceAsStream(
 * "data/villagelife/structures/house.nbt"));
 * StructureTemplate template =
 * ((ServerLevel)entity.level).getStructureManager().readStructure(tag);
 * 
 * //StructureTemplate template =
 * serverLevel.getStructureManager().getOrCreate(new
 * ResourceLocation(Villagelife.MODID, "house"));
 * 
 * if (template != null) {
 * boolean success = placeStructureInWorld(template, serverLevel,
 * entity.blockPosition(),
 * entity.blockPosition(),
 * new
 * StructurePlaceSettings().setRotation(Rotation.NONE).setMirror(Mirror.NONE).
 * setIgnoreEntities(false),
 * serverLevel.random, 2);
 * 
 * Villagelife.LOGGER.info("Success: "+success);
 * }
 * 
 * } catch (IOException e) {
 * e.printStackTrace();
 * }
 */