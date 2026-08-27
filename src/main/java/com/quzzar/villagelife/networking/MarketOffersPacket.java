package com.quzzar.villagelife.networking;

import java.util.List;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.economy.MarketOffers;
import com.quzzar.villagelife.economy.Treasury;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: the state of a village's stall - what it sells, what it wants, what it
 * can pay with, and the result of whatever the player just did. One packet
 * carries the whole screen, so the client never assembles a half-updated view.
 */
public record MarketOffersPacket(int entityId, String villageName, boolean blocked,
    List<Row> selling, List<Row> wanted, String message) implements CustomPacketPayload {

  /** One stall line as a trade: this many of the item against this many emeralds. */
  public record Row(String itemId, int itemCount, int emeralds) {
    public static final StreamCodec<ByteBuf, Row> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.stringUtf8(256), Row::itemId,
        ByteBufCodecs.VAR_INT, Row::itemCount,
        ByteBufCodecs.VAR_INT, Row::emeralds,
        Row::new);
  }

  public static final CustomPacketPayload.Type<MarketOffersPacket> TYPE = new CustomPacketPayload.Type<>(
      ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "market_offers"));

  public static final StreamCodec<ByteBuf, MarketOffersPacket> STREAM_CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, MarketOffersPacket::entityId,
      ByteBufCodecs.stringUtf8(128), MarketOffersPacket::villageName,
      ByteBufCodecs.BOOL, MarketOffersPacket::blocked,
      Row.STREAM_CODEC.apply(ByteBufCodecs.list()), MarketOffersPacket::selling,
      Row.STREAM_CODEC.apply(ByteBufCodecs.list()), MarketOffersPacket::wanted,
      ByteBufCodecs.stringUtf8(256), MarketOffersPacket::message,
      MarketOffersPacket::new);

  @Override
  public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
    return TYPE;
  }

  /** Builds the current stall state and sends it. Server thread. */
  public static void sendTo(ServerPlayer player, RealPerson merchant, Village village, ServerLevel level,
      String message) {
    // Two different reasons a stall may be shut: the village cannot trade at
    // all, or it will not trade with YOU. The screen has to know about both, or
    // it shows an open market that then refuses the trade.
    com.quzzar.villagelife.wrongdoing.Standing.Tier tier =
        com.quzzar.villagelife.wrongdoing.Standing.tierOf(village, level, player.getUUID());
    String standing = com.quzzar.villagelife.wrongdoing.Standing.refusalReason(tier);
    String blocker = Treasury.tradeBlocker(village, level).orElse(standing);
    // Prices carry the same markup the trade will charge, so the stall never
    // quotes a figure the exchange then disagrees with.
    double markup = com.quzzar.villagelife.wrongdoing.Standing.priceMultiplier(
        com.quzzar.villagelife.wrongdoing.Standing.of(village, level, player.getUUID()));
    // The stall is still listed when it cannot trade, so the screen can strike
    // the arrows out rather than going blank. A blank stall says "no trades
    // exist"; a struck-out one says "not with you, not right now".
    String note = blocker.isEmpty() ? message
        : (standing.isEmpty() ? "This market cannot trade: " : "'" + village.getName()
            + "' will not trade with you: ") + blocker + ".";
    com.quzzar.villagelife.menu.MarketMenu menu =
        player.containerMenu instanceof com.quzzar.villagelife.menu.MarketMenu open ? open : null;
    List<MarketOffers.Offer> sellingOffers = menu != null ? menu.sellingSnapshot()
        : MarketOffers.selling(village, level, markup);
    List<MarketOffers.Offer> wantedOffers = menu != null ? menu.wantedSnapshot()
        : MarketOffers.wanted(village, level, markup);
    List<Row> selling = sellingOffers.stream()
        .map(offer -> new Row(MarketOffers.idOf(offer.item()), offer.itemCount(), offer.emeralds()))
        .toList();
    List<Row> wanted = wantedOffers.stream()
        .map(offer -> new Row(MarketOffers.idOf(offer.item()), offer.itemCount(), offer.emeralds()))
        .toList();
    PacketDistributor.sendToPlayer(player, new MarketOffersPacket(merchant.getId(), village.getName(),
        !blocker.isEmpty(), selling, wanted, note));
  }

  public static void handle(MarketOffersPacket msg, IPayloadContext context) {
    context.enqueueWork(
        () -> com.quzzar.villagelife.client.gui.PersonChatScreen.onMarketOffers(msg));
  }
}
