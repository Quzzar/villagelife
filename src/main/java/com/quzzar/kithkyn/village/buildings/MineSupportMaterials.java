package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Dirt and stone blocks a miner can spend on floors, walls, and ceilings. */
public final class MineSupportMaterials {

  public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM,
      ResourceLocation.fromNamespaceAndPath("kithkyn", "mine_support_materials"));

  private MineSupportMaterials() {
  }

  /** Only a placeable item in the mine-support family can become lining. */
  public static boolean isSupport(ItemStack stack) {
    return !stack.isEmpty() && stack.getItem() instanceof BlockItem && stack.is(TAG);
  }

  public static int held(Container container) {
    int total = 0;
    for (int slot = 0; slot < container.getContainerSize(); slot++) {
      ItemStack stack = container.getItem(slot);
      if (isSupport(stack)) {
        total += stack.getCount();
      }
    }
    return total;
  }

  /** Takes mixed support blocks, preserving each actual block the miner carries. */
  public static List<ItemStack> take(Container container, int amount) {
    List<ItemStack> taken = new ArrayList<>();
    int left = amount;
    for (int slot = container.getContainerSize() - 1; slot >= 0 && left > 0; slot--) {
      ItemStack stack = container.getItem(slot);
      if (!isSupport(stack)) {
        continue;
      }
      ItemStack piece = stack.split(left);
      left -= piece.getCount();
      taken.add(piece);
    }
    if (!taken.isEmpty()) {
      container.setChanged();
    }
    return taken;
  }

  public static ItemStack takeOne(Container container) {
    List<ItemStack> taken = take(container, 1);
    return taken.isEmpty() ? ItemStack.EMPTY : taken.getFirst();
  }

  /** The exact block state represented by a consumed support item. */
  public static BlockState blockState(ItemStack support) {
    return support.getItem() instanceof BlockItem blockItem
        ? blockItem.getBlock().defaultBlockState()
        : null;
  }
}
