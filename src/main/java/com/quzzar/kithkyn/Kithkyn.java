package com.quzzar.kithkyn;

import com.mojang.logging.LogUtils;
import com.quzzar.kithkyn.configuration.KithkynConfig;
import com.quzzar.kithkyn.entities.Person;
import com.quzzar.kithkyn.networking.KithkynNetworking;
import com.quzzar.kithkyn.other.KithkynItems;
import com.quzzar.kithkyn.other.KithkynMenus;

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

@Mod(Kithkyn.MODID)
public class Kithkyn {
    public static final String MODID = "kithkyn";

    public static final Logger LOGGER = LogUtils.getLogger();

    public Kithkyn(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::setup);
        modEventBus.addListener(this::addAttributes);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(KithkynNetworking::register);
        modEventBus.addListener(com.quzzar.kithkyn.village.VillageChunkLoader::onRegisterTicketControllers);

        modContainer.registerConfig(ModConfig.Type.COMMON, KithkynConfig.COMMON_SPEC);
        modContainer.registerConfig(ModConfig.Type.COMMON, KithkynConfig.ADVANCED_SPEC, "kithkyn-advanced.toml");

        PersonEntityType.ENTITIES.register(modEventBus);
        KithkynItems.ITEMS.register(modEventBus);
        KithkynMenus.MENUS.register(modEventBus);
        com.quzzar.kithkyn.entities.KithkynAttachments.ATTACHMENT_TYPES.register(modEventBus);
        com.quzzar.kithkyn.village.KithkynPoiTypes.POI_TYPES.register(modEventBus);
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("Kithkyn setup!");
    }

    private void addAttributes(final EntityAttributeCreationEvent event) {
        event.put(PersonEntityType.PERSON.get(), Person.createAttributes().build());
    }

    private void registerCapabilities(final RegisterCapabilitiesEvent event) {
        event.registerEntity(Capabilities.ItemHandler.ENTITY, PersonEntityType.PERSON.get(),
                (person, context) -> new InvWrapper(person.personEquipInv));
    }
}
