package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * Low-priority litter pickup shared by every person, regardless of age or
 * occupation. A person only starts a pickup while otherwise free, but once
 * they choose an item they finish walking to it instead of rerolling the
 * random start gate every tick.
 */
public class SearchForItemsGoal extends Goal {

  private static final double SEARCH_RANGE = 8.0D;
  private static final double SPEED_MODIFIER = 0.5D;
  private static final int START_CHECK_INTERVAL = 10;
  private static final int REPATH_INTERVAL = 20;
  private static final int GIVE_UP_TICKS = 200;

  private final RealPerson person;
  private final Predicate<ItemEntity> visibleItem = this::canSeeAndCollect;

  private ItemEntity target;
  private int ticksTrying;

  public SearchForItemsGoal(RealPerson person) {
    this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    this.person = person;
  }

  @Override
  public boolean canUse() {
    if (shouldInterrupt()
        || person.getRandom().nextInt(reducedTickDelay(START_CHECK_INTERVAL)) != 0) {
      return false;
    }
    this.target = nearestItem();
    return this.target != null;
  }

  @Override
  public boolean canContinueToUse() {
    return !shouldInterrupt()
        && this.target != null
        && canCollect(this.target)
        && this.ticksTrying < GIVE_UP_TICKS
        && person.distanceToSqr(this.target) <= SEARCH_RANGE * SEARCH_RANGE;
  }

  @Override
  public boolean requiresUpdateEveryTick() {
    return true;
  }

  @Override
  public void start() {
    this.ticksTrying = 0;
    if (this.target == null || !person.getNavigation().moveTo(this.target, SPEED_MODIFIER)) {
      this.ticksTrying = GIVE_UP_TICKS;
    }
  }

  @Override
  public void stop() {
    this.target = null;
    person.getNavigation().stop();
  }

  @Override
  public void tick() {
    this.ticksTrying++;
    if (this.target != null && this.ticksTrying % REPATH_INTERVAL == 0) {
      person.getNavigation().moveTo(this.target, SPEED_MODIFIER);
    }
  }

  private ItemEntity nearestItem() {
    List<ItemEntity> items = person.level().getEntitiesOfClass(ItemEntity.class,
        person.getBoundingBox().inflate(SEARCH_RANGE), visibleItem);
    ItemEntity nearest = null;
    double nearestDistance = Double.MAX_VALUE;
    for (ItemEntity item : items) {
      double distance = person.distanceToSqr(item);
      if (distance < nearestDistance) {
        nearest = item;
        nearestDistance = distance;
      }
    }
    return nearest;
  }

  private boolean canCollect(ItemEntity item) {
    return item.isAlive()
        && !item.hasPickUpDelay()
        && !item.getItem().isEmpty()
        && person.personMainInv.canAddItem(item.getItem());
  }

  private boolean canSeeAndCollect(ItemEntity item) {
    return canCollect(item) && person.hasLineOfSight(item);
  }

  private boolean shouldInterrupt() {
    return this.person.getTarget() != null
        || this.person.isImmobile()
        || this.person.isSleeping()
        || this.person.isFreezing()
        || this.person.isOnFire()
        || this.person.isInterrupted();
  }
}
