package com.quzzar.villagelife.networking;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.Person;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record GuardFollowPacket(int entityId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GuardFollowPacket> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "guard_follow"));

    public static final StreamCodec<ByteBuf, GuardFollowPacket> STREAM_CODEC = ByteBufCodecs.VAR_INT
            .map(GuardFollowPacket::new, GuardFollowPacket::entityId);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GuardFollowPacket msg, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            Entity entity = player.level().getEntity(msg.entityId());
            if (entity instanceof Person guard) {
                guard.setFollowing(!guard.isFollowing());
                guard.setFollowLeaderUUID(player.getUUID());
                guard.playSound(SoundEvents.VILLAGER_YES, 1.0F, 1.0F);
            }
        });
    }
}
