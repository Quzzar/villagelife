package com.quzzar.kithkyn.events;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.village.Village;
import com.quzzar.kithkyn.village.VillageManager;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Announces a village with a fading on-screen title the first time a player
 * crosses into its claim, the way region- and biome-entry mods name a place as
 * you arrive. Each player's current village is tracked so the banner fires once
 * on entry rather than every tick, and again if they leave and come back.
 *
 * <p>Purely a client-facing flourish sent with vanilla title packets, so there
 * is no custom network channel and nothing to register on the client.
 */
@EventBusSubscriber(modid = Kithkyn.MODID)
public class VillageEntryTitle {

  /** How often, in ticks, a player's position is tested against village claims. */
  private static final int CHECK_INTERVAL = 20;

  /**
   * How far outside a village's claim the banner still fires, in blocks, so the
   * place is announced a little before the player reaches its first building.
   */
  private static final int ENTRY_PADDING = 16;

  /** Title animation timing in ticks: fade in, hold, fade out. */
  private static final int FADE_IN = 10;
  private static final int STAY = 60;
  private static final int FADE_OUT = 20;

  /** Player UUID to the village uuid they were last found inside; absent when outside any. */
  private static final Map<UUID, String> currentVillage = new ConcurrentHashMap<>();

  @SubscribeEvent
  public static void onPlayerTick(PlayerTickEvent.Post event) {
    if (!(event.getEntity() instanceof ServerPlayer player)
        || !(player.level() instanceof ServerLevel level)) {
      return;
    }
    // Claims are a coarse grid and the player is one entity; a once-a-second
    // sample is responsive enough for an entry banner and costs nothing.
    if (player.tickCount % CHECK_INTERVAL != 0) {
      return;
    }

    String insideUuid = null;
    Village inside = null;
    for (Map.Entry<String, Village> entry : VillageManager.get(level).getVillages().entrySet()) {
      if (entry.getValue().hasClaimedWithin(player.blockPosition(), ENTRY_PADDING)) {
        insideUuid = entry.getKey();
        inside = entry.getValue();
        break;
      }
    }

    UUID playerId = player.getUUID();
    if (insideUuid == null) {
      // Left every claim: forget where they were so re-entry announces again.
      currentVillage.remove(playerId);
      return;
    }
    if (insideUuid.equals(currentVillage.get(playerId))) {
      return;
    }

    currentVillage.put(playerId, insideUuid);
    announce(player, inside.getName(), inside.getPopulation().size());
  }

  private static void announce(ServerPlayer player, String villageName, int population) {
    player.connection.send(new ClientboundSetTitlesAnimationPacket(FADE_IN, STAY, FADE_OUT));
    player.connection.send(new ClientboundSetSubtitleTextPacket(
        // en-dash (U+2013) separators flanking the count; grouped with commas
        // (forced US locale) so a four-figure village reads "1,000", not "1000".
        Component.literal("– Population " + String.format(Locale.US, "%,d", population) + " –")
            .withStyle(ChatFormatting.GRAY)));
    player.connection.send(new ClientboundSetTitleTextPacket(
        Component.literal(villageName).withStyle(ChatFormatting.WHITE)));
  }
}
