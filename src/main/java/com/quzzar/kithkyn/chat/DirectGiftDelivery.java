package com.quzzar.kithkyn.chat;

import java.util.function.Consumer;

import net.minecraft.world.item.ItemStack;

/** The inventory-first half of handing an item to a player. */
final class DirectGiftDelivery {

  record Result(int inserted, ItemStack overflow) {
  }

  private DirectGiftDelivery() {
  }

  /**
   * Offers the gift to an inventory operation that mutates the offered stack,
   * then reports exactly what landed and what is still physical overflow.
   */
  static Result insert(ItemStack gift, Consumer<ItemStack> inventoryInsert) {
    int offered = gift.getCount();
    inventoryInsert.accept(gift);
    int remaining = gift.getCount();
    return new Result(offered - remaining, gift.isEmpty() ? ItemStack.EMPTY : gift.copy());
  }
}
