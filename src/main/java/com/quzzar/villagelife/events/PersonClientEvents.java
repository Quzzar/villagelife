package com.quzzar.villagelife.events;

import com.quzzar.villagelife.PersonEntityType;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.client.gui.GuardInventoryScreen;
import com.quzzar.villagelife.client.models.PersonArmorModel;
import com.quzzar.villagelife.client.models.PersonModel;
import com.quzzar.villagelife.client.renderer.PersonRenderer;
import com.quzzar.villagelife.other.VillagelifeMenus;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(value = Dist.CLIENT, modid = Villagelife.MODID, bus = EventBusSubscriber.Bus.MOD)
public class PersonClientEvents {

    public static ModelLayerLocation PERSON = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "person"), "person");
    public static ModelLayerLocation PERSON_SLIM = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "person_slim"), "person_slim");
    public static ModelLayerLocation PERSON_ARMOR_OUTER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "person_armor_outer"), "person_armor_outer");
    public static ModelLayerLocation PERSON_ARMOR_INNER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "person_armor_inner"), "person_armor_inner");

    @SubscribeEvent
    public static void layerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(PersonClientEvents.PERSON, PersonModel::createMesh);
        event.registerLayerDefinition(PersonClientEvents.PERSON_SLIM, PersonModel::createSlimMesh);
        event.registerLayerDefinition(PersonClientEvents.PERSON_ARMOR_OUTER, PersonArmorModel::createOuterArmorLayer);
        event.registerLayerDefinition(PersonClientEvents.PERSON_ARMOR_INNER, PersonArmorModel::createInnerArmorLayer);
    }

    @SubscribeEvent
    public static void entityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PersonEntityType.PERSON.get(), PersonRenderer::new);
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(VillagelifeMenus.PERSON_MENU.get(), GuardInventoryScreen::new);
        event.register(VillagelifeMenus.MARKET.get(),
                com.quzzar.villagelife.client.gui.PersonChatScreen::new);
    }
}
