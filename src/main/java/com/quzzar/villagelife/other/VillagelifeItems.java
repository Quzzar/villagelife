package com.quzzar.villagelife.other;

import com.google.common.base.Predicate;
import com.quzzar.villagelife.PersonEntityType;
import com.quzzar.villagelife.Villagelife;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@EventBusSubscriber(modid = Villagelife.MODID, bus = EventBusSubscriber.Bus.MOD)
public class VillagelifeItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, Villagelife.MODID);

    public static final DeferredHolder<Item, DeferredSpawnEggItem> PERSON_SPAWN_EGG = ITEMS.register("person_spawn_egg",
            () -> new DeferredSpawnEggItem(PersonEntityType.PERSON, 5651507, 9804699, new Item.Properties()));

    @SubscribeEvent
    public static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(PERSON_SPAWN_EGG.get());
        }
    }

    public static InteractionHand getHandWith(LivingEntity livingEntity, Predicate<Item> itemPredicate) {
        return itemPredicate.test(livingEntity.getMainHandItem().getItem()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }
}
