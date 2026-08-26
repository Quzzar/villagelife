package com.quzzar.villagelife.relationships;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.village.JobAssignment;
import com.quzzar.villagelife.village.Village;

import net.minecraft.util.Mth;

/**
 * Relationships that move.
 *
 * Generation gives a newcomer a web of opinions on the day they arrive
 * (see {@link RelationshipService}); this is what happens to those opinions
 * afterwards. Two kinds of change, and the distinction is the whole design:
 *
 * <ul>
 *   <li><b>Mutual</b> drift moves the pair's shared value, because both people
 *       lived through the same thing: working the same building day after day,
 *       sitting at the same fire with nothing to do.</li>
 *   <li><b>One-sided</b> change moves only the viewer's lean, because only one
 *       of them experienced it that way: being defended, or losing a job to
 *       someone. The other person may not even know. Game code never decides
 *       these: they arrive through {@link OpinionService} as a villager's own
 *       judgement, made in conversation or on reflection.</li>
 * </ul>
 *
 * Drift is deliberately weak and bounded. The strong opinions in a village
 * should be the ones the model authored at generation, with a reason attached;
 * drift is the slow pressure of ordinary life around them, so it never pushes
 * a pair past {@link #DRIFT_LIMIT} on its own and never invents flavour text.
 */
public final class RelationshipDrift {

  /** Seconds between drift passes, phase-staggered per village. */
  public static final int DRIFT_INTERVAL_SECONDS = 120;

  /** Drift alone never carries a pair beyond this; stronger feelings are authored. */
  public static final int DRIFT_LIMIT = 55;

  /** Working the same building, every pass. */
  private static final int COWORKER_STEP = 1;

  /** Idling at the same fire with nothing to do, every pass. */
  private static final int CAMPFIRE_STEP = 1;

  private RelationshipDrift() {
  }

  /**
   * One pass of ordinary life: coworkers and campfire companions grow slowly
   * closer. Runs on the village tick, so it only advances while the village is
   * actually being simulated.
   */
  public static void tick(Village village) {
    List<UUID> residents = village.getPopulation();
    if (residents.size() < 2) {
      return;
    }

    for (int i = 0; i < residents.size(); i++) {
      for (int j = i + 1; j < residents.size(); j++) {
        UUID first = residents.get(i);
        UUID second = residents.get(j);

        JobAssignment firstJob = village.getJobAssignment(first);
        JobAssignment secondJob = village.getJobAssignment(second);

        if (firstJob != null && secondJob != null
            && firstJob.getBuildingUUID() != null
            && firstJob.getBuildingUUID().equals(secondJob.getBuildingUUID())) {
          nudgeMutual(village, first, second, COWORKER_STEP);
        } else if (firstJob == null && secondJob == null) {
          // Both idle: they are sitting at the same fire, day after day.
          nudgeMutual(village, first, second, CAMPFIRE_STEP);
        }
      }
    }
  }

  /**
   * Moves what both people feel about each other. Used for shared experience,
   * where neither has a private reason the other lacks.
   */
  public static void nudgeMutual(Village village, UUID first, UUID second, int delta) {
    RelationshipPair pair = village.getRelationship(first, second);
    int current = pair != null ? pair.value() : 0;
    // Drift pushes toward its limit and stops; it never drags an authored
    // friendship down or a feud up just because time passed.
    if (delta > 0 && current >= DRIFT_LIMIT) {
      return;
    }
    if (delta < 0 && current <= -DRIFT_LIMIT) {
      return;
    }
    int updated = Mth.clamp(current + delta, -DRIFT_LIMIT, DRIFT_LIMIT);
    store(village, pair, first, second, updated,
        pair != null ? pair.leanA() : 0, pair != null ? pair.leanB() : 0);
  }

  /**
   * Moves what one person feels, leaving the other's view alone: their lean
   * shifts, and the pair is marked asymmetric once the lean outgrows the
   * ordinary limit, which is exactly what asymmetry is for.
   */
  public static void nudgeOneSided(Village village, UUID viewer, UUID subject, int delta, String reason) {
    if (viewer.equals(subject)) {
      return;
    }
    RelationshipPair pair = village.getRelationship(viewer, subject);
    int value = pair != null ? pair.value() : 0;
    boolean viewerIsA = pair == null || pair.personA().equals(viewer);
    int viewerLean = pair == null ? 0 : (viewerIsA ? pair.leanA() : pair.leanB());
    int otherLean = pair == null ? 0 : (viewerIsA ? pair.leanB() : pair.leanA());

    int updatedLean = viewerLean + delta;
    boolean asymmetric = Math.abs(updatedLean) > RelationshipPair.LEAN_LIMIT
        || (pair != null && pair.asymmetric());

    RelationshipPair updated = RelationshipPair.create(viewer, subject, value,
        updatedLean, otherLean, asymmetric, pair != null ? pair.flavor() : "");
    village.putRelationship(updated);
    Villagelife.LOGGER.debug("Relationship shift in '{}': {} toward {} by {} ({})",
        village.getName(), viewer, subject, delta, reason);
  }

  /** Writes the pair back, keeping the canonical ordering and existing flavour. */
  private static void store(Village village, RelationshipPair existing, UUID first, UUID second,
      int value, int leanA, int leanB) {
    boolean firstIsA = existing == null || existing.personA().equals(first);
    RelationshipPair updated = RelationshipPair.create(first, second, value,
        firstIsA ? leanA : leanB, firstIsA ? leanB : leanA,
        existing != null && existing.asymmetric(),
        existing != null ? existing.flavor() : "");
    village.putRelationship(updated);
  }

  /** Residents who currently work the given building, for callers that need coworkers. */
  public static List<UUID> coworkersAt(Village village, UUID buildingUUID) {
    List<UUID> coworkers = new ArrayList<>();
    if (buildingUUID == null) {
      return coworkers;
    }
    for (UUID person : village.getPopulation()) {
      JobAssignment job = village.getJobAssignment(person);
      if (job != null && buildingUUID.equals(job.getBuildingUUID())) {
        coworkers.add(person);
      }
    }
    return coworkers;
  }

}
