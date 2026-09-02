package com.quzzar.villagelife.entities;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.village.Occupation;
import com.quzzar.villagelife.village.bookkeeping.NoResourceBookkeepingEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;

import net.neoforged.neoforge.common.Tags;

/**
 * The tool a trade works with, and how a lost one is made again.
 *
 * A villager may give their axe away in conversation, drop it, or wear it out.
 * The one time a tool appears from nowhere is the bare starting kit on taking
 * the job ({@link RealPerson#issueStartingKit}); after that, a hand that is
 * bare at bedtime is filled the way a player would fill it. First the best tool
 * of the kind the village stores can spare (the ordinary gear pass), then one
 * made from what the stores hold: the head alone, three cobblestone for an axe
 * or a pickaxe, two for a hoe, three string for a bow. Sticks are waived
 * (Aaron, 2026-09-02: "a pickaxe is just three cobblestone"), the way the
 * recipes waive the saw between logs and planks; the handle is not worth a
 * villager's trip to the woodpile. The stone comes out of a real chest, any
 * cobblestone kind the mine yields, and goes back if the rest is short. A
 * village with neither the tool nor the stone logs the shortage, and the
 * worker turns out tomorrow bare-handed, which the chat briefing shows, so
 * they can ask.
 */
public enum JobTool {

  AXE(AxeItem.class, Items.STONE_AXE, 3, 0),
  PICKAXE(PickaxeItem.class, Items.STONE_PICKAXE, 3, 0),
  HOE(HoeItem.class, Items.STONE_HOE, 2, 0),
  BOW(BowItem.class, Items.BOW, 0, 3);

  private final Class<? extends Item> kind;
  private final Item basic;
  private final int stone;
  private final int string;

  JobTool(Class<? extends Item> kind, Item basic, int stone, int string) {
    this.kind = kind;
    this.basic = basic;
    this.stone = stone;
    this.string = string;
  }

  /** The tool an occupation works with, or null for a trade that carries a token rather than a tool. */
  @Nullable
  public static JobTool of(Occupation occupation) {
    return switch (occupation) {
      case GUARD, LUMBERJACK -> AXE;
      case MINER -> PICKAXE;
      case FARMER -> HOE;
      case HUNTER -> BOW;
      default -> null;
    };
  }

  /** The item class the gear pass looks for: any axe, any pickaxe. */
  public Class<? extends Item> kind() {
    return this.kind;
  }

  /** Whether the villager's main hand holds a tool of this kind, of any tier. */
  public boolean inHand(RealPerson person) {
    return this.kind.isInstance(person.getMainHandItem().getItem());
  }

  /**
   * Make the basic tool for a bare-handed worker out of the village's stock and
   * put it in their hand, or log the shortage. Everything gathered goes back to
   * the stores when the rest is short.
   */
  public static void replace(RealPerson person, JobTool tool, @Nullable BlockPos depositToLoc) {
    List<ItemStack> taken = new ArrayList<>();
    boolean made = tool.gatherString(person, depositToLoc, taken)
        && gatherAnyOf(person, Tags.Items.COBBLESTONES, tool.stone, depositToLoc, taken) == tool.stone;
    if (!made) {
      for (ItemStack part : taken) {
        person.getVillage().placeItemStackIntoVillage(part, person, depositToLoc);
      }
      person.getVillage().logEvent(new NoResourceBookkeepingEvent(tool.basic, 1));
      person.logIssue("I have no " + plain(tool.basic) + " to work with and nothing to make one from",
          java.util.Optional.empty());
      Villagelife.LOGGER.info("'{}' has no {} and the stores hold nothing to make one from",
          person.getFullName(), plain(tool.basic));
      return;
    }
    person.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(tool.basic));
    Villagelife.LOGGER.info("'{}' made a {} from {} for tomorrow's work", person.getFullName(),
        plain(tool.basic), describe(taken));
  }

  private boolean gatherString(RealPerson person, @Nullable BlockPos near, List<ItemStack> taken) {
    if (this.string == 0) {
      return true;
    }
    ItemStack got = person.gatherForWork(new ItemStack(Items.STRING, this.string), near);
    if (!got.isEmpty()) {
      taken.add(got);
    }
    return got.getCount() == this.string;
  }

  /** Up to {@code count} of any item in the tag, out of the home chest and stores; how many came. */
  private static int gatherAnyOf(RealPerson person, TagKey<Item> tag, int count, @Nullable BlockPos near,
      List<ItemStack> taken) {
    int left = count;
    for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
      if (left <= 0) {
        break;
      }
      ItemStack got = person.gatherForWork(new ItemStack(holder.value(), left), near);
      if (!got.isEmpty()) {
        taken.add(got);
        left -= got.getCount();
      }
    }
    return count - left;
  }

  private static String plain(Item item) {
    return item.getDescription().getString().toLowerCase(java.util.Locale.ROOT);
  }

  private static String describe(List<ItemStack> parts) {
    List<String> words = new ArrayList<>();
    for (ItemStack part : parts) {
      words.add(part.getCount() + " " + plain(part.getItem()));
    }
    return String.join(", ", words);
  }
}
