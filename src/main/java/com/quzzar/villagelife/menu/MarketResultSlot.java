package com.quzzar.villagelife.menu;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The slot you take a trade out of. Nothing may be put into it, and taking
 * from it is what makes the trade happen, exactly as MerchantResultSlot works.
 */
public class MarketResultSlot extends Slot {

  private final MarketMenu menu;

  public MarketResultSlot(MarketMenu menu, Container container, int index, int x, int y) {
    super(container, index, x, y);
    this.menu = menu;
  }

  @Override
  public boolean mayPlace(ItemStack stack) {
    return false;
  }

  @Override
  public void onTake(Player player, ItemStack stack) {
    menu.completeTrade();
    super.onTake(player, stack);
  }
}
