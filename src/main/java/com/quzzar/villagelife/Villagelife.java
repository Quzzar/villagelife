package com.quzzar.villagelife;

import java.util.Map;

import com.mojang.logging.LogUtils;
import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.networking.VillagelifePacketHandler;
import com.quzzar.villagelife.other.VillagelifeItems;
import com.quzzar.villagelife.village.VillageManager;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(Villagelife.MODID)
public class Villagelife {
    public static final String MODID = "villagelife";

    public static final Logger LOGGER = LogUtils.getLogger();
    

    public Villagelife() {

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::addAttributes);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::doClientStuff);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, VillagelifeConfig.COMMON_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, VillagelifeConfig.CLIENT_SPEC);
        MinecraftForge.EVENT_BUS.register(this);
        PersonEntityType.ENTITIES.register(FMLJavaModLoadingContext.get().getModEventBus());
        VillagelifeItems.ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        VillagelifePacketHandler.registerPackets();


    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("Villagelife setup!");
    }

    private void addAttributes(final EntityAttributeCreationEvent event) {
        event.put(PersonEntityType.PERSON.get(), Person.createAttributes().build());
    }

    private void doClientStuff(final FMLClientSetupEvent event) {
    }



}
