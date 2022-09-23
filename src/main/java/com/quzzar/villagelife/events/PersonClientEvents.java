package com.quzzar.villagelife.events;

import com.quzzar.villagelife.PersonEntityType;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.client.models.PersonArmorModel;
import com.quzzar.villagelife.client.models.PersonModel;
import com.quzzar.villagelife.client.renderer.PersonRenderer;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class PersonClientEvents {

    public static ModelLayerLocation PERSON = new ModelLayerLocation(
            new ResourceLocation(Villagelife.MODID + "guard_steve"), "guard_steve");
    public static ModelLayerLocation PERSON_ARMOR_OUTER = new ModelLayerLocation(
            new ResourceLocation(Villagelife.MODID + "guard_armor_outer"), "guard_armor_outer");
    public static ModelLayerLocation PERSON_ARMOR_INNER = new ModelLayerLocation(
            new ResourceLocation(Villagelife.MODID + "guard_armor_inner"), "guard_armor_inner");

    @SubscribeEvent
    public static void layerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(PersonClientEvents.PERSON, PersonModel::createMesh);
        event.registerLayerDefinition(PersonClientEvents.PERSON_ARMOR_OUTER, PersonArmorModel::createOuterArmorLayer);
        event.registerLayerDefinition(PersonClientEvents.PERSON_ARMOR_INNER, PersonArmorModel::createInnerArmorLayer);
    }

    @SubscribeEvent
    public static void entityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PersonEntityType.PERSON.get(), PersonRenderer::new);
    }
}
