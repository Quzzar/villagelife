package com.quzzar.kithkyn.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class DirectGiftDeliveryTest {

  @Test
  void insertsWhatFitsAndReturnsOnlyTheOverflow() {
    ItemStack gift = new ItemStack(Items.DIRT, 12);

    DirectGiftDelivery.Result result = DirectGiftDelivery.insert(gift, stack -> stack.shrink(8));

    assertEquals(8, result.inserted());
    assertEquals(4, result.overflow().getCount());
  }

  @Test
  void aGiftThatFitsLeavesNothingToDrop() {
    ItemStack gift = new ItemStack(Items.DIRT, 12);

    DirectGiftDelivery.Result result = DirectGiftDelivery.insert(gift, stack -> stack.setCount(0));

    assertEquals(12, result.inserted());
    assertTrue(result.overflow().isEmpty());
  }
}
