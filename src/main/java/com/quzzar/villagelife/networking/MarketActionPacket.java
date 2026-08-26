package com.quzzar.villagelife.networking;

import java.util.Optional;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.economy.ItemValues;
import com.quzzar.villagelife.economy.Trade;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C2S: the player asked the market for its current offers, or to trade.
 * {@code count} of zero means "just refresh"; the server always answers with
 * {@link MarketOffersPacket}, so the screen never has to guess what happened.
 */
public record MarketActionPacket(int entityId, boolean playerBuys, String itemId, int count)
    implements CustomPacketPayload {

  public static final CustomPacketPayload.Type<MarketActionPacket> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "market_action"));

  public static final StreamCodec<ByteBuf, MarketActionPacket> STREAM_CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, MarketActionPacket::entityId,
      ByteBufCodecs.BOOL, MarketActionPacket::playerBuys,
      ByteBufCodecs.stringUtf8(256), MarketActionPacket::itemId,
      ByteBufCodecs.VAR_INT, MarketActionPacket::count,
      MarketActionPacket::new);

  /** A refresh carries no trade. */
  public static MarketActionPacket refresh(int entityId) {
    return new MarketActionPacket(entityId, false, "", 0);
  }

  @Override
  public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
    return TYPE;
  }

  public static void handle(MarketActionPacket msg, IPayloadContext context) {
    context.enqueueWork(() -> {
      if (!(context.player() instanceof ServerPlayer player)
          || !(player.level() instanceof ServerLevel level)) {
        return;
      }
      Entity entity = level.getEntity(msg.entityId());
      // Same reach rule as conversation: you trade with someone in front of you.
      if (!(entity instanceof RealPerson merchant) || !merchant.isAlive()
          || player.distanceTo(merchant) >= 12.0F) {
        return;
      }
      Village village = merchant.getVillage();
      if (village == null) {
        return;
      }

      String message = "";
      if (msg.count() > 0) {
        Optional<Item> item = ItemValues.item(msg.itemId());
        if (item.isPresent()) {
          Trade.Result result = msg.playerBuys()
              ? Trade.villageSells(village, level, player, item.get(), msg.count())
              : Trade.villageBuys(village, level, player, item.get(), msg.count());
          message = result.message();
        }
      }
      MarketOffersPacket.sendTo(player, merchant, village, level, message);
    });
  }
}
