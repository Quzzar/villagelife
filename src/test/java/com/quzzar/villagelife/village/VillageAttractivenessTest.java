package com.quzzar.villagelife.village;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VillageAttractivenessTest {

  @Test
  void theftPenaltyContributesToTheReportedTotal() {
    VillageAttractiveness report = new VillageAttractiveness(
        4, 0, 4, 0, 0,
        0.0F, 0.0F, 0.0F, 2.0F,
        50.0D, 0.0D, 0.0D, 0.0D,
        0.0D, 0.0D, 0.0D, -2.0D);

    assertEquals(48.0D, report.total());
  }
}
