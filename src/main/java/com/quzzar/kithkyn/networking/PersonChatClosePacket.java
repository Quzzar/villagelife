package com.quzzar.kithkyn.networking;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.chat.PersonChatDispatcher;
import com.quzzar.kithkyn.entities.RealPerson;

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
      ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, "person_chat_close"));

  public static final StreamCodec<ByteBuf, PersonChatClosePacket> STREAM_CODEC = ByteBufCodecs.VAR_INT
      .map(PersonChatClosePacket::new, PersonChatClosePacket::entityId);

  @Override
  public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
    return TYPE;
  }

  public static void handle(PersonChatClosePacket msg, IPayloadContext context) {
    context.enqueueWork(() -> {
      Entity entity = context.player().level().getEntity(msg.entityId());
      if (entity instanceof RealPerson person) {
        PersonChatDispatcher.markClosed(msg.entityId(), context.player().getUUID());
        // Summarize the session now it has ended, so the memory is ready before
        // the next conversation opens rather than being generated on demand.
        PersonChatDispatcher.summarizeSession(person, context.player().getUUID(),
            context.player().getGameProfile().getName());
      }
    });
  }

}
