package com.quzzar.villagelife;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.quzzar.villagelife.entities.RealPerson;

@Mod.EventBusSubscriber(modid = Villagelife.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class PersonEntityType {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITIES, Villagelife.MODID);
    public static final RegistryObject<EntityType<RealPerson>> PERSON = ENTITIES.register("person", () -> EntityType.Builder.of(RealPerson::new, MobCategory.MISC).sized(0.6F, 1.95F).setShouldReceiveVelocityUpdates(true).build(Villagelife.MODID + "person"));
}
