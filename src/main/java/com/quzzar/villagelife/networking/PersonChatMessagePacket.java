package com.quzzar.villagelife.networking;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.chat.PersonChatDispatcher;
import com.quzzar.villagelife.entities.RealPerson;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** C2S: the player said something to a villager. */
public record PersonChatMessagePacket(int entityId, String text) implements CustomPacketPayload {

  /** Generous bound; the UI stays well under it. */
  public static final int MAX_TEXT_LENGTH = 512;

  public static final CustomPacketPayload.Type<PersonChatMessagePacket> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "person_chat_message"));

  public static final StreamCodec<ByteBuf, PersonChatMessagePacket> STREAM_CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, PersonChatMessagePacket::entityId,
      ByteBufCodecs.stringUtf8(MAX_TEXT_LENGTH), PersonChatMessagePacket::text,
      PersonChatMessagePacket::new);

  @Override
  public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
    return TYPE;
  }

  public static void handle(PersonChatMessagePacket msg, IPayloadContext context) {
    context.enqueueWork(() -> {
      if (!(context.player() instanceof ServerPlayer player)) {
        return;
      }
      Entity entity = player.level().getEntity(msg.entityId());
      String text = msg.text().strip();
      if (entity instanceof RealPerson person && !text.isEmpty()
          && player.distanceTo(person) < 12.0F && person.isAlive()) {
        PersonChatDispatcher.handle(player, person, text);
      }
    });
  }

}
