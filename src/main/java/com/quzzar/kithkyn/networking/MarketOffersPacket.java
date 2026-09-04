package com.quzzar.kithkyn.networking;

import java.util.List;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.economy.EconomySnapshot;
import com.quzzar.kithkyn.economy.MarketOffers;
import com.quzzar.kithkyn.economy.Treasury;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.village.Village;

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
      ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, "market_offers"));

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
    com.quzzar.kithkyn.wrongdoing.Standing.Tier tier =
        com.quzzar.kithkyn.wrongdoing.Standing.tierOf(village, level, player.getUUID());
    String standing = com.quzzar.kithkyn.wrongdoing.Standing.refusalReason(tier);
    String blocker = Treasury.tradeBlocker(village, level).orElse(standing);
    // Prices carry the same markup the trade will charge, so the stall never
    // quotes a figure the exchange then disagrees with.
    double markup = com.quzzar.kithkyn.wrongdoing.Standing.priceMultiplier(
        com.quzzar.kithkyn.wrongdoing.Standing.of(village, level, player.getUUID()));
    // The stall is still listed when it cannot trade, so the screen can strike
    // the arrows out rather than going blank. A blank stall says "no trades
    // exist"; a struck-out one says "not with you, not right now".
    String note = blocker.isEmpty() ? message
        : (standing.isEmpty() ? "This market cannot trade: " : "'" + village.getName()
            + "' will not trade with you: ") + blocker + ".";
    com.quzzar.kithkyn.menu.MarketMenu menu =
        player.containerMenu instanceof com.quzzar.kithkyn.menu.MarketMenu open ? open : null;
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

  /**
   * The wandering-merchant form: the stall comes from the merchant's own
   * virtual ledger, priced at its home village's standing towards this player.
   * There is no {@code tradeBlocker} (a merchant is always its own market); only
   * standing can shut it, and a home village since deleted shuts nothing.
   * Server thread.
   */
  public static void sendTo(ServerPlayer player, RealPerson merchant, ServerLevel level, String message) {
    EconomySnapshot ledger = merchant.getWanderingStock();
    if (ledger == null) {
      PacketDistributor.sendToPlayer(player, new MarketOffersPacket(merchant.getId(),
          merchant.getVillageName(), true, List.of(), List.of(),
          message.isEmpty() ? "This merchant has nothing to trade." : message));
      return;
    }
    Village source = merchant.getSourceVillage();
    String standing = source == null ? ""
        : com.quzzar.kithkyn.wrongdoing.Standing.refusalReason(
            com.quzzar.kithkyn.wrongdoing.Standing.tierOf(source, level, player.getUUID()));
    double markup = source == null ? 1.0D
        : com.quzzar.kithkyn.wrongdoing.Standing.priceMultiplier(
            com.quzzar.kithkyn.wrongdoing.Standing.of(source, level, player.getUUID()));
    String note = standing.isEmpty() ? message
        : ("The merchant will not trade with you: " + standing + ".");
    com.quzzar.kithkyn.menu.MarketMenu menu =
        player.containerMenu instanceof com.quzzar.kithkyn.menu.MarketMenu open ? open : null;
    List<MarketOffers.Offer> sellingOffers = menu != null ? menu.sellingSnapshot()
        : MarketOffers.sellingFrom(ledger, level, markup);
    List<MarketOffers.Offer> wantedOffers = menu != null ? menu.wantedSnapshot()
        : MarketOffers.wantedFrom(ledger, level, markup);
    List<Row> selling = sellingOffers.stream()
        .map(offer -> new Row(MarketOffers.idOf(offer.item()), offer.itemCount(), offer.emeralds()))
        .toList();
    List<Row> wanted = wantedOffers.stream()
        .map(offer -> new Row(MarketOffers.idOf(offer.item()), offer.itemCount(), offer.emeralds()))
        .toList();
    PacketDistributor.sendToPlayer(player, new MarketOffersPacket(merchant.getId(),
        merchant.getVillageName(), !standing.isEmpty(), selling, wanted, note));
  }

  public static void handle(MarketOffersPacket msg, IPayloadContext context) {
    context.enqueueWork(
        () -> com.quzzar.kithkyn.client.gui.PersonChatScreen.onMarketOffers(msg));
  }
}
