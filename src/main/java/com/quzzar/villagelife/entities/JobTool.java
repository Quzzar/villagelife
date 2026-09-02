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
import net.minecraft.tags.ItemTags;
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
 * made at the bench from what the stores hold: cobblestone and sticks for a
 * stone tool, sticks and string for a bow, the sticks split from planks, and
 * planks from a log, four to a log, with nobody sawing (the same rule the
 * recipes use, {@code Materials}). Every part comes out of a real chest and
 * nothing is left over unaccounted for: a log split for two sticks puts its
 * spare planks back. A village with neither the tool nor the parts logs the
 * shortage, and the worker turns out tomorrow bare-handed, which the chat
 * briefing shows, so they can ask.
 */
public enum JobTool {

  AXE(AxeItem.class, Items.STONE_AXE, 3, 2, 0),
  PICKAXE(PickaxeItem.class, Items.STONE_PICKAXE, 3, 2, 0),
  HOE(HoeItem.class, Items.STONE_HOE, 2, 2, 0),
  BOW(BowItem.class, Items.BOW, 0, 3, 3);

  /** Sticks one plank splits into (vanilla: two planks make four). */
  private static final int STICKS_PER_PLANK = 2;

  /** Planks one log stands in for, the recipes' own rate. */
  private static final int PLANKS_PER_LOG = 4;

  private final Class<? extends Item> kind;
  private final Item basic;
  private final int stone;
  private final int sticks;
  private final int string;

  JobTool(Class<? extends Item> kind, Item basic, int stone, int sticks, int string) {
    this.kind = kind;
    this.basic = basic;
    this.stone = stone;
    this.sticks = sticks;
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
   * put it in their hand, or log the shortage. The parts are gathered hardest
   * first (string, then wood, then stone) so a failure returns as little as
   * possible, and everything gathered goes back to the stores when any part is
   * short.
   */
  public static void replace(RealPerson person, JobTool tool, @Nullable BlockPos depositToLoc) {
    List<ItemStack> taken = new ArrayList<>();
    boolean made = tool.gatherString(person, depositToLoc, taken)
        && tool.gatherSticks(person, depositToLoc, taken)
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

  /**
   * Sticks from the stores, then planks split two sticks to the plank, then one
   * log for the planks, its spare planks returned. The wood is spent here as
   * the sticks it became; the recipe rules never counted sticks, so nothing
   * else in the village wants them back.
   */
  private boolean gatherSticks(RealPerson person, @Nullable BlockPos near, List<ItemStack> taken) {
    int left = this.sticks;
    ItemStack sticks = person.gatherForWork(new ItemStack(Items.STICK, left), near);
    if (!sticks.isEmpty()) {
      taken.add(sticks);
      left -= sticks.getCount();
    }
    if (left <= 0) {
      return true;
    }
    int planksWanted = Math.ceilDiv(left, STICKS_PER_PLANK);
    int planks = gatherAnyOf(person, ItemTags.PLANKS, planksWanted, near, taken);
    left -= planks * STICKS_PER_PLANK;
    if (left <= 0) {
      return true;
    }
    if (gatherAnyOf(person, ItemTags.LOGS, 1, near, taken) < 1) {
      return false;
    }
    int spare = PLANKS_PER_LOG - Math.ceilDiv(left, STICKS_PER_PLANK);
    if (spare > 0) {
      person.getVillage().placeItemStackIntoVillage(new ItemStack(Items.OAK_PLANKS, spare), person, near);
    }
    return true;
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
