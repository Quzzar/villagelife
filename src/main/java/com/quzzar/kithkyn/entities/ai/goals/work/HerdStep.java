package com.quzzar.kithkyn.entities.ai.goals.work;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.Utils;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.village.FarmedStock;
import com.quzzar.kithkyn.village.LocationManager;
import com.quzzar.kithkyn.village.Occupation;
import com.quzzar.kithkyn.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.Items;

/**
 * The herder's round on the pasture: shear grown sheep for wool (the CLOTH
 * grant) and put grown stock in the mood to breed, so the pasture "breeds and is
 * genuinely productive" (docs/worker-loops.md) instead of being hunted to
 * nothing. Non-lethal on purpose -- taking meat and hide off the herd is the
 * butcher's job ({@link CullStep}); keeping it alive and multiplying is this
 * one's. Wool falls where the shear happens and is gathered like any dropped
 * item.
 *
 * Only farm stock is tended, and grain is only ever spent on an animal that has
 * a breeding partner of its own kind on the ground and whose kind is still
 * under the pen's breeding cap ({@link FarmedStock#BREED_CAP}): a lone animal,
 * an odd one out, a stray wolf, or a kind the pen already has enough of is
 * left alone rather than fed wheat the village gets nothing for. The cap is
 * what stopped a fed pen from breeding until nobody could move in it.
 *
 * <b>The wheat rides in the herder's pack</b>, carried out from a chest by the
 * {@link FetchStep} ahead of this loop (docs/worker-loops.md, "Nothing
 * teleports"): an empty pocket on the pasture means a walk back for more, not
 * grain conjured across the village.
 */
public final class HerdStep implements WorkStep<Animal> {

  /**
   * Whether this worker should be tending the herd this round. The herder always
   * does. A butcher does too, but only while the village has no herder of its
   * own: one worker on the pen fills both halves of the job (breed and shear as
   * well as cull), and the moment a herder is hired the butcher hands the
   * breeding and shearing back and returns to the knife alone (Aaron, 2026-09-03).
   */
  public static boolean tends(RealPerson person) {
    Occupation job = person.getOccupation();
    if (job == Occupation.HERDER) {
      return true;
    }
    if (job != Occupation.BUTCHER) {
      return false;
    }
    Village village = person.getVillage();
    return village != null && !village.hasWorkerOf(Occupation.HERDER);
  }

  @Override
  @Nullable
  public Animal select(RealPerson person) {
    if (!tends(person)) {
      return null;
    }
    BlockPos pasture = LocationManager.getJobLocation(person);
    if (pasture == BlockPos.ZERO) {
      return null;
    }
    Animal best = null;
    double nearest = Double.MAX_VALUE;
    for (Animal animal : person.level().getEntitiesOfClass(Animal.class, FarmedStock.pasture(pasture))) {
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
    // Tending is adoption: whatever the herder works on is the village's herd.
    // Back-fills pens that predate the farmed mark, and claims wild arrivals
    // the herder takes in -- either way the hunter stops seeing it as game.
    FarmedStock.mark(target);
    person.getLookControl().setLookAt(target, 30.0F, 30.0F);
    person.swing(person.getUsedItemHand());
    if (target instanceof Sheep sheep && sheep.readyForShearing()) {
      sheep.shear(SoundSource.PLAYERS); // drops wool where it stands, for pickup
      return false;
    }
    // Otherwise a grown animal with a partner worth breeding: spend a little grain
    // from the pack to set it courting, and vanilla pairs two that are in love
    // near each other.
    if (spendGrain(person)) {
      target.setInLove(null);
    }
    return false;
  }

  @Override
  public String describe() {
    return "the pasture";
  }

  @Override
  public String activity() {
    return "tending the animals in the pasture";
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
    if (!FarmedStock.isStock(animal) || animal.isBaby()) {
      return false;
    }
    if (animal instanceof Sheep sheep && sheep.readyForShearing()) {
      return true;
    }
    // A grown animal is worth walking to only when there is grain to spend AND a
    // partner of its own kind to spend it on AND room in the pen for the young
    // -- otherwise the wheat is wasted on an animal that cannot breed, or on a
    // calf the butcher would take the moment it grew.
    BlockPos pasture = LocationManager.getJobLocation(person);
    return pasture != BlockPos.ZERO && animal.canFallInLove() && hasGrain(person)
        && !FarmedStock.atBreedCap(person.level(), pasture, animal)
        && hasBreedingPartner(person, animal);
  }

  private boolean hasBreedingPartner(RealPerson person, Animal animal) {
    BlockPos pasture = LocationManager.getJobLocation(person);
    if (pasture == BlockPos.ZERO) {
      return false;
    }
    for (Animal other : person.level().getEntitiesOfClass(Animal.class, FarmedStock.pasture(pasture))) {
      if (other != animal && other.getClass() == animal.getClass() && other.canFallInLove()) {
        return true;
      }
    }
    return false;
  }

  /** Wheat in the herder's own pack; the fetch trip keeps it stocked. */
  private boolean hasGrain(RealPerson person) {
    return PackLogistics.carried(person, Items.WHEAT) > 0;
  }

  private boolean spendGrain(RealPerson person) {
    return Utils.removeItem(person.personMainInv, Items.WHEAT, 1).getCount() == 1;
  }
}
