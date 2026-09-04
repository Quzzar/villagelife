package com.quzzar.kithkyn.entities.ai.goals.work;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.Utils;
import com.quzzar.kithkyn.entities.RealPerson;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One roast at a time on a lit campfire, out of a person's own pack and back
 * into it: the fire-tending half that the idle camper's {@link CookStep} and
 * the roaming wanderer's {@link CampStep} share, so a campfire cooks the same
 * way whoever crouches at it (docs/worker-loops.md, "The idle camper tends the
 * fire"). Where the raw food comes from before the pack, and where the cooked
 * food goes after it, is each step's own business.
 *
 * <p>The raw item genuinely roasts on the fire via
 * {@link CampfireBlockEntity#placeFood}; what counts as cookable is read from
 * the vanilla campfire recipe set, so any campfire-cookable item counts and
 * modded ones come along for free. This helper owns the timing and lifts the
 * cooked food straight into the pack, so the fire's own cook tick never
 * finishes first and drops it on the ground. One instance per step, because
 * one person tends one roast at a time.
 */
public final class CampfireRoast {
  /** Ticks a raw item roasts on the fire before it is lifted off cooked. */
  private static final int COOK_TICKS = 20 * 8;
  /**
   * Cook time handed to the campfire block. Far longer than {@link #COOK_TICKS}
   * so the block's own cook tick never finishes a batch before this does,
   * which is what keeps the cooked food out of the dirt and in the pack.
   */
  private static final int FIRE_COOK_TICKS = 20 * 60;

  /** The raw items any campfire recipe accepts, resolved once from the recipe set. */
  @Nullable
  private Set<Item> cookableRaws;
  /** What those recipes produce, so a cook knows their own output. */
  @Nullable
  private Set<Item> cookedResults;
  /** The raw item currently roasting on the fire, or null when tending nothing. */
  @Nullable
  private ItemStack roasting;
  private long collectAtTick;

  /** Whether a roast is on the fire right now. */
  public boolean tending() {
    return this.roasting != null;
  }

  /** The lit campfire at this position, or null: an unlit or missing fire cooks nothing. */
  @Nullable
  public static CampfireBlockEntity litFireAt(Level level, BlockPos pos) {
    BlockState state = level.getBlockState(pos);
    if (!state.is(Blocks.CAMPFIRE) || !state.getValue(CampfireBlock.LIT)) {
      return null;
    }
    return level.getBlockEntity(pos) instanceof CampfireBlockEntity campfire ? campfire : null;
  }

  public static boolean hasFreeSlot(CampfireBlockEntity campfire) {
    for (ItemStack stack : campfire.getItems()) {
      if (stack.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Take one raw item from the pack and set it roasting. True when one is now
   * on the fire; false when the pack holds nothing raw, the fire is full, or
   * the slot was lost to a race, in which case the raw is back in the pack.
   */
  public boolean start(RealPerson person, CampfireBlockEntity campfire) {
    Item raw = rawInPack(person);
    if (raw == null || !hasFreeSlot(campfire)) {
      return false;
    }
    ItemStack taken = Utils.removeItem(person.personMainInv, raw, 1);
    if (taken.isEmpty()) {
      return false;
    }
    if (!campfire.placeFood(person, new ItemStack(raw, 1), FIRE_COOK_TICKS)) {
      Utils.insertItems(person.personMainInv, List.of(taken), person);
      return false;
    }
    person.setPose(Pose.CROUCHING);
    this.roasting = new ItemStack(raw, 1);
    this.collectAtTick = person.tickCount + COOK_TICKS;
    return true;
  }

  /** Whether the roast on the fire has had its time. */
  public boolean done(RealPerson person) {
    return this.roasting != null && person.tickCount >= this.collectAtTick;
  }

  /** Lift the cooked food into the pack and clear the fire. */
  public void finish(RealPerson person, CampfireBlockEntity campfire, BlockPos fire) {
    if (this.roasting == null) {
      return;
    }
    Level level = person.level();
    BlockState state = level.getBlockState(fire);
    Item raw = this.roasting.getItem();
    ItemStack cooked = campfire.getCookableRecipe(this.roasting)
        .map(recipe -> recipe.value().getResultItem(level.registryAccess()).copy())
        .orElse(ItemStack.EMPTY);
    this.roasting = null;
    clearFromFire(campfire, fire, state, level, raw);
    person.setPose(Pose.STANDING);
    // Into the pack, or the raw straight back if its recipe vanished on a
    // datapack reload.
    ItemStack out = cooked.isEmpty() ? new ItemStack(raw) : cooked;
    Utils.insertItems(person.personMainInv, List.of(out), person);
  }

  /**
   * Give up an in-progress roast cleanly: the raw came out of the pack, so it
   * goes back in, and the cosmetic item is wiped off the fire when the fire is
   * still there. Safe to call with no roast on.
   */
  public void abandon(RealPerson person, @Nullable BlockPos fire) {
    person.setPose(Pose.STANDING);
    if (this.roasting == null) {
      return;
    }
    Item raw = this.roasting.getItem();
    Utils.insertItems(person.personMainInv, List.of(new ItemStack(raw)), person);
    this.roasting = null;
    Level level = person.level();
    if (fire != null && level.getBlockEntity(fire) instanceof CampfireBlockEntity campfire) {
      clearFromFire(campfire, fire, level.getBlockState(fire), level, raw);
    }
  }

  /** The first campfire-cookable raw item in the pack, or null. */
  @Nullable
  public Item rawInPack(RealPerson person) {
    for (Item raw : cookableRaws(person.level())) {
      if (PackLogistics.carried(person, raw) > 0) {
        return raw;
      }
    }
    return null;
  }

  public int countRawInPack(RealPerson person) {
    int count = 0;
    for (Item raw : cookableRaws(person.level())) {
      count += PackLogistics.carried(person, raw);
    }
    return count;
  }

  /** The first cooked result in the pack, or null. */
  @Nullable
  public Item cookedInPack(RealPerson person) {
    cookableRaws(person.level()); // resolves cookedResults alongside
    for (Item cooked : this.cookedResults) {
      if (PackLogistics.carried(person, cooked) > 0) {
        return cooked;
      }
    }
    return null;
  }

  /**
   * Every item a campfire recipe accepts as input, resolved once and kept,
   * with the matching results alongside. A datapack reload is the only thing
   * that changes the set, so a cache that outlives a reload is a fair trade
   * for not walking the recipe set on every scan.
   */
  public Set<Item> cookableRaws(Level level) {
    if (this.cookableRaws == null) {
      Set<Item> raws = new HashSet<>();
      Set<Item> results = new HashSet<>();
      for (RecipeHolder<CampfireCookingRecipe> holder
          : level.getRecipeManager().getAllRecipesFor(RecipeType.CAMPFIRE_COOKING)) {
        NonNullList<Ingredient> inputs = holder.value().getIngredients();
        if (inputs.isEmpty()) {
          continue;
        }
        for (ItemStack in : inputs.get(0).getItems()) {
          raws.add(in.getItem());
        }
        results.add(holder.value().getResultItem(level.registryAccess()).getItem());
      }
      this.cookableRaws = raws;
      this.cookedResults = results;
    }
    return this.cookableRaws;
  }

  /** Wipe the first slot holding this raw item off the fire and sync the change. */
  private static void clearFromFire(CampfireBlockEntity campfire, BlockPos fire, BlockState state,
      Level level, Item raw) {
    NonNullList<ItemStack> items = campfire.getItems();
    for (int i = 0; i < items.size(); i++) {
      if (items.get(i).is(raw)) {
        items.set(i, ItemStack.EMPTY);
        campfire.setChanged();
        level.sendBlockUpdated(fire, state, state, 3);
        return;
      }
    }
  }
}
