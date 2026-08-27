package com.quzzar.villagelife.networking;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.menu.MarketMenu;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C2S: the player picked a trade. The equivalent of vanilla's
 * ServerboundSelectTradePacket, and it exists for the same reason: staging a
 * payment moves real items, so the server has to be the one that does it.
 */
public record SelectTradePacket(int index) implements CustomPacketPayload {

  public static final CustomPacketPayload.Type<SelectTradePacket> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "select_trade"));

  public static final StreamCodec<ByteBuf, SelectTradePacket> STREAM_CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, SelectTradePacket::index,
      SelectTradePacket::new);

  @Override
  public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
    return TYPE;
  }

  public static void handle(SelectTradePacket msg, IPayloadContext context) {
    context.enqueueWork(() -> {
      if (context.player() instanceof ServerPlayer player
          && player.containerMenu instanceof MarketMenu menu) {
        menu.select(msg.index());
      }
    });
  }
}
