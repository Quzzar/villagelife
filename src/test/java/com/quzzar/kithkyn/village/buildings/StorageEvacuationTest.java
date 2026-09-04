package com.quzzar.kithkyn.village.buildings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class StorageEvacuationTest {
  @Test
  void aPartialTransferCannotDuplicateWhatReachedStorage() {
    SimpleContainer source = new SimpleContainer(new ItemStack(Items.COBBLESTONE, 20));
    SimpleContainer destination = new SimpleContainer(new ItemStack(Items.COBBLESTONE, 60));

    assertFalse(StorageEvacuation.transfer(List.of(source), List.of(destination)));
    assertEquals(16, source.getItem(0).getCount());
    assertEquals(64, destination.getItem(0).getCount());
    assertEquals(80, source.getItem(0).getCount() + destination.getItem(0).getCount());
  }

  @Test
  void feasibilityAccountsForAllVictimsTogetherWithoutMovingTheirContents() {
    SimpleContainer first = new SimpleContainer(new ItemStack(Items.COBBLESTONE, 40));
    SimpleContainer second = new SimpleContainer(new ItemStack(Items.COBBLESTONE, 40));
    SimpleContainer destination = new SimpleContainer(1);

    assertFalse(StorageEvacuation.canFit(List.of(first, second), List.of(destination)));
    assertEquals(40, first.getItem(0).getCount());
    assertEquals(40, second.getItem(0).getCount());
    assertTrue(destination.isEmpty());
  }

  @Test
  void successfulEvacuationPreservesFullContentsWithoutApplyingTheSalvageRate() {
    SimpleContainer source = new SimpleContainer(new ItemStack(Items.DIAMOND, 17));
    SimpleContainer destination = new SimpleContainer(2);

    assertTrue(StorageEvacuation.canFit(List.of(source), List.of(destination)));
    assertTrue(StorageEvacuation.transfer(List.of(source), List.of(destination)));
    assertTrue(source.isEmpty());
    assertEquals(17, destination.getItem(0).getCount());
  }
}
