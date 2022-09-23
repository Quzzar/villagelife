package com.quzzar.villagelife;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

import com.google.common.base.Predicate;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.buildings.BuildingInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public class Utils {

  public static String capitalize(String str) {
    return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
  }

  public static InteractionHand getHandWith(LivingEntity livingEntity, Predicate<Item> itemPredicate) {
    return itemPredicate.test(livingEntity.getMainHandItem().getItem()) ? InteractionHand.MAIN_HAND
        : InteractionHand.OFF_HAND;
  }

  public static void insertItems(Container container, List<ItemStack> items, Entity entity) {

    for (ItemStack item : items) {
      if (item.getCount() > 0) {
        ItemEntity itemEntity = entity.spawnAtLocation(item);
        if (container != null && itemEntity != null) {
          HopperBlockEntity.addItem(container, itemEntity);
        }
      }
    }

  }

  public static ItemStack removeItem(Container container, Item item, int amount) {
    ItemStack itemstack = new ItemStack(item, 0);
    if (container == null) {
      return itemstack;
    }

    for (int i = container.getContainerSize() - 1; i >= 0; --i) {
      ItemStack itemstack1 = container.getItem(i);
      if (itemstack1.getItem().equals(item)) {
        int j = amount - itemstack.getCount();
        ItemStack itemstack2 = itemstack1.split(j);
        itemstack.grow(itemstack2.getCount());
        if (itemstack.getCount() == amount) {
          break;
        }
      }
    }

    if (!itemstack.isEmpty()) {
      container.setChanged();
    }

    return itemstack;
  }

  public static ItemStack removeItem(Container container, ItemStack item, int amount) {
    ItemStack itemstack = item.copy();
    itemstack.setCount(0);
    if (container == null) {
      return itemstack;
    }

    for (int i = container.getContainerSize() - 1; i >= 0; --i) {
      ItemStack itemstack1 = container.getItem(i);

      int prevCount = itemstack1.getCount();
      itemstack1.setCount(item.getCount());
      boolean areEqual = itemstack1.equals(item, false);
      itemstack1.setCount(prevCount);

      if (areEqual) {
        int j = amount - itemstack.getCount();
        ItemStack itemstack2 = itemstack1.split(j);
        itemstack.grow(itemstack2.getCount());
        if (itemstack.getCount() == amount) {
          break;
        }
      }
    }

    if (!itemstack.isEmpty()) {
      container.setChanged();
    }

    return itemstack;
  }

  public static int getAmountOfItemType(Container container, Item item) {
    if (container == null) {
      return 0;
    }

    int count = 0;
    for (int i = container.getContainerSize() - 1; i >= 0; --i) {
      ItemStack itemstack = container.getItem(i);
      if (itemstack.getItem().equals(item)) {
        count += itemstack.getCount();
      }
    }

    return count;
  }

  public static boolean isFullContainer(Container container) {
    boolean isFull = true;
    for (int i = 0; i < container.getContainerSize(); i++) {
      ItemStack itemstack = container.getItem(i);
      if (itemstack.getCount() < itemstack.getMaxStackSize()) {
        isFull = false;
      }
    }
    return isFull;
  }

  /*
   * public static BlockPos convertStringToBlockPos(String value) {
   * String[] split = value.split(",");
   * return new BlockPos(Integer.parseInt(split[0]), Integer.parseInt(split[1]),
   * Integer.parseInt(split[2]));
   * }
   * public static String convertBlockPosToString(BlockPos value) {
   * return value.getX()+","+value.getY()+","+value.getZ();
   * }
   */

  public static byte[] objectToByteArray(Object obj) {
    final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    try {
      final ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
      objectOutputStream.writeObject(obj);
      objectOutputStream.flush();
      objectOutputStream.close();
    } catch (IOException e) {
      // e.printStackTrace();
    }
    return byteArrayOutputStream.toByteArray();
  }

  public static <T> T byteArrayToGeneric(byte[] bytes) {
    T generic = null;
    try {
      final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
      final ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
      generic = (T) objectInputStream.readObject();
      objectInputStream.close();
    } catch (Exception e) {
      // e.printStackTrace();
    }
    return generic;
  }

}
