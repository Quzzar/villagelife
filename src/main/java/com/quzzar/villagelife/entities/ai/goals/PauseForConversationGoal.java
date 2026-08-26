package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;

import com.quzzar.villagelife.chat.PersonChatDispatcher;
import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * While a player is in conversation with this person (conversation map #40),
 * stand still and face them instead of wandering off. The chat session is
 * authoritative ({@link PersonChatDispatcher#isConversing}); it self-expires
 * after inactivity, so an abandoned chat never freezes a villager. Priority
 * sits below panic, shield, travel, and combat: danger still interrupts a
 * chat, small talk never interrupts danger.
 */
public class PauseForConversationGoal extends Goal {

  private final RealPerson person;

  public PauseForConversationGoal(RealPerson person) {
    this.person = person;
    this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
  }

  @Override
  public boolean canUse() {
    // Self-preservation outranks politeness: a burning or drowning villager
    // stops standing still to chat, whatever the conversation state.
    if (person.isOnFire() || person.isInLava() || person.getAirSupply() <= 0) {
      return false;
    }
    return PersonChatDispatcher.isConversing(person);
  }

  @Override
  public boolean canContinueToUse() {
    return canUse();
  }

  @Override
  public boolean requiresUpdateEveryTick() {
    return true;
  }

  @Override
  public void start() {
    person.getNavigation().stop();
  }

  @Override
  public void tick() {
    person.getNavigation().stop();
    PersonChatDispatcher.conversingWith(person).ifPresent(playerId -> {
      if (person.level() instanceof ServerLevel serverLevel) {
        var player = serverLevel.getServer().getPlayerList().getPlayer(playerId);
        if (player != null) {
          person.getLookControl().setLookAt(player, 30.0F, 30.0F);
        }
      }
    });
  }
}
