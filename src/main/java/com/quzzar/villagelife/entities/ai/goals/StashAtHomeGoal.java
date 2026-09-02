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
 * <p>Bounded so a bad night cannot cost the village the goods, and bounded by
 * headway rather than by what the pathfinder says: its verdict on whether a
 * path reaches the chest proved worthless live, false from twenty blocks out
 * because that is a villager's search range, and false from inside the house
 * because a door, a turn and a staircase exhaust its node budget. So the
 * walk gets a larger budget for its duration, and a villager who has not
 * come closer to the chest for a stretch (stood at the foot of a ladder, or
 * found no way in) gives up, as does one still walking after a few minutes.
 * A chest that is gone or full gives up on arrival. In every case the items
 * stay in the pack and the next bedtime stow returns them to the stores as
 * before.
 */
public class StashAtHomeGoal extends Goal {

  /**
   * Goal ticks of walking before the trip is given up. The selector ticks a
   * goal every other server tick, so this is about three minutes: a worker
   * who chose at the far end of the village still has time to walk home and
   * climb the stairs.
   */
  private static final int GIVE_UP_TICKS = 1800;
  /** Goal ticks without coming closer to the chest before the walk counts as going nowhere (about 40 s). */
  private static final int STALL_TICKS = 400;
  /** Closer by this much (blocks) counts as headway. */
  private static final double HEADWAY = 0.5D;
  /** The pathfinder's node budget while walking home, over its usual one. */
  private static final float PATH_BUDGET = 4.0F;
  /** Close enough to reach into the chest, squared. */
  private static final double REACH_SQR = 6.25;

  private final RealPerson person;
  private BlockPos chest;
  private int ticks;
  private int stalledTicks;
  private double bestDistance;

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
    if (!person.level().isNight()) {
      // The night is over. Whatever was not put away is the stores' again at
      // the next bedtime; nobody walks their keepsakes home by daylight.
      person.doneKeeping();
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
    return !person.keepingForHome().isEmpty() && this.chest != null && this.ticks < GIVE_UP_TICKS
        && person.level().isNight();
  }

  @Override
  public void start() {
    this.ticks = 0;
    this.stalledTicks = 0;
    this.bestDistance = Double.MAX_VALUE;
    person.getNavigation().setMaxVisitedNodesMultiplier(PATH_BUDGET);
    walk();
  }

  @Override
  public void stop() {
    person.getNavigation().resetMaxVisitedNodesMultiplier();
  }

  @Override
  public void tick() {
    this.ticks++;
    double distance = person.position().distanceTo(Vec3.atCenterOf(this.chest));
    if (distance * distance <= REACH_SQR) {
      person.getNavigation().stop();
      putAway();
      return;
    }
    if (distance < this.bestDistance - HEADWAY) {
      this.bestDistance = distance;
      this.stalledTicks = 0;
    } else {
      this.stalledTicks++;
    }
    if (this.stalledTicks >= STALL_TICKS) {
      Villagelife.LOGGER.info("'{}' made no headway toward their chest at home ({} blocks off); the {} stays in the pack",
          person.getFullName(), Math.round(distance), StashOffer.names(person.keepingForHome()));
      person.doneKeeping();
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
