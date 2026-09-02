package com.quzzar.villagelife.entities.ai.goals;

import java.util.List;

import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

/**
 * A badly hurt villager eats to heal: below a third of their health, with a
 * meal to hand and nothing to fight, they take a bite a second until they are
 * back over the line ({@link Person#eatFood} for what a bite heals). A meal
 * is anything edible in the off hand OR in the pack: only guards are ever
 * issued rations, but a fisher carries cod and a farmer carrots, and they
 * should not wait out their wounds with a full pack. Food from the pack is
 * brought to the off hand for the meal, since that is the hand the eating
 * animation and the use-item logic read; whatever the off hand held goes into
 * the pack and stays there (Aaron: no swapping back). A villager with nothing
 * to eat goes and gets some ({@link FetchFoodWhenHurtGoal}).
 *
 * <p>Fighters do not eat under fire: SetRunningToEatGoal breaks off first,
 * and a bite stops if a hostile mob nearby takes aim at a villager.
 */
public class PersonEatFoodGoal extends Goal {
  private final RealPerson person;

  public PersonEatFoodGoal(RealPerson person) {
    this.person = person;
  }

  @Override
  public boolean canUse() {
    if (person.getHealth() >= person.getMaxHealth() / 3 || !person.hasMeal()) {
      return false;
    }
    return (!person.isRunningToEat() && person.isEating())
        || (person.getTarget() == null && !person.isAggressive());
  }

  @Override
  public boolean canContinueToUse() {
    List<LivingEntity> near = person.level().getEntitiesOfClass(LivingEntity.class,
        person.getBoundingBox().inflate(5.0D, 3.0D, 5.0D));
    for (LivingEntity other : near) {
      if (other instanceof Mob mob && mob.getTarget() instanceof RealPerson) {
        return false;
      }
    }
    return person.getHealth() < (person.getMaxHealth() / 3) + 2 && person.isEating();
  }

  @Override
  public void start() {
    bringMealToHand();
    if (person.getTarget() == null) {
      person.setEating(true);
    }
    person.startUsingItem(InteractionHand.OFF_HAND);
  }

  @Override
  public void stop() {
    person.setEating(false);
    person.stopUsingItem();
  }

  /** The off hand already holds a meal: nothing to do. Otherwise the pack's first meal comes up, and the hand's item goes down. */
  private void bringMealToHand() {
    if (Person.isMeal(person.getOffhandItem())) {
      return;
    }
    int slot = person.mealSlotInPack();
    if (slot < 0) {
      return;
    }
    ItemStack meal = person.personMainInv.removeItemNoUpdate(slot);
    ItemStack held = person.getOffhandItem();
    person.setItemSlot(EquipmentSlot.OFFHAND, meal);
    if (!held.isEmpty()) {
      person.addItems(List.of(held));
    }
  }
}
