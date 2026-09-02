package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import java.util.Set;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.StashOffer;
import com.quzzar.villagelife.entities.ai.goals.work.PackLogistics;
import com.quzzar.villagelife.village.PersonalChest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/**
 * The walk that follows a bedtime "keep it": a villager who chose to hold
 * things back from the village stores ({@link StashOffer}) carries them home
 * and sets them down in their own chest by hand. Runs ahead of
 * {@link SleepAtNightGoal}, which takes over the moment the pack is put away.
 *
 * <p>Bounded so a bad night cannot cost the village the goods: a chest that
 * cannot be reached, is gone, or is full gives the trip up, the items stay in
 * the pack, and the next bedtime stow returns them to the stores as before.
 */
public class StashAtHomeGoal extends Goal {

  /** Ticks of walking before the trip is given up and the pack keeps the goods. */
  private static final int GIVE_UP_TICKS = 600;
  /** Close enough to reach into the chest, squared. */
  private static final double REACH_SQR = 6.25;

  private final RealPerson person;
  private BlockPos chest;
  private int ticks;

  public StashAtHomeGoal(RealPerson person) {
    this.setFlags(EnumSet.of(Flag.MOVE));
    this.person = person;
  }

  @Override
  public boolean canUse() {
    Set<Item> keeping = person.keepingForHome();
    if (keeping.isEmpty()) {
      return false;
    }
    if (keeping.stream().noneMatch(item -> person.personMainInv.countItem(item) > 0)) {
      person.doneKeeping(); // already set down, or stowed by a refire
      return false;
    }
    this.chest = PersonalChest.of(person);
    if (this.chest == null) {
      person.doneKeeping(); // the home went since bedtime; the next stow takes it
      return false;
    }
    return true;
  }

  @Override
  public boolean canContinueToUse() {
    return !person.keepingForHome().isEmpty() && this.chest != null && this.ticks < GIVE_UP_TICKS;
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
      putAway();
      return;
    }
    if (this.ticks >= GIVE_UP_TICKS) {
      Villagelife.LOGGER.info("'{}' could not reach their chest at home tonight; the {} stays in the pack",
          person.getFullName(), StashOffer.names(person.keepingForHome()));
      person.doneKeeping();
      return;
    }
    if (!person.getNavigation().isInProgress()) {
      walk();
    }
  }

  private void walk() {
    person.getNavigation().moveTo(this.chest.getX(), this.chest.getY(), this.chest.getZ(), 0.5D);
  }

  private void putAway() {
    Set<Item> keeping = person.keepingForHome();
    Container container = PersonalChest.container(person, this.chest);
    if (container == null) {
      Villagelife.LOGGER.info("'{}' found no chest at home to keep the {} in", person.getFullName(),
          StashOffer.names(keeping));
      person.doneKeeping();
      return;
    }
    int moved = 0;
    for (Item item : keeping) {
      moved += PackLogistics.depositCarried(person, container, item, "home");
    }
    if (moved > 0) {
      Villagelife.LOGGER.info("'{}' put {} item(s) of {} away in their chest at home", person.getFullName(),
          moved, StashOffer.names(keeping));
    } else {
      Villagelife.LOGGER.info("'{}' found no room in their chest at home for the {}", person.getFullName(),
          StashOffer.names(keeping));
    }
    person.doneKeeping();
  }
}
