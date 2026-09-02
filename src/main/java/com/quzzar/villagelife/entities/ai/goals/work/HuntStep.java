package com.quzzar.villagelife.entities.ai.goals.work;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.FarmedStock;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * The hunter's ground: an ATTACK step whose target is an animal, bounded to a
 * radius of the lodge rather than a chase.
 *
 * docs/worker-loops.md is explicit that this is "bounded roaming, not a chase" --
 * pure pursuit would walk a hunter arbitrarily far into danger, so the search is
 * a box around the lodge, not around the hunter, and game that has wandered off
 * the ground is simply not seen. The kill's drops (meat, leather) fall where the
 * animal does and are gathered the same way any dropped item is, which is why
 * this step never touches an inventory itself.
 *
 * The work is done with a bow: draw on one act, loose on the next, from the
 * stand-off distance reachSqr calls arrival. A blocked shot flips the step to
 * stalking -- reach collapses to arm's length and the loop closes the distance
 * on its own -- and a hunter with no bow in hand falls back to the spear-thrust
 * rather than standing idle. Arrows follow the worker-loops rule: the plain
 * fallback is conjured and never counted, special arrows are real and consumed
 * (Person.getProjectile / shootArrowAt). Village stock carrying the farmed mark
 * (FarmedStock) is never game, however identical the species: that is what
 * keeps the butchery's pen safe from its own neighbour.
 */
public final class HuntStep implements WorkStep<Animal> {

  /** How far from the lodge a hunter will range for game. */
  private static final double GROUND_RADIUS = 12.0D;

  /** A shot's stand-off: close enough to hit, far enough to read as archery. */
  private static final double BOW_RANGE_SQR = 100.0D;

  /** Arm's length: the spear-thrust fallback, and the stalking approach. */
  private static final double MELEE_REACH_SQR = 4.0D;

  /** A hunter aims; a skirmisher sprays. Vanilla combat spread is 10 on easy. */
  private static final float HUNTING_INACCURACY = 2.0F;

  /** True while something blocks the shot: reach shrinks and the loop walks in. */
  private boolean stalking;

  /** True between the drawing act and the loosing act. */
  private boolean drawn;

  @Override
  @Nullable
  public Animal select(RealPerson person) {
    ensureBow(person);
    BlockPos lodge = LocationManager.getJobLocation(person);
    if (lodge == BlockPos.ZERO) {
      return null;
    }
    AABB ground = new AABB(lodge).inflate(GROUND_RADIUS, 4.0D, GROUND_RADIUS);
    Animal best = null;
    double nearest = Double.MAX_VALUE;
    for (Animal animal : person.level().getEntitiesOfClass(Animal.class, ground)) {
      if (!isGame(animal) || !animal.isAlive() || animal.isBaby()) {
        continue;
      }
      double distance = person.distanceToSqr(animal);
      if (distance < nearest) {
        nearest = distance;
        best = animal;
      }
    }
    return best;
  }

  @Override
  public BlockPos positionOf(Animal target) {
    return target.blockPosition();
  }

  @Override
  public boolean act(RealPerson person, Animal target) {
    if (!target.isAlive()) {
      return false; // down: its drops fall for pickup, and select finds the next
    }
    // Bounded roaming, actually enforced: select only picks game on the ground, but
    // a beast that bolts off it mid-fight is let go rather than chased arbitrarily far
    // -- the "not a chase" property the loop cannot keep on select's behalf.
    BlockPos lodge = LocationManager.getJobLocation(person);
    if (lodge == BlockPos.ZERO
        || target.blockPosition().distSqr(lodge) > GROUND_RADIUS * GROUND_RADIUS) {
      return false;
    }
    person.getLookControl().setLookAt(target, 30.0F, 30.0F);
    if (!(person.getMainHandItem().getItem() instanceof BowItem)) {
      // Mid-meal, or the bow is simply gone: the spear-thrust keeps the larder
      // filling until ensureBow re-arms them on the next scan.
      person.doHurtTarget(target);
      return target.isAlive();
    }
    if (!person.getSensing().hasLineOfSight(target)) {
      // Something between hunter and quarry: stalk closer rather than loose
      // arrows into a wall. reachSqr collapses until this target is released,
      // and the loop walks the remaining distance on its own.
      stalking = true;
      releaseDraw(person);
      return true;
    }
    if (!drawn) {
      person.startUsingItem(InteractionHand.MAIN_HAND);
      drawn = true;
      return true; // this act was the draw; the next one looses
    }
    releaseDraw(person);
    person.shootArrowAt(target, BowItem.getPowerForTime(actEveryTicks()), HUNTING_INACCURACY);
    return target.isAlive();
  }

  @Override
  public void released(RealPerson person, Animal target) {
    stalking = false;
    releaseDraw(person);
  }

  @Override
  public String describe() {
    return "the hunting ground";
  }

  @Override
  public String activity() {
    return "out hunting";
  }

  /** A full draw between acts: an arrow every other act, none of them rushed. */
  @Override
  public int actEveryTicks() {
    return 20;
  }

  @Override
  public double reachSqr(RealPerson person) {
    if (stalking || !(person.getMainHandItem().getItem() instanceof BowItem)) {
      return MELEE_REACH_SQR;
    }
    return BOW_RANGE_SQR;
  }

  private void releaseDraw(RealPerson person) {
    if (drawn) {
      person.stopUsingItem();
      drawn = false;
    }
  }

  /**
   * The baseline bow, re-granted whenever the main hand is empty: the same
   * conjured basic tool every job starts with (populateDefaultEquipmentSlots),
   * issued here as well so a hunter who wore one out, or who predates the
   * HUNTER case, is never reduced to fists for good.
   */
  private static void ensureBow(RealPerson person) {
    if (person.getMainHandItem().isEmpty() && !person.isEating()) {
      person.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
    }
  }

  private static boolean isGame(Animal animal) {
    // The village's own stock is not game, however identical the species: the
    // farmed mark is written at the pen and read here (FarmedStock).
    return FarmedStock.isStock(animal) && !FarmedStock.isFarmed(animal);
  }
}
