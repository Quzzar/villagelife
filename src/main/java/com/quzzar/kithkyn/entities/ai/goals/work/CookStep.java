package com.quzzar.kithkyn.entities.ai.goals.work;

import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;

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
 * chest into the cook's pack, genuinely roasts on the town's lit campfire, and
 * the cooked food is carried back to a chest by hand (docs/worker-loops.md,
 * "Nothing teleports"). The roasting itself is {@link CampfireRoast}, shared
 * with the roaming wanderer's own camp; this step owns only the chest trips
 * either side of it. The step alternates its target the way {@link GatherStep}
 * does: a chest while the pack is short or holds finished goods, the station
 * while there is a batch in hand.
 */
public final class CookStep implements BlockWorkStep {
  /** Raw items carried per fetch trip: a campfire's four slots' worth. */
  private static final int RAWS_PER_TRIP = 4;

  private final CampfireRoast roast = new CampfireRoast();

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
    if (this.roast.tending()) {
      return fire;
    }
    // Raw in the pack: to the fire.
    if (this.roast.rawInPack(person) != null) {
      return fire;
    }
    // Otherwise a chest: one holding raw food, or failing that, one with room
    // for the cooked food still in the pack.
    BlockPos source = chestWithRaw(person, village);
    if (source != null) {
      return source;
    }
    Item cooked = this.roast.cookedInPack(person);
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
    CampfireBlockEntity campfire = CampfireRoast.litFireAt(person.level(), target);
    if (campfire == null) {
      // The fire went out or was taken. The raw mid-roast goes back into the
      // pack so nothing is lost, then go and select afresh.
      this.roast.abandon(person, null);
      return false;
    }
    if (!this.roast.tending()) {
      if (!CampfireRoast.hasFreeSlot(campfire)) {
        return true; // fire is full of other cooks; wait rather than pull food out
      }
      if (this.roast.rawInPack(person) == null) {
        return false; // pack is empty of raw food; back to select for a chest
      }
      this.roast.start(person, campfire); // a slot lost to a race is simply tried again
      return true;
    }
    if (!this.roast.done(person)) {
      return true; // still roasting
    }
    this.roast.finish(person, campfire, target);
    return true;
  }

  /** Shelve the cooked food carried, then load up raw food, in one visit. */
  private boolean visitChest(RealPerson person, Container chest) {
    Item cooked = this.roast.cookedInPack(person);
    if (cooked != null) {
      PackLogistics.depositCarried(person, chest, cooked, "COOK");
    }
    int carriedRaw = this.roast.countRawInPack(person);
    for (Item raw : this.roast.cookableRaws(person.level())) {
      if (carriedRaw >= RAWS_PER_TRIP) {
        break;
      }
      carriedRaw += PackLogistics.pullWanted(person, chest,
          List.of(new ItemStack(raw, RAWS_PER_TRIP - carriedRaw)), "COOK");
    }
    return false; // re-select: the fire if loaded, another chest if not
  }

  @Override
  public void released(RealPerson person, BlockPos fire) {
    this.roast.abandon(person, fire);
  }

  @Override
  public String describe() {
    return "the campfire";
  }

  @Override
  public String activity() {
    return "cooking at the campfire";
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

  /** The nearest chest holding any raw the fire can cook, or null. */
  @Nullable
  private BlockPos chestWithRaw(RealPerson person, Village village) {
    List<ItemStack> wanted = this.roast.cookableRaws(person.level()).stream()
        .map(raw -> new ItemStack(raw, RAWS_PER_TRIP))
        .toList();
    return wanted.isEmpty() ? null : PackLogistics.chestHolding(person, village, wanted);
  }
}
