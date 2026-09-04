package com.quzzar.kithkyn.entities.ai.goals;

import java.util.EnumSet;

import com.quzzar.kithkyn.chat.PersonChatDispatcher;
import com.quzzar.kithkyn.entities.RealPerson;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * While someone is in conversation with this person (conversation map #40),
 * a player at the chat screen or a fellow villager mid-exchange, stand still
 * and face them instead of wandering off. The chat session is authoritative
 * ({@link PersonChatDispatcher#isConversing}); it self-expires after
 * inactivity, so an abandoned chat never freezes a villager. Priority sits
 * below panic, shield, travel, and combat: danger still interrupts a chat,
 * small talk never interrupts danger.
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
    // A villager who has just picked a fight in this very conversation stops
    // standing politely still for it; the chat window closes on its own side.
    if (person.isQuarrelling()) {
      return false;
    }
    // Bedtime outranks small talk BETWEEN VILLAGERS: once night falls on someone
    // who sleeps, they stop standing still for a fellow villager's chat and let
    // SleepAtNightGoal walk them home (VillagerConversation parts the pair on
    // its own side too). But a chat with a PLAYER is never walked out of: when
    // someone has the screen open the villager stays and talks, night or not,
    // and only the player's own leave-taking ends it. A guard, who never sleeps,
    // keeps talking to anyone.
    if (person.getOccupation().sleepsAtNight() && person.level().isNight()
        && !conversingWithPlayer()) {
      return false;
    }
    return PersonChatDispatcher.isConversing(person);
  }

  /** Whether the person the villager is talking to is a player at the chat screen. */
  private boolean conversingWithPlayer() {
    return person.level() instanceof ServerLevel serverLevel
        && PersonChatDispatcher.conversingWith(person)
            .map(partnerId -> serverLevel.getServer().getPlayerList().getPlayer(partnerId) != null)
            .orElse(false);
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
    PersonChatDispatcher.conversingWith(person).ifPresent(partnerId -> {
      if (person.level() instanceof ServerLevel serverLevel) {
        // The partner is a player or a fellow villager; face whichever it is.
        var player = serverLevel.getServer().getPlayerList().getPlayer(partnerId);
        var partner = player != null ? player : serverLevel.getEntity(partnerId);
        if (partner != null) {
          person.getLookControl().setLookAt(partner, 30.0F, 30.0F);
        }
      }
    });
  }
}
