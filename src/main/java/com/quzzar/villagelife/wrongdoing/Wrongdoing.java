package com.quzzar.villagelife.wrongdoing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.bookkeeping.TheftBookkeepingEvent;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * What a village does about being wronged (decided on #64).
 *
 * Every offence goes through one gate — somebody has to have seen it — and then
 * has exactly two consequences. The village's mood moves, which is a fact about
 * the settlement; and each witness separately writes down what they saw, with
 * the culprit's name attached, which is a fact about a person. Nothing here
 * decides what anyone FEELS about it: reflection does that, in the villager's
 * own voice, through the same path that already handles everything else that
 * happens to them.
 *
 * That split is the whole design. The game records what happened; the villager
 * decides what it meant.
 */
public final class Wrongdoing {

  /**
   * How heavily each kind of harm lands on the village's mood. Killing is
   * worst, assault next, theft least — one scale, so a village that has been
   * robbed twice is not as unhappy as a village that has buried someone.
   */
  public enum Offence {
    THEFT(0.5F),
    ASSAULT(1.0F),
    MURDER(3.0F);

    private final float weight;

    Offence(float weight) {
      this.weight = weight;
    }

    public float weight() {
      return weight;
    }
  }

  private Wrongdoing() {
  }

  /**
   * Reports an offence that may or may not have been seen. Returns false when
   * nobody saw it, in which case nothing at all happens — no mood, no memory,
   * no grudge. An empty village is a place where anything can be done.
   */
  public static boolean report(ServerLevel level, Village village, UUID culprit, Offence offence,
      Vec3 where, String fact) {
    List<RealPerson> witnesses = Witnesses.around(level, where);
    if (witnesses.isEmpty()) {
      Villagelife.LOGGER.debug("An offence at {} went unseen, so it never happened",
          String.format("%.0f,%.0f,%.0f", where.x, where.y, where.z));
      return false;
    }

    if (village != null && offence == Offence.THEFT) {
      // Killing and assault already have their own entries in the books.
      TheftBookkeepingEvent event = new TheftBookkeepingEvent(culprit, fact);
      event.setImpact(offence.weight());
      village.logEvent(event);
    }

    for (RealPerson witness : witnesses) {
      witness.logIssue(fact, Optional.of(culprit));
    }
    Villagelife.LOGGER.info("{} was seen by {}: {}", offence.name().toLowerCase(),
        witnesses.size() == 1 ? "someone" : witnesses.size() + " people", fact);
    return true;
  }

}
