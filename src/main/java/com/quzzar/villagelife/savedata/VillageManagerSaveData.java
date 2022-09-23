package com.quzzar.villagelife.savedata;

import java.util.HashMap;
import java.util.Map;

import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.village.Village;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

public class VillageManagerSaveData extends SavedData {

    public static VillageManagerSaveData load(CompoundTag tag){
        VillageManagerSaveData data = new VillageManagerSaveData();
        data.setVillages(tag.getByteArray("villages"));
        return data;
    }

    ///

    private byte[] villages;

    public VillageManagerSaveData() {
        super();
        villages = new byte[0];
    }
    
    public void setVillages(byte[] array){
        this.villages = array;
    }
    public void setVillages(Map<String, Village> villages){
        this.villages = Utils.objectToByteArray(villages);
    }
    public HashMap<String, Village> getVillages(){
        return Utils.byteArrayToGeneric(this.villages);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putByteArray("villages", this.villages);
        return tag;
    }
    
}
