package com.quzzar.kithkyn.chat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;

import net.minecraft.core.UUIDUtil;

/**
 * Per-player conversation memory: a short summary of what a villager and a
 * player have talked about, kept as the {@code kithkyn:chat_summary}
 * attachment on a person.
 *
 * <p>A conversation is a session bounded by the Minecraft day (see
 * {@code RealPerson.openChat}). When a chat opens on a new day, the prior
 * session's raw transcript ({@link ChatHistoryData}) is consolidated into this
 * summary and then cleared, so the briefing carries a compact memory rather than
 * a transcript that keeps growing, which a small model drowns in. Bounded to
 * {@link #MAX_PLAYERS} so NBT stays small.
 */
public record ChatSummaryData(Map<UUID, String> byPlayer) {

    public static final int MAX_PLAYERS = 8;

    public static final ChatSummaryData EMPTY = new ChatSummaryData(Map.of());

    public static final Codec<ChatSummaryData> CODEC = Codec
            .unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING)
            .xmap(ChatSummaryData::new, ChatSummaryData::byPlayer);

    /** This villager's memory of past talks with the player, or "" if none yet. */
    public String with(UUID playerId) {
        return byPlayer.getOrDefault(playerId, "");
    }

    /**
     * A copy with this player's summary set. If that pushes the store past
     * {@link #MAX_PLAYERS}, some other player's summary is dropped; there is no
     * last-seen timestamp here, so the eviction is arbitrary rather than
     * least-recently-talked-to. That is acceptable: a dropped summary just means
     * the villager re-summarizes that player from scratch next time.
     */
    public ChatSummaryData withSummary(UUID playerId, String summary) {
        Map<UUID, String> updated = new HashMap<>(byPlayer);
        updated.put(playerId, summary);
        while (updated.size() > MAX_PLAYERS) {
            UUID victim = null;
            for (UUID id : updated.keySet()) {
                if (!id.equals(playerId)) {
                    victim = id;
                    break;
                }
            }
            if (victim == null) {
                break;
            }
            updated.remove(victim);
        }
        return new ChatSummaryData(Map.copyOf(updated));
    }
}
