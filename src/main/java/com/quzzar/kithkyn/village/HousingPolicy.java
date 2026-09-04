package com.quzzar.kithkyn.village;

import java.util.UUID;

import javax.annotation.Nullable;

/**
 * The small, world-free rules behind workplace bed reservation. Keeping these
 * decisions pure makes the invariants testable without booting a Minecraft
 * level; {@link Village} supplies the actual buildings and assignments.
 */
public final class HousingPolicy {

  private HousingPolicy() {
  }

  /**
   * Whether a bed may house a worker with the given job. General beds follow
   * their occupant between jobs. A reserved workplace bed is valid only while
   * its occupant staffs that same building, and is invalid for an idle person.
   */
  public static boolean bedCanHouseJob(UUID bedBuilding, boolean reserved,
      @Nullable UUID jobBuilding) {
    return !reserved || bedBuilding.equals(jobBuilding);
  }

  /**
   * Whether a person can remain housed after moving to a target workplace.
   * Their current general bed, that workplace's own bed, or a valid dependent
   * family home is enough; a bed reserved for the job they are leaving is not.
   */
  public static boolean canHouseAtTarget(boolean hasCurrentBed, boolean currentBedReserved,
      boolean currentBedMatchesTarget, boolean hasFreeGeneralBed, boolean hasFreeTargetBed,
      boolean hasDependentHome) {
    boolean canKeepCurrent = hasCurrentBed && (!currentBedReserved || currentBedMatchesTarget);
    return hasDependentHome || canKeepCurrent || hasFreeGeneralBed || hasFreeTargetBed;
  }
}
