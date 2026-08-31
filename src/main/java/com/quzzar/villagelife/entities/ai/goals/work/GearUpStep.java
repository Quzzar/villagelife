package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Occupation;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * The distribution half of the equipment economy: a villager takes the better
 * gear its role needs from the village stores and puts it on, returning its old
 * gear to the shelves. Universal, not per-job - the blacksmith forges an iron
 * pickaxe into the stores ({@link BlacksmithStep}), and here the miner still
 * swinging a stone one walks over and upgrades itself.
 *
 * <p>MVP: the primary work TOOL, upgraded to iron. A villager already holding an
 * iron (or better) tool of its trade wants nothing, so this stays dormant once
 * everyone is equipped. Armour, weapons, and folding the miner's bucket in here
 * are later refinements.
 */
public final class GearUpStep implements BlockWorkStep {

  /** The trade tool a role wants, and the set that already counts as good enough. */
  private record RoleTool(Occupation role, Item want, Set<Item> alreadyGood) {
  }

  private static final List<RoleTool> TOOLS = List.of(
      new RoleTool(Occupation.MINER, Items.IRON_PICKAXE,
          Set.of(Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE)),
      new RoleTool(Occupation.LUMBERJACK, Items.IRON_AXE,
          Set.of(Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE)),
      new RoleTool(Occupation.FARMER, Items.IRON_HOE,
          Set.of(Items.IRON_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE)));

  /** The tool to fetch this trip, chosen in select. */
  private Item wanting;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    Village village = person.getVillage();
    if (village == null) {
      return null;
    }
    Item want = neededTool(person);
    if (want == null || !village.hasItemStackInVillage(new ItemStack(want))) {
      return null;
    }
    this.wanting = want;
    return toolChest(person, village, want);
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    if (this.wanting == null
        || !(person.level().getBlockEntity(target) instanceof Container chest)) {
      return false;
    }
    ItemStack upgrade = takeOne(chest, this.wanting);
    if (upgrade.isEmpty()) {
      return false; // someone beat us to it
    }
    // Swap: the old tool goes back to the shelves, the new one onto the hand.
    ItemStack old = person.getMainHandItem().copy();
    person.setItemSlot(EquipmentSlot.MAINHAND, upgrade);
    if (!old.isEmpty()) {
      HopperBlockEntity.addItem(null, chest, old, null);
    }
    Villagelife.LOGGER.debug("[resource-flow] {} ({}) equipped a {} from the stores",
        person.getName().getString(), person.getOccupation(), this.wanting);
    return false; // one upgrade per trip
  }

  @Override
  public String describe() {
    return "the stores";
  }

  /** The trade tool this villager still lacks an iron-or-better version of; null if none. */
  @Nullable
  private Item neededTool(RealPerson person) {
    for (RoleTool tool : TOOLS) {
      if (tool.role() != person.getOccupation()) {
        continue;
      }
      return tool.alreadyGood().contains(person.getMainHandItem().getItem()) ? null : tool.want();
    }
    return null;
  }

  /** The nearest village container holding the wanted tool; null if none does. */
  @Nullable
  private BlockPos toolChest(RealPerson person, Village village, Item want) {
    BlockPos best = null;
    double bestSqr = Double.MAX_VALUE;
    for (BlockPos pos : village.getVillageContainerPositions()) {
      if (!(person.level().getBlockEntity(pos) instanceof Container chest) || !holds(chest, want)) {
        continue;
      }
      double d = pos.distSqr(person.blockPosition());
      if (d < bestSqr) {
        bestSqr = d;
        best = pos;
      }
    }
    return best;
  }

  private boolean holds(Container chest, Item want) {
    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
      if (chest.getItem(slot).is(want)) {
        return true;
      }
    }
    return false;
  }

  private ItemStack takeOne(Container chest, Item want) {
    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
      ItemStack stack = chest.getItem(slot);
      if (stack.is(want)) {
        ItemStack taken = stack.split(1);
        chest.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
        return taken;
      }
    }
    return ItemStack.EMPTY;
  }
}
