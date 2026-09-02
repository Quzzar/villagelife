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
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * The walk that follows a bedtime "keep it": a villager who chose to hold
 * things back from the village stores ({@link StashOffer}) carries them home
 * and sets them down in their own chest by hand. Runs ahead of
 * {@link SleepAtNightGoal}, which takes over the moment the pack is put away.
 *
 * <p>Bounded so a bad night cannot cost the village the goods: a chest the
 * pathfinder cannot reach (a bed up a ladder, say) gives the trip up at once,
 * a long walk gets a few minutes and no more, and a chest that is gone or
 * full gives up on arrival. In every case the items stay in the pack and the
 * next bedtime stow returns them to the stores as before.
 */
public class StashAtHomeGoal extends Goal {

  /**
   * Goal ticks of walking before the trip is given up. The selector ticks a
   * goal every other server tick, so this is about three minutes: a worker
   * who chose at the far end of the village still has time to walk home and
   * climb the stairs.
   */
  private static final int GIVE_UP_TICKS = 1800;
  /** Consecutive paths that stop short of the chest before it counts as unreachable. */
  private static final int UNREACHABLE_PATHS = 3;
  /** Close enough to reach into the chest, squared. */
  private static final double REACH_SQR = 6.25;

  private final RealPerson person;
  private BlockPos chest;
  private int ticks;
  private int shortPaths;

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
    this.shortPaths = 0;
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
    if (this.shortPaths >= UNREACHABLE_PATHS) {
      Villagelife.LOGGER.info("'{}' has no path to their chest at home; the {} stays in the pack",
          person.getFullName(), StashOffer.names(person.keepingForHome()));
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

  /**
   * One leg of the walk. The pathfinder hands back the closest route it can
   * find even when the chest is out of reach, so a path that stops short is
   * counted: a few in a row mean no route exists (a chest up a ladder), and
   * the trip is given up rather than walked to the foot of the ladder all
   * night.
   */
  private void walk() {
    Path path = person.getNavigation().createPath(this.chest, 1);
    if (path == null || !path.canReach()) {
      this.shortPaths++;
    } else {
      this.shortPaths = 0;
    }
    if (path != null) {
      person.getNavigation().moveTo(path, 0.5D);
    }
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
