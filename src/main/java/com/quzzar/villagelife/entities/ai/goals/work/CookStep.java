package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
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
 * <b>Physical all the way through.</b> The raw food is fetched from a real
 * chest into the cook's pack, genuinely roasts on the town's lit campfire via
 * {@link CampfireBlockEntity#placeFood}, and the cooked food is carried back to
 * a chest by hand (docs/worker-loops.md, "Nothing teleports"). The transform
 * itself is read from the vanilla campfire recipe set, so any campfire-cookable
 * item counts, modded ones for free, rather than being hand-listed like the
 * butcher's six meats. This step owns the timing and lifts the cooked food
 * straight into the cook's pack, so the fire's own cook tick never finishes
 * first and drops it on the ground (the liberty CompostStep also takes with its
 * bone meal).
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

  /** Raw items carried per fetch trip: a campfire's four slots' worth. */
  private static final int RAWS_PER_TRIP = 4;

  /** The raw items any campfire recipe accepts, resolved once from the recipe set. */
  @Nullable
  private Set<Item> cookableRaws;

  /** What those recipes produce, so the cook knows their own output to shelve. */
  @Nullable
  private Set<Item> cookedResults;

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
    // Raw in the pack: to the fire.
    if (rawInPack(person) != null) {
      return fire;
    }
    // Otherwise a chest: one holding raw food, or failing that, one with room
    // for the cooked food still in the pack.
    BlockPos source = chestWithRaw(person, village);
    if (source != null) {
      return source;
    }
    Item cooked = cookedInPack(person);
    if (cooked != null) {
      return PackLogistics.chestWithRoomFor(person, village, new ItemStack(cooked));
    }
    return null;
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    Village village = person.getVillage();
    if (village == null) {
      return false;
    }
    Container chest = PackLogistics.containerAt(person, target);
    if (chest != null) {
      return visitChest(person, chest);
    }
    Level level = person.level();
    BlockState state = level.getBlockState(target);
    if (!state.is(Blocks.CAMPFIRE) || !state.getValue(CampfireBlock.LIT)
        || !(level.getBlockEntity(target) instanceof CampfireBlockEntity campfire)) {
      // The fire went out or was taken. The raw mid-roast goes back into the
      // pack so nothing is lost, then go and select afresh.
      abandonRoast(person, null, null, null, null);
      return false;
    }

    if (this.roasting == null) {
      return startRoast(person, campfire, target);
    }
    return finishRoast(person, campfire, target, state, level);
  }

  /** Shelve the cooked food carried, then load up raw food, in one visit. */
  private boolean visitChest(RealPerson person, Container chest) {
    Item cooked = cookedInPack(person);
    if (cooked != null) {
      PackLogistics.depositCarried(person, chest, cooked, "COOK");
    }
    int carriedRaw = countRawInPack(person);
    for (Item raw : cookableRaws(person.level())) {
      if (carriedRaw >= RAWS_PER_TRIP) {
        break;
      }
      carriedRaw += PackLogistics.pullWanted(person, chest,
          List.of(new ItemStack(raw, RAWS_PER_TRIP - carriedRaw)), "COOK");
    }
    return false; // re-select: the fire if loaded, another chest if not
  }

  /** Take one raw item from the pack and set it roasting on the fire. */
  private boolean startRoast(RealPerson person, CampfireBlockEntity campfire, BlockPos fire) {
    if (!hasFreeSlot(campfire)) {
      return true; // fire is full of other cooks; wait rather than pull food out
    }
    Item raw = rawInPack(person);
    if (raw == null) {
      return false; // pack is empty of raw food; back to select
    }
    ItemStack taken = Utils.removeItem(person.personMainInv, raw, 1);
    if (taken.isEmpty()) {
      return false;
    }
    if (!campfire.placeFood(person, new ItemStack(raw, 1), FIRE_COOK_TICKS)) {
      // Lost the slot to a race after the free-slot check: back into the pack.
      Utils.insertItems(person.personMainInv, List.of(taken), person);
      return true;
    }
    person.setPose(Pose.CROUCHING);
    this.roasting = new ItemStack(raw, 1);
    this.collectAtTick = person.tickCount + COOK_TICKS;
    return true;
  }

  /** Once the timer is up, lift the cooked food into the pack and clear the fire. */
  private boolean finishRoast(RealPerson person, CampfireBlockEntity campfire,
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
    // Into the pack, or the raw straight back if its recipe vanished on a
    // datapack reload; the carry home happens on the next select either way.
    ItemStack out = cooked.isEmpty() ? new ItemStack(raw) : cooked;
    Utils.insertItems(person.personMainInv, List.of(out), person);
    return true;
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
   * Give up an in-progress roast cleanly: the raw came out of the pack, so it
   * goes back in, and the cosmetic item is wiped off the fire when still there.
   */
  private void abandonRoast(RealPerson person, @Nullable CampfireBlockEntity campfire,
      @Nullable BlockPos fire, @Nullable BlockState state, @Nullable Level level) {
    person.setPose(Pose.STANDING);
    if (this.roasting == null) {
      return;
    }
    Item raw = this.roasting.getItem();
    Utils.insertItems(person.personMainInv, List.of(new ItemStack(raw)), person);
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

  /** The first campfire-cookable raw item in the cook's own pack, or null. */
  @Nullable
  private Item rawInPack(RealPerson person) {
    for (Item raw : cookableRaws(person.level())) {
      if (PackLogistics.carried(person, raw) > 0) {
        return raw;
      }
    }
    return null;
  }

  private int countRawInPack(RealPerson person) {
    int count = 0;
    for (Item raw : cookableRaws(person.level())) {
      count += PackLogistics.carried(person, raw);
    }
    return count;
  }

  /** The first cooked result in the pack still owed to a chest, or null. */
  @Nullable
  private Item cookedInPack(RealPerson person) {
    cookableRaws(person.level()); // resolves cookedResults alongside
    for (Item cooked : this.cookedResults) {
      if (PackLogistics.carried(person, cooked) > 0) {
        return cooked;
      }
    }
    return null;
  }

  /** The nearest chest holding any raw the fire can cook, or null. */
  @Nullable
  private BlockPos chestWithRaw(RealPerson person, Village village) {
    List<ItemStack> wanted = cookableRaws(person.level()).stream()
        .map(raw -> new ItemStack(raw, RAWS_PER_TRIP))
        .toList();
    return wanted.isEmpty() ? null : PackLogistics.chestHolding(person, village, wanted);
  }

  /**
   * Every item a campfire recipe accepts as input, resolved once and kept,
   * with the matching results alongside. A datapack reload is the only thing
   * that changes the set, and the butcher's own recipes are hard-listed anyway,
   * so a cache that outlives a reload is a fair trade for not walking the
   * recipe set on every scan.
   */
  private Set<Item> cookableRaws(Level level) {
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
