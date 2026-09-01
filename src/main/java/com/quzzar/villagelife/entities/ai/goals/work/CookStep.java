package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;

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
 * Idle hands at the fire: an idle camper takes raw food the village has in
 * store, cooks whatever a campfire can cook, and puts the cooked food back
 * (docs/population-and-labor.md, docs/worker-loops.md).
 *
 * The campfire-model twin of the farmer's idle composter chain
 * ({@link CompostStep}), and a CONVERT step deliberately scoped to idle
 * residents as an early-camp bridge: a young camp has no butchery, so the raw
 * meat a hunter brings home would sit uncooked. Once the village builds a
 * butchery its BUTCHER out-produces the fireside, and this quietly matters less
 * without any explicit hand-off - the butcher simply drains the stores first.
 *
 * Physical where it can be: the target is the town's real lit campfire and the
 * raw item genuinely roasts on it via {@link CampfireBlockEntity#placeFood}. The
 * transform itself is read from the vanilla campfire recipe set, so any
 * campfire-cookable item counts, modded ones for free, rather than being
 * hand-listed like the butcher's six meats. This step owns the timing and lifts
 * the cooked food straight into village stores, so the fire's own cook tick
 * never finishes first and drops it on the ground (the liberty CompostStep also
 * takes with its bone meal).
 */
public final class CookStep implements BlockWorkStep {

  /** Ticks a raw item roasts on the fire before it is lifted off cooked. */
  private static final int COOK_TICKS = 20 * 8;

  /**
   * Cook time handed to the campfire block. Far longer than {@link #COOK_TICKS}
   * so the block's own cook tick never finishes a batch before this step does,
   * which is what keeps the cooked food out of the dirt and in the ledger.
   */
  private static final int FIRE_COOK_TICKS = 20 * 60;

  /** The raw items any campfire recipe accepts, resolved once from the recipe set. */
  @Nullable
  private Set<Item> cookableRaws;

  /** The raw item currently roasting on the fire, or null when tending nothing. */
  @Nullable
  private ItemStack roasting;
  private long collectAtTick;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    Village village = person.getVillage();
    if (village == null) {
      return null;
    }
    BlockPos fire = village.getCampfire();
    if (fire == null) {
      return null;
    }
    // Mid-batch: keep the fire as the target until the roast is collected.
    if (this.roasting != null) {
      return fire;
    }
    return findCookableRaw(person, village) == null ? null : fire;
  }

  @Override
  public boolean act(RealPerson person, BlockPos fire) {
    Village village = person.getVillage();
    if (village == null) {
      return false;
    }
    Level level = person.level();
    BlockState state = level.getBlockState(fire);
    if (!state.is(Blocks.CAMPFIRE) || !state.getValue(CampfireBlock.LIT)
        || !(level.getBlockEntity(fire) instanceof CampfireBlockEntity campfire)) {
      // The fire went out or was taken. Hand back anything taken but not yet
      // cooked so the stores stay balanced, then go and select afresh.
      abandonRoast(person, null, null, null, null);
      return false;
    }

    if (this.roasting == null) {
      return startRoast(person, village, campfire, fire);
    }
    return finishRoast(person, village, campfire, fire, state, level);
  }

  /** Take one raw item from stores and set it roasting on the fire. */
  private boolean startRoast(RealPerson person, Village village, CampfireBlockEntity campfire,
      BlockPos fire) {
    if (!hasFreeSlot(campfire)) {
      return true; // fire is full of other cooks; wait rather than pull food out
    }
    Item raw = findCookableRaw(person, village);
    if (raw == null) {
      return false; // stores hold nothing the fire can cook; back to select
    }
    ItemStack taken = village.gatherItemStackFromVillage(new ItemStack(raw, 1));
    if (taken.isEmpty()) {
      return false; // it was there a scan ago and is not now; give up this pass
    }
    if (!campfire.placeFood(person, new ItemStack(raw, 1), FIRE_COOK_TICKS)) {
      // Lost the slot to a race after the free-slot check: hand the raw back.
      village.placeItemStackIntoVillage(taken, person, fire);
      return true;
    }
    person.setPose(Pose.CROUCHING);
    this.roasting = new ItemStack(raw, 1);
    this.collectAtTick = person.tickCount + COOK_TICKS;
    return true;
  }

  /** Once the timer is up, lift the cooked food into stores and clear the fire. */
  private boolean finishRoast(RealPerson person, Village village, CampfireBlockEntity campfire,
      BlockPos fire, BlockState state, Level level) {
    if (person.tickCount < this.collectAtTick) {
      return true; // still roasting
    }
    Item raw = this.roasting.getItem();
    ItemStack cooked = campfire.getCookableRecipe(this.roasting)
        .map(recipe -> recipe.value().getResultItem(level.registryAccess()).copy())
        .orElse(ItemStack.EMPTY);
    this.roasting = null;
    clearFromFire(campfire, fire, state, level, raw);
    person.setPose(Pose.STANDING);
    // Deposit the cooked food, or hand the raw straight back if its recipe
    // vanished on a datapack reload, so the stores lose the item either way.
    ItemStack out = cooked.isEmpty() ? new ItemStack(raw) : cooked;
    return village.placeItemStackIntoVillage(out, person, fire);
  }

  @Override
  public void released(RealPerson person, BlockPos fire) {
    Level level = person.level();
    BlockState state = level.getBlockState(fire);
    CampfireBlockEntity campfire =
        level.getBlockEntity(fire) instanceof CampfireBlockEntity c ? c : null;
    abandonRoast(person, campfire, fire, state, level);
  }

  /**
   * Give up an in-progress roast cleanly: the raw was already taken from stores,
   * so return it, and wipe the cosmetic item off the fire when it is still there.
   */
  private void abandonRoast(RealPerson person, @Nullable CampfireBlockEntity campfire,
      @Nullable BlockPos fire, @Nullable BlockState state, @Nullable Level level) {
    person.setPose(Pose.STANDING);
    if (this.roasting == null) {
      return;
    }
    Village village = person.getVillage();
    Item raw = this.roasting.getItem();
    if (village != null) {
      village.placeItemStackIntoVillage(new ItemStack(raw), person, fire);
    }
    this.roasting = null;
    if (campfire != null && fire != null && state != null && level != null) {
      clearFromFire(campfire, fire, state, level, raw);
    }
  }

  @Override
  public String describe() {
    return "the campfire";
  }

  /** A batch check once a second is plenty; the roast itself is the long wait. */
  @Override
  public int actEveryTicks() {
    return 20;
  }

  /** The fire is the one job that belongs at night, and idle campers keep it. */
  @Override
  public boolean worksAtNight() {
    return true;
  }

  /** The first raw item in the campfire recipe set the village actually holds. */
  @Nullable
  private Item findCookableRaw(RealPerson person, Village village) {
    for (Item raw : cookableRaws(person.level())) {
      if (village.hasItemStackInVillage(new ItemStack(raw))) {
        return raw;
      }
    }
    return null;
  }

  /**
   * Every item a campfire recipe accepts as input, resolved once and kept. A
   * datapack reload is the only thing that changes the set, and the butcher's
   * own recipes are hard-listed anyway, so a cache that outlives a reload is a
   * fair trade for not walking the recipe set on every scan.
   */
  private Set<Item> cookableRaws(Level level) {
    if (this.cookableRaws == null) {
      Set<Item> raws = new HashSet<>();
      for (RecipeHolder<CampfireCookingRecipe> holder
          : level.getRecipeManager().getAllRecipesFor(RecipeType.CAMPFIRE_COOKING)) {
        NonNullList<Ingredient> inputs = holder.value().getIngredients();
        if (inputs.isEmpty()) {
          continue;
        }
        for (ItemStack in : inputs.get(0).getItems()) {
          raws.add(in.getItem());
        }
      }
      this.cookableRaws = raws;
    }
    return this.cookableRaws;
  }

  private boolean hasFreeSlot(CampfireBlockEntity campfire) {
    for (ItemStack stack : campfire.getItems()) {
      if (stack.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /** Wipe the first slot holding this raw item off the fire and sync the change. */
  private void clearFromFire(CampfireBlockEntity campfire, BlockPos fire, BlockState state,
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
