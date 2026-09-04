package com.quzzar.villagelife.relationships;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.quzzar.villagelife.entities.AgeStage;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.JobAssignment;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.buildings.BuildingInfo;
import com.quzzar.villagelife.village.buildings.Buildings;
import com.quzzar.villagelife.village.buildings.UrbanPlanner;
import com.quzzar.villagelife.village.buildings.VillageGoal;

import net.minecraft.server.level.ServerLevel;

/**
 * Turns an employed teenager's approaching adulthood into a durable village
 * housing goal. The teenager keeps living in their parents' home meanwhile;
 * the goal prepares one independent bed before that exception expires.
 */
public final class FamilyHousingService {

  private static final String HOUSE_CATEGORY = "house";

  private FamilyHousingService() {
  }

  /** Names one ordinary house as the next save-for goal when adult housing is not ready. */
  public static void ensureTeenagerHomeGoal(Village village, ServerLevel level) {
    if (VillageGoal.current(village) != null) {
      return; // the housing need queues behind the village's existing commitment
    }
    RealPerson teenager = firstEmployedTeenagerWithoutAdultHousing(village, level);
    if (teenager == null) {
      return;
    }
    BuildingInfo house = Buildings.resolve(HOUSE_CATEGORY, 1, village.getStyle());
    if (house == null || house.getName().equals(VillageGoal.stalled(village, village.getVillageTime()))) {
      return;
    }
    String occupation = teenager.getOccupation().name().toLowerCase(Locale.ROOT);
    String reason = teenager.getFullName() + " is working as the " + occupation
        + " while living with family and needs an independent home before adulthood";
    VillageGoal.set(village, house.getName(), reason,
        UrbanPlanner.shortfallFor(village, house), village.getVillageTime());
  }

  private static RealPerson firstEmployedTeenagerWithoutAdultHousing(Village village, ServerLevel level) {
    int generalBeds = village.getFreeGeneralBedCount();
    Map<UUID, Integer> reservedBedsUsed = new HashMap<>();
    for (Map.Entry<UUID, JobAssignment> entry : village.getJobAssignmentsView().entrySet()) {
      RealPerson person = village.getPerson(level, entry.getKey());
      if (person == null || person.getLifeStage() != AgeStage.TEENAGER) {
        continue;
      }
      UUID workplace = entry.getValue().getBuildingUUID();
      int used = reservedBedsUsed.getOrDefault(workplace, 0);
      if (used < village.getFreeReservedBedCountIn(workplace)) {
        reservedBedsUsed.put(workplace, used + 1);
        continue;
      }
      if (generalBeds > 0) {
        generalBeds--;
        continue;
      }
      return person;
    }
    return null;
  }
}
