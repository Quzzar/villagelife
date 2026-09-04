package com.quzzar.villagelife.entities.ai.goals;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;

/**
 * Retaliation chosen by the victim's age and temperament.
 *
 * <p>Professional guards and hunters can still seek threats through their
 * occupation goals. This goal answers the narrower question of what a person
 * does when struck: fight back when {@link RealPerson#willFightBackWhenHurt()}
 * says so, otherwise leave the target unset so their panic and avoidance goals
 * can carry them away. Toddlers therefore always flee.
 */
public class SlowToAngerGoal extends HurtByTargetGoal {

  private final RealPerson person;

  public SlowToAngerGoal(RealPerson person) {
    super(person);
    this.person = person;
    setAlertOthers();
  }

  @Override
  public boolean canUse() {
    if (!super.canUse()) {
      return false;
    }
    return person.willFightBackWhenHurt();
  }

}
