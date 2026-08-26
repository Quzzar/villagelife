package com.quzzar.villagelife.networking;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.client.gui.PersonChatScreen;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: opens the villager chat screen. The server composes the header lines
 * (name; occupation + village, or a future title) and sends the persisted
 * scrollback for this player, so reopening a chat resumes it (#45).
 */
public record OpenPersonChatPacket(int entityId, String headerName, String headerDetail,
    java.util.List<ExchangeLine> scrollback) implements CustomPacketPayload {

  /** One prior exchange, for the screen's seeded history. */
  public record ExchangeLine(String playerLine, String reply) {
    public static final StreamCodec<ByteBuf, ExchangeLine> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, ExchangeLine::playerLine,
        ByteBufCodecs.STRING_UTF8, ExchangeLine::reply,
        ExchangeLine::new);
  }

  public static final CustomPacketPayload.Type<OpenPersonChatPacket> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "open_person_chat"));

  public static final StreamCodec<ByteBuf, OpenPersonChatPacket> STREAM_CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, OpenPersonChatPacket::entityId,
      ByteBufCodecs.STRING_UTF8, OpenPersonChatPacket::headerName,
      ByteBufCodecs.STRING_UTF8, OpenPersonChatPacket::headerDetail,
      ExchangeLine.STREAM_CODEC.apply(ByteBufCodecs.list()), OpenPersonChatPacket::scrollback,
      OpenPersonChatPacket::new);

  @Override
  public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
    return TYPE;
  }

  public static void handle(OpenPersonChatPacket msg, IPayloadContext context) {
    // Lambda body defers loading the client-only screen class until a client
    // actually receives the packet.
    context.enqueueWork(() -> PersonChatScreen.open(msg.entityId(), msg.headerName(), msg.headerDetail(),
        msg.scrollback()));
  }

}
