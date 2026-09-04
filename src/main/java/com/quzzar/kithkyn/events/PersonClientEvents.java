package com.quzzar.kithkyn.events;

import com.quzzar.kithkyn.PersonEntityType;
import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.client.appearance.PersonAppearanceTextures;
import com.quzzar.kithkyn.client.gui.GuardInventoryScreen;
import com.quzzar.kithkyn.client.models.PersonArmorModel;
import com.quzzar.kithkyn.client.models.PersonModel;
import com.quzzar.kithkyn.client.renderer.PersonRenderer;
import com.quzzar.kithkyn.other.KithkynMenus;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

@EventBusSubscriber(value = Dist.CLIENT, modid = Kithkyn.MODID, bus = EventBusSubscriber.Bus.MOD)
public class PersonClientEvents {

    public static ModelLayerLocation PERSON = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, "person"), "person");
    public static ModelLayerLocation PERSON_SLIM = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, "person_slim"), "person_slim");
    public static ModelLayerLocation PERSON_ARMOR_OUTER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, "person_armor_outer"), "person_armor_outer");
    public static ModelLayerLocation PERSON_ARMOR_INNER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, "person_armor_inner"), "person_armor_inner");

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
        event.register(KithkynMenus.PERSON_MENU.get(), GuardInventoryScreen::new);
        event.register(KithkynMenus.MARKET.get(),
                com.quzzar.kithkyn.client.gui.PersonChatScreen::new);
    }

    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(PersonAppearanceTextures.INSTANCE);
    }
}
