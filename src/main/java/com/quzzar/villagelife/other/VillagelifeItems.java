package com.quzzar.villagelife.other;

import com.google.common.base.Predicate;
import com.quzzar.villagelife.PersonEntityType;
import com.quzzar.villagelife.Villagelife;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = Villagelife.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class VillagelifeItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Villagelife.MODID);

    public static final RegistryObject<ForgeSpawnEggItem> PERSON_SPAWN_EGG = ITEMS.register("person_spawn_egg", () -> new ForgeSpawnEggItem(PersonEntityType.PERSON, 5651507, 9804699, new Item.Properties().tab(CreativeModeTab.TAB_MISC)));

    public static InteractionHand getHandWith(LivingEntity livingEntity, Predicate<Item> itemPredicate) {
        return itemPredicate.test(livingEntity.getMainHandItem().getItem()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }
}