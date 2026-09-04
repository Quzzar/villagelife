package com.quzzar.kithkyn.compat;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * The Curios-facing half of {@link AccessoryCompat}. Never referenced unless
 * the mod is loaded: touching this class loads Curios types, which do not
 * exist otherwise.
 */
final class CuriosAccessories {

  private CuriosAccessories() {
  }

  /** Display names of every non-empty accessory slot, in slot order. */
  static List<String> worn(LivingEntity entity) {
    List<String> worn = new ArrayList<>();
    CuriosApi.getCuriosInventory(entity).ifPresent(inventory -> {
      IItemHandlerModifiable equipped = inventory.getEquippedCurios();
      for (int slot = 0; slot < equipped.getSlots(); slot++) {
        ItemStack stack = equipped.getStackInSlot(slot);
        if (!stack.isEmpty()) {
          worn.add(stack.getHoverName().getString());
        }
      }
    });
    return worn;
  }

  /**
   * Inserts into the first slot that accepts the stack. Curios validates the
   * item against the slot itself, so an item that belongs nowhere simply comes
   * back untouched.
   */
  static ItemStack equip(LivingEntity entity, ItemStack stack) {
    return CuriosApi.getCuriosInventory(entity).map(inventory -> {
      IItemHandlerModifiable equipped = inventory.getEquippedCurios();
      ItemStack remaining = stack;
      for (int slot = 0; slot < equipped.getSlots() && !remaining.isEmpty(); slot++) {
        if (equipped.getStackInSlot(slot).isEmpty()) {
          remaining = equipped.insertItem(slot, remaining, false);
        }
      }
      return remaining;
    }).orElse(stack);
  }

}
