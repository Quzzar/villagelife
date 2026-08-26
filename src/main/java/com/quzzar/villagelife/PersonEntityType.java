package com.quzzar.villagelife;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class PersonEntityType {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Villagelife.MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<RealPerson>> PERSON = ENTITIES.register("person",
            () -> EntityType.Builder.of(RealPerson::new, MobCategory.MISC)
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.62F)
                    .setShouldReceiveVelocityUpdates(true)
                    .build(Villagelife.MODID + ":person"));
}
