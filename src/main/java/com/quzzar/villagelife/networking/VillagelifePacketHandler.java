package com.quzzar.villagelife.networking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.client.gui.GuardInventoryScreen;
import com.quzzar.villagelife.client.gui.PersonScreenManager;
import com.quzzar.villagelife.entities.PersonContainer;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.VillageManager;

public class VillagelifePacketHandler {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(new ResourceLocation(Villagelife.MODID, "main"), () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);

    public static void registerPackets() {
        int id = 0;
        INSTANCE.registerMessage(id++, GuardOpenInventoryPacket.class, GuardOpenInventoryPacket::encode, GuardOpenInventoryPacket::decode, GuardOpenInventoryPacket::handle);
        INSTANCE.registerMessage(id++, GuardFollowPacket.class, GuardFollowPacket::encode, GuardFollowPacket::decode, GuardFollowPacket::handle);
        INSTANCE.registerMessage(id++, GuardSetPatrolPosPacket.class, GuardSetPatrolPosPacket::encode, GuardSetPatrolPosPacket::decode, GuardSetPatrolPosPacket::handle);
    }

    @SuppressWarnings("resource")
    @OnlyIn(Dist.CLIENT) // This should be removed when I find a better solution.
    public static void openGuardInventory(GuardOpenInventoryPacket packet) {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            Entity entity = player.level.getEntity(packet.getEntityId());
            if (entity instanceof RealPerson) {
                RealPerson person = (RealPerson) entity;
                LocalPlayer clientplayerentity = Minecraft.getInstance().player;
                PersonContainer container = new PersonContainer(packet.getId(), player.getInventory(), person.personEquipInv, person);
                clientplayerentity.containerMenu = container;

                Minecraft.getInstance().setScreen(new GuardInventoryScreen(container, player.getInventory(), person));
                //PersonScreenManager.openTempScreen(person);
                

            }
        }
    }
}
