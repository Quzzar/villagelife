package com.quzzar.villagelife.entities.ai.goals.work;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;

/**
 * The fisher cooking the day's catch: a CONVERT step like the idle camper's
 * {@link CookStep}, but the raw comes from the fisher's own pack (what they just
 * caught) rather than a chest, and the cooked fish rides home in the pack for
 * the bedtime stow rather than being shelved here. The fire-tending itself is
 * {@link CampfireRoast}, shared with the camper and the roaming wanderer, so a
 * fisher roasts a cod exactly the way anyone crouches at the fire
 * (docs/worker-loops.md, "The idle camper tends the fire").
 *
 * <p>A trip to the fire is worth making for a batch, not one fish: the catch is
 * left to build to {@link #BATCH} before the fisher carries it over, then the
 * whole lot is cooked down before they go back to the water. This runs at a
 * higher priority than {@link FishStep}, so the batch is seen through before the
 * next cast; it costs little, since one catch every twenty seconds rarely
 * outruns the fire. What does not reach a batch by nightfall stows raw and the
 * camp's idle cooks finish it.
 */
public final class FishCookStep implements BlockWorkStep {

  /** Raw fish worth a walk to the fire: a campfire's four slots, and one over. */
  private static final int BATCH = 5;

  private final CampfireRoast roast = new CampfireRoast();

  /**
   * Once the catch is worth cooking, cook it all down before fishing again.
   * The hysteresis is the point: without it the fisher would leave the water
   * for the fire after the very first fish, and again after every one.
   */
  private boolean cooking;

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
    if (this.roast.tending()) {
      return fire; // mid-roast: see this one off the fire before anything else
    }
    int raw = this.roast.countRawInPack(person);
    if (raw == 0) {
      this.cooking = false;
      return null; // nothing to cook: back to the water
    }
    if (raw >= BATCH) {
      this.cooking = true;
    }
    return this.cooking ? fire : null;
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    CampfireBlockEntity campfire = CampfireRoast.litFireAt(person.level(), target);
    if (campfire == null) {
      // The fire went out or was taken: the raw mid-roast goes back in the pack.
      this.roast.abandon(person, null);
      return false;
    }
    if (!this.roast.tending()) {
      if (!CampfireRoast.hasFreeSlot(campfire)) {
        return true; // the fire is full of other cooks; wait rather than shove in
      }
      if (this.roast.rawInPack(person) == null) {
        return false; // the batch is cooked: back to select, and to the water
      }
      this.roast.start(person, campfire); // a slot lost to a race is simply tried again
      return true;
    }
    if (!this.roast.done(person)) {
      return true; // still roasting
    }
    this.roast.finish(person, campfire, target);
    return true; // lift it off, then the next raw goes on
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
    return "cooking the day's catch";
  }

  /** A batch check once a second is plenty; the roast itself is the long wait. */
  @Override
  public int actEveryTicks() {
    return 20;
  }
}
