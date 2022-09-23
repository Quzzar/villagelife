package com.quzzar.villagelife.other;

import java.util.ArrayList;
import java.util.Random;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;

import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.common.TierSortingRegistry;

public class EquipmentUpgrade {

  @Nullable
  public static ItemStack findUpgrade(ItemStack currentItem, ArrayList<ItemStack> options, Random random) {

    Class classType;
    if (currentItem.getItem() instanceof ArmorItem) {
      classType = ArmorItem.class;
    } else if (currentItem.getItem() instanceof SwordItem) {
      classType = SwordItem.class;
    } else if (currentItem.getItem() instanceof ShovelItem) {
      classType = ShovelItem.class;
    } else if (currentItem.getItem() instanceof AxeItem) {
      classType = AxeItem.class;
    } else if (currentItem.getItem() instanceof HoeItem) {
      classType = HoeItem.class;
    } else if (currentItem.getItem() instanceof PickaxeItem) {
      classType = PickaxeItem.class;
    } else if (currentItem.getItem() instanceof CrossbowItem) {
      classType = CrossbowItem.class;
    } else if (currentItem.getItem() instanceof BowItem) {
      classType = BowItem.class;
    } else if (currentItem.getItem() instanceof TridentItem) {
      classType = TridentItem.class;
    } else if (currentItem.getItem() instanceof FishingRodItem) {
      classType = FishingRodItem.class;
    } else {
      return currentItem;
    }

    float currentScore = getScore(currentItem, random);

    float bestScore = currentScore;
    ItemStack bestItem = null;

    for (ItemStack itemStack : options) {

      if (classType.isInstance(itemStack.getItem())) {

        // If is armorItem, also check if is for correct slot.
        if (itemStack.getItem() instanceof ArmorItem) {
          if (((ArmorItem) itemStack.getItem()).getSlot() != ((ArmorItem) currentItem.getItem()).getSlot()) {
            continue;
          }
        }

        float score = getScore(itemStack, random);
        if (score > bestScore) {
          bestScore = score;
          bestItem = itemStack;
        }

      }

    }

    return bestItem != null ? bestItem.copy() : null;
  }

  @Nullable
  public static ItemStack findBestOfType(Class classType, EquipmentSlot armorSlot, ArrayList<ItemStack> options,
      Random random) {

    float bestScore = 0;
    ItemStack bestItem = null;

    for (ItemStack itemStack : options) {

      if (classType.isInstance(itemStack.getItem())) {

        // If is armorItem, also check if is for correct slot.
        if (itemStack.getItem() instanceof ArmorItem) {
          if (((ArmorItem) itemStack.getItem()).getSlot() != armorSlot) {
            continue;
          }
        }

        float score = getScore(itemStack, random);
        if (score > bestScore) {
          bestScore = score;
          bestItem = itemStack;
        }

      }

    }

    return bestItem != null ? bestItem.copy() : null;
  }

  private static float getScore(ItemStack itemStack, Random random) {

    float score = 0;

    if (itemStack.getItem() instanceof ArmorItem) {

      ArmorItem armorItem = (ArmorItem) itemStack.getItem();
      score += armorItem.getDefense() + armorItem.getToughness() * 0.25F;

    } else if (itemStack.getItem() instanceof TieredItem) {

      TieredItem tieredItem = (TieredItem) itemStack.getItem();
      score += TierSortingRegistry.getTiersLowerThan(tieredItem.getTier()).size()*2.0F;

    }

    ListTag enchantTags = itemStack.getEnchantmentTags();
    for (int i = 0; i < enchantTags.size(); ++i) {
      CompoundTag compoundtag = enchantTags.getCompound(i);
      Enchantment enchantment = Registry.ENCHANTMENT.getOptional(EnchantmentHelper.getEnchantmentId(compoundtag)).get();
      if(enchantment != null){
        // Ranges from 0 - 0.5
        float rarityMod = (10.0F / enchantment.getRarity().getWeight()) / 20.0F;

        // Ranges from 0 - 2-ish
        float enchantCost = enchantment.getMinCost(EnchantmentHelper.getEnchantmentLevel(compoundtag)) / 15.0F;

        score += enchantCost+rarityMod;
      }
    }

    return score;
  }

}
