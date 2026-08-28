package com.quzzar.villagelife.entities.ai.goals.work;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
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
 */
public final class HuntStep implements WorkStep<Animal> {

  /** How far from the lodge a hunter will range for game. */
  private static final double GROUND_RADIUS = 12.0D;

  @Override
  @Nullable
  public Animal select(RealPerson person) {
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
    person.swing(person.getUsedItemHand());
    person.doHurtTarget(target);
    return target.isAlive();
  }

  @Override
  public String describe() {
    return "the hunting ground";
  }

  /** A blow every second or so, not every tick: a hunter is not a blender. */
  @Override
  public int actEveryTicks() {
    return 12;
  }

  /** Arm's length: a spear-thrust, not the cleric's lob. */
  @Override
  public double reachSqr(RealPerson person) {
    return 4.0D;
  }

  private static boolean isGame(Animal animal) {
    return animal instanceof Cow || animal instanceof Pig
        || animal instanceof Sheep || animal instanceof Chicken;
  }
}
