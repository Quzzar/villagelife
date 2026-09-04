package com.quzzar.kithkyn.entities.ai.goals;

import java.util.List;

import com.quzzar.kithkyn.entities.Person;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.entities.ai.HealthRecoveryPolicy;

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
 * the pack for the bite. A guard's shield is the one thing put back once the
 * eating is done, so it can be raised again ({@link RaiseShieldGoal}); a villager
 * with nothing to restore simply keeps what it ate from. A villager with nothing
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
    if (!HealthRecoveryPolicy.isBadlyHurt(person.getHealth(), person.getMaxHealth())
        || !person.hasMeal()) {
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
    restoreShieldToOffHand();
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

  /**
   * A guard's shield went to the pack when the meal took the off hand
   * ({@link #bringMealToHand}); with the eating done, put it back so the guard can
   * raise it again. Any bite left over is set down to the pack in its place. A
   * villager carrying no shield has nothing to restore and keeps the food to hand.
   */
  private void restoreShieldToOffHand() {
    int slot = person.shieldSlotInPack();
    if (slot < 0) {
      return;
    }
    ItemStack shield = person.personMainInv.removeItemNoUpdate(slot);
    ItemStack held = person.getOffhandItem();
    person.setItemSlot(EquipmentSlot.OFFHAND, shield);
    if (!held.isEmpty()) {
      person.addItems(List.of(held));
    }
  }
}
