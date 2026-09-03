package com.quzzar.villagelife.entities;

import java.util.List;
import java.util.function.Supplier;

import com.quzzar.villagelife.village.Occupation;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

/**
 * The item a trade holds as the mark of its work, for the trades whose mark is
 * not a tool: the quartermaster's ledger (a writable book), the builder's
 * crafting table, the librarian's book, the blacksmith's iron ingot, the
 * cleric's healing and regeneration potions. This is the one place they are
 * named, so the starting kit that first hands them out
 * ({@link RealPerson#issueStartingKit}), the day-to-day check that keeps them in
 * hand ({@link RealPerson#tendSignatureGear}), and the rule that keeps a
 * villager from giving their own away all read the same list.
 *
 * <p>Tiered tools (an axe, a pickaxe, a hoe, a bow) are not here: {@link JobTool}
 * maintains those, since a tool comes in tiers and is made again from real
 * materials, while these are single, cosmetic marks. The one time a mark
 * appears from nowhere is the starting kit; after that a hand that has lost it
 * is filled from the villager's own pack or the village stores and nowhere else
 * (Aaron, 2026-09-03: fetch only, never crafted or conjured). A trade whose
 * stores cannot spare the mark simply goes without until they can.
 */
public final class SignatureGear {

  private SignatureGear() {
  }

  /**
   * One item a trade holds, in a named slot. {@code fresh} makes a new stack
   * each call, so nothing shares one mutable instance and the kit and the
   * store fetch each get their own.
   */
  public record Piece(EquipmentSlot slot, Supplier<ItemStack> fresh) {

    /** The kind of item the piece is, for naming and for the give-away guard. */
    public Item item() {
      return this.fresh.get().getItem();
    }

    /**
     * Whether {@code held} is a right item for this piece: the same item, and,
     * when the mark is a potion, the same brew (so a cleric's healing potion is
     * not mistaken for their splash of regeneration).
     */
    public boolean matches(ItemStack held) {
      ItemStack want = this.fresh.get();
      if (held.isEmpty() || held.getItem() != want.getItem()) {
        return false;
      }
      PotionContents wanted = want.get(DataComponents.POTION_CONTENTS);
      if (wanted == null) {
        return true;
      }
      return wanted.equals(held.get(DataComponents.POTION_CONTENTS));
    }
  }

  /** The marks a trade holds, in hand order, or an empty list for a trade whose mark is a tool or nothing. */
  public static List<Piece> of(Occupation occupation) {
    return switch (occupation) {
      case BLACKSMITH -> List.of(new Piece(EquipmentSlot.MAINHAND, () -> new ItemStack(Items.IRON_INGOT)));
      case BUILDER -> List.of(new Piece(EquipmentSlot.MAINHAND, () -> new ItemStack(Items.CRAFTING_TABLE)));
      case LIBRARIAN -> List.of(new Piece(EquipmentSlot.MAINHAND, () -> new ItemStack(Items.BOOK)));
      // The quartermaster keeps the village's stores; a writable book reads as
      // the ledger they are forever taking count in.
      case QUARTERMASTER -> List.of(new Piece(EquipmentSlot.MAINHAND, () -> new ItemStack(Items.WRITABLE_BOOK)));
      // A splash of regeneration to throw over the hurt, a healing potion kept
      // in the off hand. HealStep makes each throw fresh, so these are the
      // cleric's mark, not the flask it draws from.
      case CLERIC -> List.of(
          new Piece(EquipmentSlot.MAINHAND, SignatureGear::regenSplash),
          new Piece(EquipmentSlot.OFFHAND, SignatureGear::healing));
      default -> List.of();
    };
  }

  private static ItemStack regenSplash() {
    ItemStack stack = new ItemStack(Items.SPLASH_POTION);
    stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.REGENERATION));
    return stack;
  }

  private static ItemStack healing() {
    ItemStack stack = new ItemStack(Items.POTION);
    stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.HEALING));
    return stack;
  }
}
