package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The blacksmith as the village's crafting engine: it forges the gear the village
 * lacks from the iron it holds, at the forge. A CONVERT step, the need-aware
 * cousin of {@link CraftStep} - where a CraftStep makes one thing whenever its
 * input exists, this makes the FIRST thing the village is short of and can pay
 * for, and stops once it holds enough of each.
 *
 * <p>Raw iron is smelted to ingots elsewhere in the blacksmith's loop (a plain
 * CraftStep); this step turns those ingots into tools and buckets. Producing gear
 * is only half the equipment economy - villagers grabbing their role's gear from
 * the stores is the other half, done at bedtime (goToBed / equipBestPossibleGear) -
 * but the bucket alone closes a real
 * gap: the miner needs one to clear water and lava and cannot make it, so without
 * a blacksmith it never gets one. Buckets come first here for exactly that reason.
 */
public final class BlacksmithStep implements BlockWorkStep {

  /** One thing the forge can make: the ingot cost, the gear, and the stock to keep. */
  private record Gear(ItemStack cost, ItemStack output, int keep) {
  }

  /**
   * What the forge makes, in priority order. Buckets first (the miner's unmet
   * need), then the basic iron tools. Each is made only while the village holds
   * fewer than {@code keep}, so the forge tops up a small working stock rather
   * than pouring all the iron into one thing. Armour comes last, so a village low
   * on iron still gets its tools before a spare breastplate.
   */
  private static final List<Gear> GEAR = List.of(
      new Gear(new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.BUCKET), 2),
      new Gear(new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.IRON_PICKAXE), 1),
      new Gear(new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.IRON_AXE), 1),
      new Gear(new ItemStack(Items.IRON_INGOT, 1), new ItemStack(Items.IRON_SHOVEL), 1),
      new Gear(new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.IRON_HOE), 1),
      new Gear(new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.IRON_SWORD), 1),
      new Gear(new ItemStack(Items.IRON_INGOT, 5), new ItemStack(Items.IRON_HELMET), 1),
      new Gear(new ItemStack(Items.IRON_INGOT, 8), new ItemStack(Items.IRON_CHESTPLATE), 1),
      new Gear(new ItemStack(Items.IRON_INGOT, 7), new ItemStack(Items.IRON_LEGGINGS), 1),
      new Gear(new ItemStack(Items.IRON_INGOT, 4), new ItemStack(Items.IRON_BOOTS), 1));

  /** Seconds at the forge per item, matching the smelting cadence in the loop. */
  private static final int FORGE_SECONDS = 8;

  /** Chosen in select, forged in act. */
  private Gear making;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    Village village = person.getVillage();
    if (village == null) {
      return null;
    }
    Gear need = nextNeed(village);
    if (need == null) {
      return null;
    }
    this.making = need;
    BlockPos station = LocationManager.getJobLocation(person);
    return station == BlockPos.ZERO ? null : station;
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    Village village = person.getVillage();
    if (village == null || this.making == null
        || !village.hasItemStackInVillage(this.making.cost())) {
      return false;
    }
    person.setPose(Pose.CROUCHING);
    person.level().playSound((Player) null, target.getX(), target.getY(), target.getZ(),
        SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.4F, person.getRandom().nextFloat() * 0.4F + 0.6F);
    village.gatherItemStackFromVillage(this.making.cost());
    // Copy the template output - the gear list is shared, so the inserted stack
    // must not be the one the recipe is defined with.
    boolean placed = village.placeItemStackIntoVillage(this.making.output().copy(), person, target);
    if (placed) {
      Villagelife.LOGGER.debug("[resource-flow] {} (BLACKSMITH) forged a {}",
          person.getName().getString(), this.making.output().getItem());
    }
    // Keep working while there is room for it and something still worth making.
    return placed && nextNeed(village) != null;
  }

  @Override
  public void released(RealPerson person, BlockPos target) {
    person.setPose(Pose.STANDING);
  }

  @Override
  public String describe() {
    return "the forge";
  }

  @Override
  public int actEveryTicks() {
    return 20 * FORGE_SECONDS;
  }

  /** The first gear the village is short of and can pay the iron for; null if none. */
  @Nullable
  private Gear nextNeed(Village village) {
    Map<Item, Integer> stock = village.stockTally();
    for (Gear gear : GEAR) {
      if (stock.getOrDefault(gear.output().getItem(), 0) >= gear.keep()) {
        continue;
      }
      if (!village.hasItemStackInVillage(gear.cost())) {
        continue;
      }
      return gear;
    }
    return null;
  }
}
