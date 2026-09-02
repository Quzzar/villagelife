package com.quzzar.villagelife.entities;

import java.util.ArrayList;
import java.util.Comparator;
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
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TieredItem;

/**
 * The tool a trade works with, and how a lost one is made again.
 *
 * A villager may give their axe away in conversation, drop it, or wear it out.
 * The one time a tool appears from nowhere is the bare starting kit on taking
 * the job ({@link RealPerson#issueStartingKit}); after that, a hand that is
 * bare at bedtime is filled the way a player would fill it. First the best tool
 * of the kind the village stores can spare (the ordinary gear pass), then one
 * made from what the stores hold, in the fundamentals the recipes are written
 * in: the head alone, three pieces for an axe or a pickaxe, two for a hoe, two
 * planks for a bow. Sticks and string are waived (Aaron, 2026-09-02: "a pickaxe
 * is just three cobblestone", "a bow is two planks"), the way the recipes waive
 * the saw between logs and planks, and a log pays for planks at the recipes'
 * own rate of four with the rounding lost ({@code Materials}).
 *
 * <p>The best the stores allow, not the least, and every tier alike: the
 * candidates are every registered tool of the kind, vanilla or modded, best
 * tier first, and each one's head material is what its tier repairs with, read
 * off the tier rather than written here. Stone is any cobblestone kind the mine
 * yields, iron three ingots, diamond three diamonds, a modded copper pickaxe
 * three of whatever its author repairs it with; a miner whose iron pickaxe wore
 * out gets iron back when the village has it and stone when it does not.
 * Everything comes out of a real chest and goes back if the rest is short. A
 * village with neither the tool nor the materials for any tier logs the
 * shortage, and the worker turns out tomorrow bare-handed, which the chat
 * briefing shows, so they can ask.
 */
public enum JobTool {

  AXE(AxeItem.class, Items.STONE_AXE, 3, 0),
  PICKAXE(PickaxeItem.class, Items.STONE_PICKAXE, 3, 0),
  HOE(HoeItem.class, Items.STONE_HOE, 2, 0),
  BOW(BowItem.class, Items.BOW, 0, 2);

  /** Planks one log stands in for, the recipes' own rate. */
  private static final int PLANKS_PER_LOG = 4;

  private final Class<? extends Item> kind;
  /** The plain tool of the kind: the shortage is logged in its name, and an untiered kind makes only it. */
  private final Item basic;
  /** Head pieces a tool of this kind takes, of whatever its tier is made of. */
  private final int head;
  private final int planks;

  JobTool(Class<? extends Item> kind, Item basic, int head, int planks) {
    this.kind = kind;
    this.basic = basic;
    this.head = head;
    this.planks = planks;
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
   * Make a tool for a bare-handed worker out of the village's stock and put it
   * in their hand, the best tier the stores have the makings of, or log the
   * shortage.
   */
  public static void replace(RealPerson person, JobTool tool, @Nullable BlockPos depositToLoc) {
    ItemStack made = ItemStack.EMPTY;
    for (Item candidate : tool.candidatesBestFirst()) {
      made = tool.make(person, depositToLoc, candidate, headMaterialsOf(candidate));
      if (!made.isEmpty()) {
        break;
      }
    }
    if (made.isEmpty()) {
      person.getVillage().logEvent(new NoResourceBookkeepingEvent(tool.basic, 1));
      person.logIssue("I have no " + plain(tool.basic) + " to work with and nothing to make one from",
          java.util.Optional.empty());
      Villagelife.LOGGER.info("'{}' has no {} and the stores hold nothing to make one from",
          person.getFullName(), plain(tool.basic));
      return;
    }
    person.setItemSlot(EquipmentSlot.MAINHAND, made);
  }

  /**
   * Every registered tool of this kind whose tier says what it is made of,
   * best tier first: faster to dig with, then longer-lived. An untiered kind
   * (the bow) offers only its plain item.
   */
  private List<Item> candidatesBestFirst() {
    List<Item> out = new ArrayList<>();
    for (Item item : BuiltInRegistries.ITEM) {
      if (this.kind.isInstance(item) && !headMaterialsOf(item).isEmpty()) {
        out.add(item);
      }
    }
    out.sort(Comparator.comparingDouble((Item item) -> (double) tierOf(item).getSpeed())
        .thenComparingInt(item -> tierOf(item).getUses())
        .reversed());
    if (out.isEmpty()) {
      out.add(this.basic);
    }
    return out;
  }

  private static Tier tierOf(Item item) {
    return ((TieredItem) item).getTier();
  }

  /** What a tool's tier is made of, as its repair ingredient says; empty for an untiered item. */
  private static List<Item> headMaterialsOf(Item item) {
    List<Item> out = new ArrayList<>();
    if (item instanceof TieredItem tiered) {
      for (ItemStack stack : tiered.getTier().getRepairIngredient().getItems()) {
        out.add(stack.getItem());
      }
    }
    return out;
  }

  /**
   * One attempt at {@code product}: the planks a bow wants, then the head pieces
   * out of any of {@code headMaterials}. Everything gathered goes back to the
   * stores when any part is short, and EMPTY comes back.
   */
  private ItemStack make(RealPerson person, @Nullable BlockPos near, Item product, List<Item> headMaterials) {
    List<ItemStack> taken = new ArrayList<>();
    boolean whole = gatherPlanks(person, near, taken)
        && gatherAny(person, headMaterials, this.head, near, taken) == this.head;
    if (!whole) {
      for (ItemStack part : taken) {
        person.getVillage().placeItemStackIntoVillage(part, person, near);
      }
      return ItemStack.EMPTY;
    }
    Villagelife.LOGGER.info("'{}' made a {} from {} for tomorrow's work", person.getFullName(),
        plain(product), describe(taken));
    return new ItemStack(product);
  }

  /**
   * Planks of any wood from the stores, then logs standing in for what is
   * short, one per four planks rounded up, the rounding lost as it is when a
   * recipe's plank line is paid in logs.
   */
  private boolean gatherPlanks(RealPerson person, @Nullable BlockPos near, List<ItemStack> taken) {
    if (this.planks == 0) {
      return true;
    }
    int left = this.planks - gatherAnyOf(person, ItemTags.PLANKS, this.planks, near, taken);
    if (left <= 0) {
      return true;
    }
    int logs = Math.ceilDiv(left, PLANKS_PER_LOG);
    return gatherAnyOf(person, ItemTags.LOGS, logs, near, taken) == logs;
  }

  /** Up to {@code count} of any item in the tag, out of the home chest and stores; how many came. */
  private static int gatherAnyOf(RealPerson person, TagKey<Item> tag, int count, @Nullable BlockPos near,
      List<ItemStack> taken) {
    List<Item> items = new ArrayList<>();
    for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
      items.add(holder.value());
    }
    return gatherAny(person, items, count, near, taken);
  }

  /** Up to {@code count} of any of the items, out of the home chest and stores; how many came. */
  private static int gatherAny(RealPerson person, List<Item> items, int count, @Nullable BlockPos near,
      List<ItemStack> taken) {
    int left = count;
    for (Item item : items) {
      if (left <= 0) {
        break;
      }
      ItemStack got = person.gatherForWork(new ItemStack(item, left), near);
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
