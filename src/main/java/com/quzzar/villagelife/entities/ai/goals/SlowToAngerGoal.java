package com.quzzar.villagelife.entities.ai.goals;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Player;

/**
 * Retaliation with a temper that has to be earned (docs/relationships.md).
 *
 * Against a monster this is vanilla {@link HurtByTargetGoal}: strike a
 * villager and they turn on you at once, alerting the neighbours. Against a
 * player it waits for a real grudge. One punch is a grievance that costs
 * opinion, not a war; only when the victim's own opinion of the player has
 * fallen past the grudge line, or the village already counts them an outright
 * threat, does the blow get answered in kind. The neighbours are alerted with
 * it, which is how a beating in the square becomes a brawl.
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
    if (person.getLastHurtByMob() instanceof Player player) {
      return person.holdsGrudgeAgainst(player) || person.villageConsidersThreat(player);
    }
    return true;
  }

}
