package com.quzzar.kithkyn.wrongdoing;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.village.Village;
import com.quzzar.kithkyn.village.VillageManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Taking from a village, and being seen doing it (decided on #64).
 *
 * Putting things IN is always fine and never suspicious — a gift is a gift, and
 * a village that eyed every donation would be exhausting. Taking things out is
 * an offence, and so is breaking the container they were in, which is the same
 * theft with an extra step.
 *
 * A chest's contents are counted when a player opens it and counted again when
 * they close it. Anything missing left with them. That is cruder than watching
 * every click, and it is honest about what a village can actually notice: that
 * the stores are lighter than they were, and who was standing there.
 */
@EventBusSubscriber(modid = Kithkyn.MODID)
public final class TheftEvents {

  /** What each open container held when its lid went up, per player. */
  private static final Map<UUID, Map<Item, Integer>> OPENED = new HashMap<>();

  private TheftEvents() {
  }

  @SubscribeEvent
  public static void onContainerOpen(PlayerContainerEvent.Open event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) {
      return;
    }
    Container container = openedVillageContainer(player, event);
    if (container == null) {
      return;
    }
    OPENED.put(player.getUUID(), contentsOf(container));
  }

  @SubscribeEvent
  public static void onContainerClose(PlayerContainerEvent.Close event) {
    if (!(event.getEntity() instanceof ServerPlayer player)) {
      return;
    }
    Map<Item, Integer> before = OPENED.remove(player.getUUID());
    if (before == null || !(player.level() instanceof ServerLevel level)) {
      return;
    }
    Container container = openedVillageContainer(player, event);
    if (container == null) {
      return;
    }
    Map<Item, Integer> after = contentsOf(container);

    for (Map.Entry<Item, Integer> entry : before.entrySet()) {
      int taken = entry.getValue() - after.getOrDefault(entry.getKey(), 0);
      if (taken <= 0) {
        continue;
      }
      Village village = VillageManager.get(level).getNearestVillage(player.blockPosition());
      Wrongdoing.report(level, village, player.getUUID(), Wrongdoing.Offence.THEFT,
          player.position(),
          player.getName().getString() + " took " + taken + " "
              + entry.getKey().getDescription().getString().toLowerCase() + " from our stores", null);
      return; // one report per visit: a thief is a thief, not a thief per item
    }
  }

  @SubscribeEvent
  public static void onBlockBreak(BlockEvent.BreakEvent event) {
    if (!(event.getLevel() instanceof ServerLevel level) || event.isCanceled()) {
      return;
    }
    if (!(event.getPlayer() instanceof ServerPlayer player)) {
      return;
    }
    BlockPos pos = event.getPos();
    Village village = VillageManager.get(level).getNearestVillage(pos);
    if (village == null || !isVillageContainer(level, village, pos)) {
      return;
    }
    Wrongdoing.report(level, village, player.getUUID(), Wrongdoing.Offence.THEFT,
        net.minecraft.world.phys.Vec3.atCenterOf(pos),
        player.getName().getString() + " broke into our stores", null);
  }

  /**
   * The container this player has open, if it belongs to a village. Anything
   * else — their own chest, a dungeon, their inventory — is not the village's
   * business.
   */
  private static Container openedVillageContainer(ServerPlayer player, PlayerContainerEvent event) {
    if (!(player.level() instanceof ServerLevel level)) {
      return null;
    }
    BlockPos looking = player.blockPosition();
    Village village = VillageManager.get(level).getNearestVillage(looking);
    if (village == null) {
      return null;
    }
    // The menu does not say which block it came from, so the village's own
    // containers within reach are the ones that can be open.
    for (BlockPos pos : BlockPos.betweenClosed(looking.offset(-6, -4, -6), looking.offset(6, 4, 6))) {
      if (!isVillageContainer(level, village, pos)) {
        continue;
      }
      if (level.getBlockEntity(pos) instanceof Container container && container.stillValid(player)) {
        return container;
      }
    }
    return null;
  }

  private static boolean isVillageContainer(ServerLevel level, Village village, BlockPos pos) {
    return village.getVillageContainerPositions().contains(pos)
        && level.getBlockEntity(pos) instanceof Container;
  }

  private static Map<Item, Integer> contentsOf(Container container) {
    Map<Item, Integer> counts = new HashMap<>();
    for (int slot = 0; slot < container.getContainerSize(); slot++) {
      ItemStack stack = container.getItem(slot);
      if (!stack.isEmpty()) {
        counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
      }
    }
    return counts;
  }

}
