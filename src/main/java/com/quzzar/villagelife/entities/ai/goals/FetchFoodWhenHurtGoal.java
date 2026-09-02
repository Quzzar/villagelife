package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.ai.goals.work.PackLogistics;
import com.quzzar.villagelife.village.PersonalChest;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * A badly hurt villager with nothing to eat goes and gets some: the nearest
 * chest holding a meal, in the village stores or their own chest at home,
 * whichever is closer. They take a few bites' worth into the pack and
 * {@link PersonEatFoodGoal} does the rest. Nothing teleports: the food is
 * carried from a real chest, and a villager with no reachable food heals the
 * slow way, two health every ten seconds.
 *
 * <p>Not under fire: a villager with a target leaves this to the fighters'
 * retreat, and the trip is bounded so a chest that cannot be reached is given
 * up rather than walked toward all night.
 */
public class FetchFoodWhenHurtGoal extends Goal {

  /** Bites taken from the chest: enough to get well over the line in one sitting. */
  private static final int BITES = 4;
  /** Goal ticks (every other server tick) before the trip is given up: about a minute. */
  private static final int GIVE_UP_TICKS = 600;
  /** Close enough to reach into the chest, squared. */
  private static final double REACH_SQR = 9.0D;

  private final RealPerson person;
  private BlockPos chest;
  private int ticks;

  public FetchFoodWhenHurtGoal(RealPerson person) {
    this.setFlags(EnumSet.of(Flag.MOVE));
    this.person = person;
  }

  @Override
  public boolean canUse() {
    if (person.getHealth() >= person.getMaxHealth() / 3 || person.hasMeal()
        || person.getTarget() != null || person.isAggressive() || person.getVillage() == null) {
      return false;
    }
    this.chest = nearestMeal(person.getVillage());
    return this.chest != null;
  }

  @Override
  public boolean canContinueToUse() {
    return person.getHealth() < (person.getMaxHealth() / 3) + 2 && !person.hasMeal()
        && person.getTarget() == null && this.ticks < GIVE_UP_TICKS;
  }

  @Override
  public void start() {
    this.ticks = 0;
    walk();
  }

  @Override
  public void tick() {
    this.ticks++;
    if (person.position().distanceToSqr(Vec3.atCenterOf(this.chest)) <= REACH_SQR) {
      person.getNavigation().stop();
      takeBites();
      return;
    }
    if (!person.getNavigation().isInProgress()) {
      walk();
    }
  }

  @Override
  public void stop() {
    if (this.ticks >= GIVE_UP_TICKS && !person.hasMeal()) {
      Villagelife.LOGGER.info("'{}' could not reach any food while hurt; healing the slow way", person.getFullName());
    }
  }

  private void walk() {
    person.getNavigation().moveTo(this.chest.getX(), this.chest.getY(), this.chest.getZ(), 0.6D);
  }

  /**
   * The nearest chest with a meal in it: the closest village chest holding
   * one, or the villager's own chest at home when that is nearer and holds
   * one. Null when nowhere in reach has food.
   */
  @Nullable
  private BlockPos nearestMeal(Village village) {
    BlockPos shared = PackLogistics.chestWhere(person, village, Person::isMeal);
    BlockPos own = PersonalChest.of(person);
    if (own != null) {
      Container container = PersonalChest.container(person, own);
      boolean stocked = container != null && PackLogistics.holdsAny(container, Person::isMeal);
      if (stocked && (shared == null || own.distSqr(person.blockPosition()) < shared.distSqr(person.blockPosition()))) {
        return own;
      }
    }
    return shared;
  }

  /** A few bites out of the first meal in the chest, into the pack. */
  private void takeBites() {
    Container container = PackLogistics.containerAt(person, this.chest);
    if (container == null) {
      return; // gone from under them; canUse looks again next tick
    }
    for (int slot = 0; slot < container.getContainerSize(); slot++) {
      ItemStack stack = container.getItem(slot);
      if (Person.isMeal(stack)) {
        ItemStack taken = stack.split(Math.min(BITES, stack.getCount()));
        container.setChanged();
        person.addItems(List.of(taken));
        Villagelife.LOGGER.info("'{}' is hurt and took {} {} from a chest to eat", person.getFullName(),
            taken.getCount(), taken.getItem().getDescription().getString().toLowerCase(java.util.Locale.ROOT));
        return;
      }
    }
  }
}
