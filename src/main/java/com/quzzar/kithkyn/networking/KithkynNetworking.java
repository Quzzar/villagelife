package com.quzzar.kithkyn.networking;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class KithkynNetworking {

    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(PersonChatMessagePacket.TYPE, PersonChatMessagePacket.STREAM_CODEC, PersonChatMessagePacket::handle);
        registrar.playToServer(PersonChatClosePacket.TYPE, PersonChatClosePacket.STREAM_CODEC, PersonChatClosePacket::handle);
        registrar.playToClient(OpenPersonChatPacket.TYPE, OpenPersonChatPacket.STREAM_CODEC, OpenPersonChatPacket::handle);
        registrar.playToClient(PersonChatReplyPacket.TYPE, PersonChatReplyPacket.STREAM_CODEC, PersonChatReplyPacket::handle);
        registrar.playToClient(VillagerSpeechPacket.TYPE, VillagerSpeechPacket.STREAM_CODEC, VillagerSpeechPacket::handle);
        registrar.playToServer(MarketActionPacket.TYPE, MarketActionPacket.STREAM_CODEC, MarketActionPacket::handle);
        registrar.playToServer(SelectTradePacket.TYPE, SelectTradePacket.STREAM_CODEC, SelectTradePacket::handle);
        registrar.playToClient(MarketOffersPacket.TYPE, MarketOffersPacket.STREAM_CODEC, MarketOffersPacket::handle);
    }
}
