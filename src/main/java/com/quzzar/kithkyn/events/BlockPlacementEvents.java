package com.quzzar.kithkyn.events;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.savedata.PlacedBlockStore;
import com.quzzar.kithkyn.village.BlockOwnership;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Keeps {@link PlacedBlockStore} in step with what players do: a block a player
 * sets down is remembered, and a block anyone breaks is forgotten. Plants are
 * the exception ({@link BlockOwnership#isPlanted}): put down to grow, never
 * recorded.
 *
 * The break side is what keeps the store compact. Since a broken position is
 * dropped, placing and later removing a block nets to nothing rather than
 * leaving a permanent entry, so the set tracks what currently stands, not
 * everything ever placed.
 *
 * Non-player removals (explosions, pistons, fluids) do not fire a break event,
 * so a player-placed block destroyed that way lingers in the set as a stale
 * entry until something is next placed and broken there. That is a bounded,
 * accepted imprecision: it can only over-protect, never under-protect.
 */
@EventBusSubscriber(modid = Kithkyn.MODID)
public final class BlockPlacementEvents {

  private BlockPlacementEvents() {
  }

  @SubscribeEvent
  public static void onPlace(BlockEvent.EntityPlaceEvent event) {
    if (event.getEntity() instanceof Player && event.getLevel() instanceof ServerLevel level
        && !BlockOwnership.isPlanted(event.getPlacedBlock())) {
      PlacedBlockStore.get(level).markPlayerPlaced(event.getPos());
    }
  }

  @SubscribeEvent
  public static void onMultiPlace(BlockEvent.EntityMultiPlaceEvent event) {
    if (!(event.getEntity() instanceof Player) || !(event.getLevel() instanceof ServerLevel level)
        || BlockOwnership.isPlanted(event.getPlacedBlock())) {
      return;
    }
    PlacedBlockStore store = PlacedBlockStore.get(level);
    for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
      store.markPlayerPlaced(snapshot.getPos());
    }
  }

  @SubscribeEvent
  public static void onBreak(BlockEvent.BreakEvent event) {
    if (event.getLevel() instanceof ServerLevel level) {
      PlacedBlockStore.get(level).clearPlaced(event.getPos());
    }
  }

}
