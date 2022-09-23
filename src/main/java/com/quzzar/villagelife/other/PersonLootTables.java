package com.quzzar.villagelife.other;


import java.util.function.Consumer;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.quzzar.villagelife.Villagelife;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSet;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

public class PersonLootTables {
    public static final BiMap<ResourceLocation, LootContextParamSet> REGISTRY = HashBiMap.create();
    public static final LootContextParamSet SLOT = register("slot", (p_216252_0_) -> {
        p_216252_0_.required(LootContextParams.THIS_ENTITY);
    });
    
    public static final ResourceLocation GUARD_MAIN_HAND = new ResourceLocation(Villagelife.MODID, "entities/guard_main_hand");
    public static final ResourceLocation GUARD_OFF_HAND = new ResourceLocation(Villagelife.MODID, "entities/guard_off_hand");
    public static final ResourceLocation GUARD_HELMET = new ResourceLocation(Villagelife.MODID, "entities/guard_helmet");
    public static final ResourceLocation GUARD_CHEST = new ResourceLocation(Villagelife.MODID, "entities/guard_chestplate");
    public static final ResourceLocation GUARD_LEGGINGS = new ResourceLocation(Villagelife.MODID, "entities/guard_legs");
    public static final ResourceLocation GUARD_FEET = new ResourceLocation(Villagelife.MODID, "entities/guard_feet");
    
    public static LootContextParamSet register(String p_81429_, Consumer<LootContextParamSet.Builder> p_81430_) {
        LootContextParamSet.Builder lootcontextparamset$builder = new LootContextParamSet.Builder();
        p_81430_.accept(lootcontextparamset$builder);
        LootContextParamSet lootcontextparamset = lootcontextparamset$builder.build();
        ResourceLocation resourcelocation = new ResourceLocation(Villagelife.MODID + p_81429_);
        LootContextParamSet lootcontextparamset1 = REGISTRY.put(resourcelocation, lootcontextparamset);
        if (lootcontextparamset1 != null) {
           throw new IllegalStateException("Loot table parameter set " + resourcelocation + " is already registered");
        } else {
           return lootcontextparamset;
        }
     }
}