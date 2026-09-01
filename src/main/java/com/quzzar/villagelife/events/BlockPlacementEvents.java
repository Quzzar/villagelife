package com.quzzar.villagelife.events;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.savedata.PlacedBlockStore;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Keeps {@link PlacedBlockStore} in step with what players do: a block a player
 * sets down is remembered, and a block anyone breaks is forgotten.
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
@EventBusSubscriber(modid = Villagelife.MODID)
public final class BlockPlacementEvents {

  private BlockPlacementEvents() {
  }

  @SubscribeEvent
  public static void onPlace(BlockEvent.EntityPlaceEvent event) {
    if (event.getEntity() instanceof Player && event.getLevel() instanceof ServerLevel level
        && !isPlanted(event.getPlacedBlock())) {
      PlacedBlockStore.get(level).markPlayerPlaced(event.getPos());
    }
  }

  @SubscribeEvent
  public static void onMultiPlace(BlockEvent.EntityMultiPlaceEvent event) {
    if (!(event.getEntity() instanceof Player) || !(event.getLevel() instanceof ServerLevel level)
        || isPlanted(event.getPlacedBlock())) {
      return;
    }
    PlacedBlockStore store = PlacedBlockStore.get(level);
    for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
      store.markPlayerPlaced(snapshot.getPos());
    }
  }

  /**
   * Things put down to grow rather than to stand. A sapling is not a structure,
   * and recording it would carry over to the tree it becomes: the grown trunk's
   * base log sat on the sapling's recorded position, so a worker felled the
   * whole tree and left exactly one block - the base - standing as "the
   * player's". Plants are nobody's, and what grows from them is fellable.
   */
  private static boolean isPlanted(BlockState placed) {
    return placed.is(BlockTags.SAPLINGS) || placed.is(BlockTags.CROPS) || placed.is(BlockTags.FLOWERS);
  }

  @SubscribeEvent
  public static void onBreak(BlockEvent.BreakEvent event) {
    if (event.getLevel() instanceof ServerLevel level) {
      PlacedBlockStore.get(level).clearPlaced(event.getPos());
    }
  }

}
