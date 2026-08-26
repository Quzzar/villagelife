package com.quzzar.villagelife.other;

import java.util.ArrayList;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public class EquipmentUpgrade {

  @Nullable
  public static ItemStack findUpgrade(ItemStack currentItem, ArrayList<ItemStack> options, RandomSource random) {

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
          if (((ArmorItem) itemStack.getItem()).getEquipmentSlot() != ((ArmorItem) currentItem.getItem()).getEquipmentSlot()) {
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
      RandomSource random) {

    float bestScore = 0;
    ItemStack bestItem = null;

    for (ItemStack itemStack : options) {

      if (classType.isInstance(itemStack.getItem())) {

        // If is armorItem, also check if is for correct slot.
        if (itemStack.getItem() instanceof ArmorItem) {
          if (((ArmorItem) itemStack.getItem()).getEquipmentSlot() != armorSlot) {
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

  private static float getScore(ItemStack itemStack, RandomSource random) {

    float score = 0;

    if (itemStack.getItem() instanceof ArmorItem) {

      ArmorItem armorItem = (ArmorItem) itemStack.getItem();
      score += armorItem.getDefense() + armorItem.getToughness() * 0.25F;

    } else if (itemStack.getItem() instanceof TieredItem) {

      // Tier sorting registry is gone; the attack damage bonus tracks material
      // progression well enough (wood/gold 0, stone 1, iron 2, diamond 3, netherite 4).
      TieredItem tieredItem = (TieredItem) itemStack.getItem();
      score += tieredItem.getTier().getAttackDamageBonus() * 2.0F;

    }

    ItemEnchantments enchantments = itemStack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
    for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
      Enchantment enchantment = entry.getKey().value();

      // Ranges from 0 - 0.5
      float rarityMod = (10.0F / enchantment.definition().weight()) / 20.0F;

      // Ranges from 0 - 2-ish
      float enchantCost = enchantment.getMinCost(entry.getIntValue()) / 15.0F;

      score += enchantCost + rarityMod;
    }

    return score;
  }

}
