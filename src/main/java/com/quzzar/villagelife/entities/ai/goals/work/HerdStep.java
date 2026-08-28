package com.quzzar.villagelife.entities.ai.goals.work;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * The herder's round on the pasture: shear grown sheep for wool (the CLOTH
 * grant) and put grown stock in the mood to breed, so the pasture "breeds and is
 * genuinely productive" (docs/worker-loops.md) instead of being hunted to
 * nothing. Non-lethal on purpose -- taking meat and leather off the herd is the
 * hunter's job; keeping it alive and multiplying is this one's. Wool falls where
 * the shear happens and is gathered like any dropped item.
 */
public final class HerdStep implements WorkStep<Animal> {

  /** How far around the pasture the herder tends stock. */
  private static final double PASTURE_RADIUS = 12.0D;

  @Override
  @Nullable
  public Animal select(RealPerson person) {
    BlockPos pasture = LocationManager.getJobLocation(person);
    if (pasture == BlockPos.ZERO) {
      return null;
    }
    AABB ground = new AABB(pasture).inflate(PASTURE_RADIUS, 4.0D, PASTURE_RADIUS);
    Animal best = null;
    double nearest = Double.MAX_VALUE;
    for (Animal animal : person.level().getEntitiesOfClass(Animal.class, ground)) {
      if (!animal.isAlive() || !needsTending(person, animal)) {
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
    if (!needsTending(person, target)) {
      return false;
    }
    person.getLookControl().setLookAt(target, 30.0F, 30.0F);
    person.swing(person.getUsedItemHand());
    if (target instanceof Sheep sheep && sheep.readyForShearing()) {
      sheep.shear(SoundSource.PLAYERS); // drops wool where it stands, for pickup
      return false;
    }
    // Otherwise a grown animal worth breeding: spend a little grain to set it
    // courting, and vanilla pairs two that are in love near each other.
    Village village = person.getVillage();
    if (village != null && spendGrain(village)) {
      target.setInLove(null);
    }
    return false;
  }

  @Override
  public String describe() {
    return "the pasture";
  }

  @Override
  public int actEveryTicks() {
    return 20;
  }

  @Override
  public double reachSqr(RealPerson person) {
    return 6.0D;
  }

  private boolean needsTending(RealPerson person, Animal animal) {
    if (animal.isBaby()) {
      return false;
    }
    if (animal instanceof Sheep sheep && sheep.readyForShearing()) {
      return true;
    }
    // A grown animal not already courting is a breeding candidate, but only while
    // there is grain to spend on it.
    return animal.canFallInLove() && hasGrain(person.getVillage());
  }

  private boolean hasGrain(@Nullable Village village) {
    return village != null && village.hasItemStackInVillage(new ItemStack(Items.WHEAT, 1));
  }

  private boolean spendGrain(Village village) {
    if (!village.hasItemStackInVillage(new ItemStack(Items.WHEAT, 1))) {
      return false;
    }
    village.gatherItemStackFromVillage(new ItemStack(Items.WHEAT, 1));
    return true;
  }
}
