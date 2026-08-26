package com.quzzar.villagelife.networking;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.chat.PersonChatDispatcher;
import com.quzzar.villagelife.entities.RealPerson;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** C2S: the player closed the chat screen on a villager. */
public record PersonChatClosePacket(int entityId) implements CustomPacketPayload {

  public static final CustomPacketPayload.Type<PersonChatClosePacket> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "person_chat_close"));

  public static final StreamCodec<ByteBuf, PersonChatClosePacket> STREAM_CODEC = ByteBufCodecs.VAR_INT
      .map(PersonChatClosePacket::new, PersonChatClosePacket::entityId);

  @Override
  public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
    return TYPE;
  }

  public static void handle(PersonChatClosePacket msg, IPayloadContext context) {
    context.enqueueWork(() -> {
      Entity entity = context.player().level().getEntity(msg.entityId());
      if (entity instanceof RealPerson) {
        PersonChatDispatcher.markClosed(msg.entityId(), context.player().getUUID());
      }
    });
  }

}
