package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.FarmedStock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * A roaming wanderer's larder: game met on the road, taken with whatever is
 * in hand (docs/population-and-labor.md, "The road"). There is no hunger, so
 * meat is for the hurt day: the pack is kept to a few bites, and a wanderer
 * carrying that many walks past the cows.
 *
 * <p>The ground is around the wanderer rather than a lodge, a dozen blocks
 * either side of wherever the day's heading has brought them, and it is a
 * stop and not a chase: a beast that bolts well off the road is let go.
 * Village stock carrying the farmed mark is never game, the same rule the
 * hunter keeps, so a wanderer passing a butchery's pen leaves it alone. The
 * kill's drops fall where the animal does, and the wanderer standing over it
 * pockets them; nothing is conjured and nothing is fetched from afar.
 */
public final class ForageHuntStep implements WorkStep<Animal> {
  /** Bites a wanderer keeps in the pack; below it, game within range is worth the stop. */
  private static final int MEAL_TARGET = 4;
  /** How far off the road a wanderer goes for game: the hunter's ground, around themselves. */
  private static final double RANGE = 12.0D;
  /** Arm's length, squared. */
  private static final double REACH_SQR = 4.0D;
  /** A beast that gets this far away mid-fight is let go: the road is not a chase. */
  private static final double CHASE_SQR = 24.0D * 24.0D;
  /** How far around the fallen animal its drops are gathered from. */
  private static final double GATHER_RADIUS = 2.5D;

  @Override
  @Nullable
  public Animal select(RealPerson person) {
    if (!person.isRoamingWanderer() || person.mealsCarried() >= MEAL_TARGET || person.isInventoryFull()) {
      return null;
    }
    AABB ground = person.getBoundingBox().inflate(RANGE, 4.0D, RANGE);
    Animal best = null;
    double nearest = Double.MAX_VALUE;
    for (Animal animal : person.level().getEntitiesOfClass(Animal.class, ground)) {
      if (!animal.isAlive() || animal.isBaby() || !FarmedStock.isStock(animal) || FarmedStock.isFarmed(animal)) {
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
    if (target.isAlive()) {
      if (person.distanceToSqr(target) > CHASE_SQR) {
        return false; // gone off the road; the next scan finds nearer game, or none
      }
      person.getLookControl().setLookAt(target, 30.0F, 30.0F);
      person.doHurtTarget(target);
      if (!target.isAlive()) {
        Villagelife.LOGGER.info("[road] '{}' brought down a {}", person.getFullName(),
            target.getName().getString().toLowerCase(Locale.ROOT));
      }
      return true;
    }
    // Down. Its drops lie where it fell, and the wanderer is standing over it.
    // Usually the ordinary pickup has them already (a villager picks up what
    // touches them, the way any mob with CanPickUpLoot does); this is for
    // whatever bounced out of reach of that.
    List<ItemStack> taken = new ArrayList<>();
    for (ItemEntity drop : person.level().getEntitiesOfClass(ItemEntity.class,
        target.getBoundingBox().inflate(GATHER_RADIUS))) {
      if (!drop.isAlive()) {
        continue;
      }
      taken.add(drop.getItem().copy());
      drop.discard();
    }
    if (!taken.isEmpty()) {
      person.addItems(taken);
      Villagelife.LOGGER.info("[road] '{}' picked up {} off the ground", person.getFullName(), describe(taken));
    }
    return false;
  }

  @Override
  public String describe() {
    return "game on the road";
  }

  @Override
  public String activity() {
    return "hunting for the pot";
  }

  /** A blow a second, the way a fist lands. */
  @Override
  public int actEveryTicks() {
    return 20;
  }

  @Override
  public double reachSqr(RealPerson person) {
    return REACH_SQR;
  }

  private static String describe(List<ItemStack> parts) {
    List<String> words = new ArrayList<>();
    for (ItemStack part : parts) {
      words.add(part.getCount() + " " + part.getHoverName().getString().toLowerCase(Locale.ROOT));
    }
    return String.join(", ", words);
  }
}
