package com.quzzar.kithkyn.networking;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.client.renderer.VillagerSpeechBubbles;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** S2C: briefly shows a nearby villager's spoken line above their head. */
public record VillagerSpeechPacket(int entityId, String text) implements CustomPacketPayload {

  public static final CustomPacketPayload.Type<VillagerSpeechPacket> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, "villager_speech"));

  public static final StreamCodec<ByteBuf, VillagerSpeechPacket> STREAM_CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, VillagerSpeechPacket::entityId,
      ByteBufCodecs.stringUtf8(2048), VillagerSpeechPacket::text,
      VillagerSpeechPacket::new);

  @Override
  public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
    return TYPE;
  }

  public static void handle(VillagerSpeechPacket msg, IPayloadContext context) {
    context.enqueueWork(() -> VillagerSpeechBubbles.show(msg.entityId(), msg.text()));
  }
}
