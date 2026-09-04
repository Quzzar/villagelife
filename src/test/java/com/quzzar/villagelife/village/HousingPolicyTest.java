package com.quzzar.villagelife.village;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class HousingPolicyTest {

  @Test
  void aLiveInBedStopsHousingItsWorkerWhenTheyLeaveThatWorkplace() {
    UUID lumberjack = UUID.randomUUID();
    UUID fishery = UUID.randomUUID();

    assertTrue(HousingPolicy.bedCanHouseJob(lumberjack, true, lumberjack));
    assertFalse(HousingPolicy.bedCanHouseJob(lumberjack, true, fishery));
    assertFalse(HousingPolicy.bedCanHouseJob(lumberjack, true, null));
    assertTrue(HousingPolicy.bedCanHouseJob(lumberjack, false, fishery));
  }

  @Test
  void aLiveInWorkerCannotMoveToABedlessWorkplaceWithoutGeneralHousing() {
    assertFalse(HousingPolicy.canHouseAtTarget(true, true, false, false, false, false));
    assertTrue(HousingPolicy.canHouseAtTarget(true, false, false, false, false, false));
    assertTrue(HousingPolicy.canHouseAtTarget(true, true, true, false, false, false));
    assertTrue(HousingPolicy.canHouseAtTarget(true, true, false, true, false, false));
    assertTrue(HousingPolicy.canHouseAtTarget(true, true, false, false, true, false));
  }

  @Test
  void aTeenagersFamilyHomeSatisfiesHousingWithoutTakingABed() {
    assertTrue(HousingPolicy.canHouseAtTarget(false, false, false, false, false, true));
  }
}
