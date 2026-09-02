package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import java.util.Set;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.StashOffer;
import com.quzzar.villagelife.entities.ai.goals.work.PackLogistics;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.PersonalChest;
import com.quzzar.villagelife.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
 * <p>Two legs when the walk starts outside: to the doorstep first, then to
 * the chest ({@link LocationManager#getEntrance}). A path aimed straight at a
 * chest indoors stalls against the nearest outside wall when the door is on
 * the far side; the level-3 house at Wildflower Downs had its door away from
 * the village and everyone stopped under the upstairs chest, outside, night
 * after night, while a walk that began inside the house reached it. From the
 * doorstep the rest is a dozen nodes.
 *
 * <p>Bounded so a bad night cannot cost the village the goods, and bounded by
 * headway rather than by what the pathfinder says, whose verdict on whether
 * a path reaches proved worthless live: a villager who has not come closer
 * to the leg's target for a stretch (stood at the foot of a ladder, or found
 * no way in) gives up, as does one still walking after a few minutes. A chest
 * that is gone or full gives up on arrival. In every case the items stay in
 * the pack and the next bedtime stow returns them to the stores as before.
 */
public class StashAtHomeGoal extends Goal {

  /**
   * Goal ticks of walking before the trip is given up. The selector ticks a
   * goal every other server tick, so this is about three minutes: a worker
   * who chose at the far end of the village still has time to walk home and
   * climb the stairs.
   */
  private static final int GIVE_UP_TICKS = 1800;
  /** Goal ticks without coming closer to the target before the walk counts as going nowhere (about 40 s). */
  private static final int STALL_TICKS = 400;
  /** Closer by this much (blocks) counts as headway. */
  private static final double HEADWAY = 0.5D;
  /**
   * Close enough to reach into the chest, or to count as standing on the
   * doorstep, squared: three blocks. A chest on a loft is reached from the
   * floor a block below it, and the nearest standing spot to one such chest
   * measured 2.7 blocks from its middle, a hand short of the old 2.5.
   */
  private static final double REACH_SQR = 9.0D;

  private final RealPerson person;
  private BlockPos chest;
  private BlockPos leg;
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
    this.leg = firstLeg();
    aimAt(this.leg);
  }

  /** The doorstep when the walk starts outside the home; the chest itself when it starts inside or the home has no door. */
  private BlockPos firstLeg() {
    Building home = PersonalChest.home(person);
    if (home == null || !(person.level() instanceof ServerLevel level)) {
      return this.chest;
    }
    LocationManager.Entrance entrance = LocationManager.getEntrance(level, home);
    if (entrance == null || entrance.contains(person.blockPosition())) {
      return this.chest;
    }
    return entrance.doorstep();
  }

  private void aimAt(BlockPos target) {
    this.leg = target;
    this.stalledTicks = 0;
    this.bestDistance = Double.MAX_VALUE;
    walk();
  }

  @Override
  public void tick() {
    this.ticks++;
    double distance = person.position().distanceTo(Vec3.atCenterOf(this.leg));
    if (distance * distance <= REACH_SQR) {
      if (this.leg.equals(this.chest)) {
        person.getNavigation().stop();
        putAway();
      } else {
        aimAt(this.chest); // on the doorstep: the rest is indoors and short
      }
      return;
    }
    if (distance < this.bestDistance - HEADWAY) {
      this.bestDistance = distance;
      this.stalledTicks = 0;
    } else {
      this.stalledTicks++;
    }
    if (this.stalledTicks >= STALL_TICKS) {
      Villagelife.LOGGER.info("'{}' made no headway toward their chest at home ({} blocks off, {}); the {} stays in the pack",
          person.getFullName(), Math.round(distance), this.leg.equals(this.chest) ? "indoors" : "on the way to the door",
          StashOffer.names(person.keepingForHome()));
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
    person.getNavigation().moveTo(this.leg.getX(), this.leg.getY(), this.leg.getZ(), 0.5D);
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
