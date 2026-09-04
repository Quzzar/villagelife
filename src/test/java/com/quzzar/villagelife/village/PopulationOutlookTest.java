package com.quzzar.villagelife.village;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PopulationOutlookTest {

  @Test
  void highAttractivenessDoesNotClaimArrivalsWhenHousingIsCapped() {
    assertEquals(PopulationOutlook.HOUSING_CAP,
        PopulationOutlook.evaluate(6, 0, 4, true, PopulationOutlook.Trend.GROWING,
            6, 4, 1, 2));
  }

  @Test
  void thePopulationFloorOverridesMoodAndColdStores() {
    assertEquals(PopulationOutlook.FORCED_REFILL,
        PopulationOutlook.evaluate(2, 0, 4, false, PopulationOutlook.Trend.DECLINING,
            2, 0, 2, 2));
  }

  @Test
  void declineAtTheFloorReportsThatNobodyCanLeave() {
    assertEquals(PopulationOutlook.HELD_AT_FLOOR,
        PopulationOutlook.evaluate(4, 0, 4, true, PopulationOutlook.Trend.DECLINING,
            4, 4, 0, 2));
  }
}
