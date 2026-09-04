package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** An immutable material quantity, including totals larger than one inventory stack. */
public record MaterialAmount(Item item, int count) {
  public static final Codec<MaterialAmount> CODEC = RecordCodecBuilder.create(instance -> instance.group(
      BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(MaterialAmount::item),
      Codec.intRange(1, Integer.MAX_VALUE).fieldOf("count").forGetter(MaterialAmount::count)
  ).apply(instance, MaterialAmount::new));

  public MaterialAmount {
    java.util.Objects.requireNonNull(item);
    if (count <= 0) {
      throw new IllegalArgumentException("Material quantity must be positive");
    }
  }

  /** Merges actual payment stacks without losing their item identity. */
  public static List<MaterialAmount> fromStacks(List<ItemStack> stacks) {
    Map<Item, Integer> totals = new LinkedHashMap<>();
    for (ItemStack stack : stacks) {
      if (!stack.isEmpty()) {
        totals.merge(stack.getItem(), stack.getCount(), Math::addExact);
      }
    }
    return totals.entrySet().stream().map(entry -> new MaterialAmount(entry.getKey(), entry.getValue())).toList();
  }

  public static List<ItemStack> stacks(List<MaterialAmount> amounts) {
    return amounts.stream().map(amount -> new ItemStack(amount.item(), amount.count())).toList();
  }

  public static List<MaterialAmount> combine(List<MaterialAmount> first, List<MaterialAmount> second) {
    List<ItemStack> stacks = new ArrayList<>(stacks(first));
    stacks.addAll(stacks(second));
    return fromStacks(stacks);
  }

  /** Half of each material actually invested; rounding happens once per building and item. */
  public static List<MaterialAmount> salvage(List<MaterialAmount> investment) {
    return investment.stream().filter(amount -> amount.count() >= 2)
        .map(amount -> new MaterialAmount(amount.item(), amount.count() / 2)).toList();
  }

  public static Map<Item, Integer> tally(List<MaterialAmount> amounts) {
    Map<Item, Integer> result = new LinkedHashMap<>();
    for (MaterialAmount amount : amounts) {
      result.merge(amount.item(), amount.count(), Math::addExact);
    }
    return result;
  }

  public static String describe(List<MaterialAmount> amounts) {
    return amounts.isEmpty() ? "none" : amounts.stream()
        .map(amount -> amount.count() + " " + Materials.describe(amount.item()))
        .collect(java.util.stream.Collectors.joining(", "));
  }
}
