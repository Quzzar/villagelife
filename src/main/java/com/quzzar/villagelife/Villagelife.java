package com.quzzar.villagelife;

import com.mojang.logging.LogUtils;
import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.networking.VillagelifeNetworking;
import com.quzzar.villagelife.other.VillagelifeItems;
import com.quzzar.villagelife.other.VillagelifeMenus;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.slf4j.Logger;

@Mod(Villagelife.MODID)
public class Villagelife {
    public static final String MODID = "villagelife";

    public static final Logger LOGGER = LogUtils.getLogger();

    public Villagelife(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::setup);
        modEventBus.addListener(this::addAttributes);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(VillagelifeNetworking::register);

        modContainer.registerConfig(ModConfig.Type.COMMON, VillagelifeConfig.COMMON_SPEC);

        PersonEntityType.ENTITIES.register(modEventBus);
        VillagelifeItems.ITEMS.register(modEventBus);
        VillagelifeMenus.MENUS.register(modEventBus);
        com.quzzar.villagelife.entities.VillagelifeAttachments.ATTACHMENT_TYPES.register(modEventBus);
        com.quzzar.villagelife.village.VillagelifePoiTypes.POI_TYPES.register(modEventBus);
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("Villagelife setup!");
    }

    private void addAttributes(final EntityAttributeCreationEvent event) {
        event.put(PersonEntityType.PERSON.get(), Person.createAttributes().build());
    }

    private void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerEntity(Capabilities.ItemHandler.ENTITY, PersonEntityType.PERSON.get(),
                (person, context) -> new InvWrapper(person.personEquipInv));
    }
}
