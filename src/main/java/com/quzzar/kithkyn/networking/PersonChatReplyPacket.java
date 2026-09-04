package com.quzzar.kithkyn.networking;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.client.gui.PersonChatScreen;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: the villager's reply to an earlier {@link PersonChatMessagePacket}.
 * {@code done} is the villager taking their leave: the screen shows the line
 * and closes itself a little later.
 */
public record PersonChatReplyPacket(int entityId, String text, boolean done) implements CustomPacketPayload {

  public static final CustomPacketPayload.Type<PersonChatReplyPacket> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, "person_chat_reply"));

  public static final StreamCodec<ByteBuf, PersonChatReplyPacket> STREAM_CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, PersonChatReplyPacket::entityId,
      ByteBufCodecs.stringUtf8(2048), PersonChatReplyPacket::text,
      ByteBufCodecs.BOOL, PersonChatReplyPacket::done,
      PersonChatReplyPacket::new);

  @Override
  public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
    return TYPE;
  }

  public static void handle(PersonChatReplyPacket msg, IPayloadContext context) {
    context.enqueueWork(() -> PersonChatScreen.onReply(msg.entityId(), msg.text(), msg.done()));
  }

}
