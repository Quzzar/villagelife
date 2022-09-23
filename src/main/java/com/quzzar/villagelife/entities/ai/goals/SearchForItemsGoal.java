package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import java.util.List;

import com.google.common.base.Predicate;
import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;

public class SearchForItemsGoal extends Goal {

  // TODO, make this a list of needed items in the village.
  final Predicate<ItemEntity> ALLOWED_ITEMS = (itemEntity) -> {
    return !itemEntity.hasPickUpDelay() && itemEntity.isAlive();
  };

  protected RealPerson person;

  public SearchForItemsGoal(RealPerson person) {
    this.setFlags(EnumSet.of(Goal.Flag.MOVE));

    this.person = person;

  }

  @Override
  public boolean canUse() {
    if (person.getTarget() == null && person.getLastHurtByMob() == null) {
      if (person.isImmobile()) {
        return false;
      } else if (shouldInterrupt()) {
        return false;
      } else if (person.getRandom().nextInt(reducedTickDelay(10)) != 0) {
        return false;
      } else {
        List<ItemEntity> list = person.level.getEntitiesOfClass(ItemEntity.class,
            person.getBoundingBox().inflate(8.0D, 8.0D, 8.0D), ALLOWED_ITEMS);
        return !list.isEmpty();
      }
    } else {
      return false;
    }
  }

  @Override
  public void tick() {
    List<ItemEntity> list = person.level.getEntitiesOfClass(ItemEntity.class,
        person.getBoundingBox().inflate(8.0D, 8.0D, 8.0D), ALLOWED_ITEMS);
    if (!list.isEmpty()) {
      person.getNavigation().moveTo(list.get(0), 0.5D);
    }

  }

  protected boolean shouldInterrupt() {
    return this.person.getLastHurtByMob() != null
        || this.person.isFreezing()
        || this.person.isOnFire()
        || this.person.getLevel().isNight()
        || this.person.isInterrupted();
  }

}
