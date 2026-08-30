package com.quzzar.villagelife.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * Persistent conversation history, stored as the {@code villagelife:chat_history}
 * attachment on a person: the last exchanges per player, surviving screen
 * closes and server restarts (conversation map #45). Bounded on both axes so
 * NBT stays small: at most {@link #MAX_PLAYERS} players tracked (least
 * recently talked-to evicted) and {@link #MAX_EXCHANGES} exchanges per player
 * (oldest pruned). The chat briefing feeds from this, so villagers remember
 * what was said, not just what happened.
 */
public record ChatHistoryData(Map<UUID, List<Exchange>> byPlayer) {

    public static final int MAX_PLAYERS = 8;
    public static final int MAX_EXCHANGES = 10;

    public static final ChatHistoryData EMPTY = new ChatHistoryData(Map.of());

    /** One player line and the villager's reply, stamped with world day time. */
    public record Exchange(String playerLine, String reply, long dayTime) {
        public static final Codec<Exchange> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.STRING.fieldOf("player").forGetter(Exchange::playerLine),
                Codec.STRING.fieldOf("reply").forGetter(Exchange::reply),
                Codec.LONG.optionalFieldOf("day_time", 0L).forGetter(Exchange::dayTime)
        ).apply(inst, Exchange::new));
    }

    public static final Codec<ChatHistoryData> CODEC = Codec
            .unboundedMap(UUIDUtil.STRING_CODEC, Exchange.CODEC.listOf())
            .xmap(ChatHistoryData::new, ChatHistoryData::byPlayer);

    public List<Exchange> with(UUID playerId) {
        return byPlayer.getOrDefault(playerId, List.of());
    }

    /** Returns a copy with the exchange appended and both bounds enforced. */
    public ChatHistoryData withExchange(UUID playerId, Exchange exchange) {
        Map<UUID, List<Exchange>> updated = new HashMap<>();
        byPlayer.forEach((id, exchanges) -> updated.put(id, new ArrayList<>(exchanges)));

        List<Exchange> mine = updated.computeIfAbsent(playerId, id -> new ArrayList<>());
        mine.add(exchange);
        while (mine.size() > MAX_EXCHANGES) {
            mine.remove(0);
        }

        while (updated.size() > MAX_PLAYERS) {
            UUID oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<UUID, List<Exchange>> entry : updated.entrySet()) {
                if (entry.getKey().equals(playerId)) {
                    continue; // never evict the player we just talked to
                }
                List<Exchange> exchanges = entry.getValue();
                long last = exchanges.isEmpty() ? 0 : exchanges.get(exchanges.size() - 1).dayTime();
                if (last < oldestTime) {
                    oldestTime = last;
                    oldest = entry.getKey();
                }
            }
            if (oldest == null) {
                break;
            }
            updated.remove(oldest);
        }

        Map<UUID, List<Exchange>> frozen = new HashMap<>();
        updated.forEach((id, exchanges) -> frozen.put(id, List.copyOf(exchanges)));
        return new ChatHistoryData(Map.copyOf(frozen));
    }

    /**
     * A copy with this player's raw exchanges dropped, used when a new-day chat
     * consolidates the prior session into a summary (ChatSummaryData) and starts
     * the transcript fresh. Other players' history is untouched.
     */
    public ChatHistoryData clearedFor(UUID playerId) {
        if (!byPlayer.containsKey(playerId)) {
            return this;
        }
        Map<UUID, List<Exchange>> updated = new HashMap<>(byPlayer);
        updated.remove(playerId);
        return new ChatHistoryData(Map.copyOf(updated));
    }
}
