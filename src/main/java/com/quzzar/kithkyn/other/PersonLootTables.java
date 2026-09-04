package com.quzzar.kithkyn.other;

import java.util.function.Consumer;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.quzzar.kithkyn.Kithkyn;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSet;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

public class PersonLootTables {
    public static final BiMap<ResourceLocation, LootContextParamSet> REGISTRY = HashBiMap.create();
    public static final LootContextParamSet SLOT = register("slot", (builder) -> {
        builder.required(LootContextParams.THIS_ENTITY);
    });

    public static final ResourceKey<LootTable> GUARD_MAIN_HAND = key("entities/guard_main_hand");
    public static final ResourceKey<LootTable> GUARD_OFF_HAND = key("entities/guard_off_hand");
    public static final ResourceKey<LootTable> GUARD_HELMET = key("entities/guard_helmet");
    public static final ResourceKey<LootTable> GUARD_CHEST = key("entities/guard_chestplate");
    public static final ResourceKey<LootTable> GUARD_LEGGINGS = key("entities/guard_legs");
    public static final ResourceKey<LootTable> GUARD_FEET = key("entities/guard_feet");

    private static ResourceKey<LootTable> key(String path) {
        return ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, path));
    }

    public static LootContextParamSet register(String name, Consumer<LootContextParamSet.Builder> consumer) {
        LootContextParamSet.Builder builder = new LootContextParamSet.Builder();
        consumer.accept(builder);
        LootContextParamSet paramSet = builder.build();
        ResourceLocation resourcelocation = ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, name);
        LootContextParamSet previous = REGISTRY.put(resourcelocation, paramSet);
        if (previous != null) {
            throw new IllegalStateException("Loot table parameter set " + resourcelocation + " is already registered");
        } else {
            return paramSet;
        }
    }
}
