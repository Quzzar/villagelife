package com.quzzar.villagelife.networking;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.Person;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record GuardSetPatrolPosPacket(int entityId, boolean pressed) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GuardSetPatrolPosPacket> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "guard_set_patrol_pos"));

    public static final StreamCodec<ByteBuf, GuardSetPatrolPosPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, GuardSetPatrolPosPacket::entityId,
            ByteBufCodecs.BOOL, GuardSetPatrolPosPacket::pressed,
            GuardSetPatrolPosPacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GuardSetPatrolPosPacket msg, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            Entity entity = player.level().getEntity(msg.entityId());
            if (entity instanceof Person guard) {
                BlockPos pos = msg.pressed() ? null : guard.blockPosition();
                guard.setPatrolPos(pos);
                guard.setPatrolling(!msg.pressed());
            }
        });
    }
}
