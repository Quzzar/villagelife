package com.quzzar.villagelife.village;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.buildings.Building;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * Job placement and reorganization for the campfire model (campfire map #18,
 * upgraded by #38): the brain places the best-suited idle person into each
 * open job (aptitude per {@link JobAptitudes}, FIFO as the tiebreaker), and a
 * slow-tick swap pass reorganizes workers only when the improvement clears the
 * configured threshold, with a per-person cooldown so villages don't churn.
 * Rules place; the LLM never picks. Runs from {@link Village#update}.
 */
public final class JobClaiming {

  private JobClaiming() {
  }

  /** One reconciliation-and-claiming pass every second; swaps on the slow tick. */
  public static void tick(Village village, ServerLevel level) {
    releaseInvalidAssignments(village, level);
    releaseOrphanedWorkers(village, level);
    claimOpenJobs(village, level);
    maybeRunSwapPass(village, level);
  }

  /**
   * A building definition redefined by a datapack reload is not a building
   * removal, so nothing else fires when a station list shrinks or reorders
   * under live assignments. Station indexes rely on the definition's insertion
   * order, so a stale assignment either dangles or silently re-points at a
   * DIFFERENT station (a guard quietly becomes a miner). Any held assignment
   * whose station no longer exists, or whose occupation no longer matches the
   * station it points at, is released: the person returns to the campfire pool
   * and gets claimed by whatever is genuinely open.
   */
  private static void releaseInvalidAssignments(Village village, ServerLevel level) {
    for (Map.Entry<UUID, JobAssignment> entry : village.getJobAssignmentsView().entrySet()) {
      JobAssignment job = entry.getValue();
      if (isStationValid(village, job)) {
        continue;
      }
      village.releaseJob(entry.getKey()); // dropped, never re-queued: the station is gone
      RealPerson person = village.getPerson(level, entry.getKey());
      Villagelife.LOGGER.warn(
          "Released {}'s {} assignment in '{}': its station no longer matches the building definition",
          person != null ? person.getFullName() : entry.getKey(), job.getOccupation(), village.getName());
      // The orphan pass this same tick returns them to idle.
    }
  }

  /** True when the assignment's station index still exists and still carries its occupation. */
  private static boolean isStationValid(Village village, JobAssignment job) {
    Building building = village.getBuilding(job.getBuildingUUID());
    if (building == null) {
      return false;
    }
    int index = 0;
    for (Occupation occupation : building.getInfo().getWorkLocations().values()) {
      if (index == job.getStationIndex()) {
        return occupation == job.getOccupation();
      }
      index++;
    }
    return false; // index past the end: the station list shrank
  }

  /**
   * Anyone holding an occupation without a booked job assignment goes back to
   * idle: this is how removed buildings and vacated jobs return people to the
   * campfire pool, with no hook needed at the removal sites.
   */
  private static void releaseOrphanedWorkers(Village village, ServerLevel level) {
    for (UUID personId : new ArrayList<>(village.getPopulation())) {
      if (village.getJobAssignment(personId) != null) {
        continue;
      }
      RealPerson person = village.getPerson(level, personId);
      if (person == null || person.getOccupation().isIdle()) {
        continue;
      }
      Villagelife.LOGGER.debug("{} lost their job; returning to the campfire pool", person.getFullName());
      person.setOccupation(Occupation.IDLE);
      person.setTravelTarget(null);
      person.reloadState();
    }
  }

  /** Oldest open job first; the best-suited loaded idle person takes it (FIFO breaks ties). */
  private static void claimOpenJobs(Village village, ServerLevel level) {
    while (!village.getUnassignedJobs().isEmpty()) {
      JobAssignment job = village.getUnassignedJobs().get(0);
      if (!isStationValid(village, job)) {
        // Stale opening from a redefined building: drop it, never fill it.
        village.getUnassignedJobs().remove(0);
        Villagelife.LOGGER.warn("Dropped a stale open {} job in '{}': station no longer in the building definition",
            job.getOccupation(), village.getName());
        continue;
      }
      RealPerson best = null;
      double bestScore = -1;
      for (UUID idleId : village.idlePeople()) {
        RealPerson person = village.getPerson(level, idleId);
        if (person == null) {
          continue; // not loaded right now; the next pass will see them
        }
        double score = JobAptitudes.score(person.getStatBlock(), job.getOccupation());
        if (score > bestScore) {
          best = person;
          bestScore = score;
        }
      }
      if (best == null) {
        return; // nobody claimable is loaded; try again next pass
      }
      village.assignJob(best.getUUID(), job);
      startJob(village, best, job);
      markCooldown(village, best.getUUID(), level.getGameTime());
      Villagelife.LOGGER.debug("{} claimed the open {} job (aptitude {})", best.getFullName(),
          job.getOccupation(), String.format("%.1f", bestScore));
    }
  }

  /** Occupation, visible commute to the workplace, goal refresh. */
  private static void startJob(Village village, RealPerson person, JobAssignment job) {
    person.setOccupation(job.getOccupation());
    Building workplace = village.getBuilding(job.getBuildingUUID());
    if (workplace != null) {
      person.setTravelTarget(BlockPos.of(workplace.getCenterLocation()));
    }
    person.reloadState();
  }

  // --- The swap pass (#38): threshold-gated reorganization on a slow tick ---

  private static void maybeRunSwapPass(Village village, ServerLevel level) {
    int interval = Math.max(10, VillagelifeConfig.JobSwapIntervalSeconds);
    long gameSeconds = level.getGameTime() / 20;
    if ((gameSeconds + Math.floorMod(village.getID().hashCode(), interval)) % interval != 0) {
      return;
    }
    double threshold = VillagelifeConfig.JobSwapThreshold;
    long now = level.getGameTime();

    Map<UUID, JobAssignment> assignments = village.getJobAssignmentsView();

    // A) A markedly better-suited idle person takes over a worker's job.
    for (Map.Entry<UUID, JobAssignment> entry : assignments.entrySet()) {
      UUID workerId = entry.getKey();
      JobAssignment job = entry.getValue();
      if (isOnCooldown(village, workerId, now)) {
        continue;
      }
      RealPerson worker = village.getPerson(level, workerId);
      if (worker == null) {
        continue;
      }
      double workerScore = JobAptitudes.score(worker.getStatBlock(), job.getOccupation());

      RealPerson challenger = null;
      double challengerScore = workerScore;
      for (UUID idleId : village.idlePeople()) {
        if (isOnCooldown(village, idleId, now)) {
          continue;
        }
        RealPerson candidate = village.getPerson(level, idleId);
        if (candidate == null) {
          continue;
        }
        double score = JobAptitudes.score(candidate.getStatBlock(), job.getOccupation());
        if (score > challengerScore) {
          challenger = candidate;
          challengerScore = score;
        }
      }
      if (challenger == null || challengerScore - workerScore < threshold) {
        continue;
      }

      JobAssignment released = village.releaseJob(workerId);
      if (released == null) {
        continue;
      }
      village.assignJob(challenger.getUUID(), released);
      startJob(village, challenger, released);
      worker.setOccupation(Occupation.IDLE);
      worker.setTravelTarget(null);
      worker.reloadState();
      worker.logIssue("Lost the " + released.getOccupation().name().toLowerCase() + " job to "
          + challenger.getFullName() + "; the village decided they were better suited.",
          java.util.Optional.of(challenger.getUUID()));
      // Losing your work to someone is personal, and only you feel it.
      com.quzzar.villagelife.relationships.RelationshipDrift.nudgeOneSided(village, workerId,
          challenger.getUUID(), com.quzzar.villagelife.relationships.RelationshipDrift.DISPLACED_PENALTY,
          "lost their job to them");
      markCooldown(village, workerId, now);
      markCooldown(village, challenger.getUUID(), now);
      Villagelife.LOGGER.info("'{}' took over the {} job from '{}' in '{}' (aptitude {} vs {})",
          challenger.getFullName(), released.getOccupation(), worker.getFullName(), village.getName(),
          String.format("%.1f", challengerScore), String.format("%.1f", workerScore));
    }

    // B) One beneficial two-worker exchange per pass, when the NET gain clears
    // the threshold. One per pass keeps reorganization legible.
    List<Map.Entry<UUID, JobAssignment>> workers = new ArrayList<>(village.getJobAssignmentsView().entrySet());
    for (int i = 0; i < workers.size(); i++) {
      for (int j = i + 1; j < workers.size(); j++) {
        UUID firstId = workers.get(i).getKey();
        UUID secondId = workers.get(j).getKey();
        if (isOnCooldown(village, firstId, now) || isOnCooldown(village, secondId, now)) {
          continue;
        }
        JobAssignment firstJob = workers.get(i).getValue();
        JobAssignment secondJob = workers.get(j).getValue();
        if (firstJob.getOccupation() == secondJob.getOccupation()) {
          continue;
        }
        RealPerson first = village.getPerson(level, firstId);
        RealPerson second = village.getPerson(level, secondId);
        if (first == null || second == null) {
          continue;
        }
        double current = JobAptitudes.score(first.getStatBlock(), firstJob.getOccupation())
            + JobAptitudes.score(second.getStatBlock(), secondJob.getOccupation());
        double exchanged = JobAptitudes.score(second.getStatBlock(), firstJob.getOccupation())
            + JobAptitudes.score(first.getStatBlock(), secondJob.getOccupation());
        if (exchanged - current < threshold) {
          continue;
        }
        JobAssignment releasedFirst = village.releaseJob(firstId);
        JobAssignment releasedSecond = village.releaseJob(secondId);
        if (releasedFirst == null || releasedSecond == null) {
          continue;
        }
        village.assignJob(secondId, releasedFirst);
        village.assignJob(firstId, releasedSecond);
        startJob(village, second, releasedFirst);
        startJob(village, first, releasedSecond);
        markCooldown(village, firstId, now);
        markCooldown(village, secondId, now);
        Villagelife.LOGGER.info("'{}' and '{}' exchanged the {} and {} jobs in '{}' (net aptitude +{})",
            first.getFullName(), second.getFullName(), releasedFirst.getOccupation(),
            releasedSecond.getOccupation(), village.getName(), String.format("%.1f", exchanged - current));
        return; // one exchange per pass
      }
    }
  }

  private static boolean isOnCooldown(Village village, UUID personId, long now) {
    CompoundTag cooldowns = village.getSwapCooldowns();
    String key = personId.toString();
    if (!cooldowns.contains(key)) {
      return false;
    }
    long cooldownTicks = (long) (VillagelifeConfig.JobSwapCooldownDays * 24000L);
    if (now - cooldowns.getLong(key) >= cooldownTicks) {
      cooldowns.remove(key); // expired; prune as we go
      return false;
    }
    return true;
  }

  private static void markCooldown(Village village, UUID personId, long now) {
    village.getSwapCooldowns().putLong(personId.toString(), now);
  }
}
