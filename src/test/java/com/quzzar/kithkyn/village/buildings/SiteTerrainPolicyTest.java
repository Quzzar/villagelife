package com.quzzar.kithkyn.village.buildings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SiteTerrainPolicyTest {

  @Test
  void allowsOneDeepLowCornerOnAnOtherwiseLevelFootprint() {
    List<SiteTerrainPolicy.Column> columns = levelFootprint(8, 8);
    columns.set(0, new SiteTerrainPolicy.Column(0, 0, -6));

    assertTrue(SiteTerrainPolicy.assess(columns).allowed());
  }

  @Test
  void rejectsWhenHalfTheFootprintIsDeepLowGround() {
    List<SiteTerrainPolicy.Column> columns = levelFootprint(4, 4);
    for (int i = 0; i < 8; i++) {
      SiteTerrainPolicy.Column column = columns.get(i);
      columns.set(i, new SiteTerrainPolicy.Column(column.x(), column.z(), -4));
    }

    assertFalse(SiteTerrainPolicy.assess(columns).allowed());
  }

  @Test
  void rejectsALongTrenchEvenWhenItsRatioIsSmall() {
    List<SiteTerrainPolicy.Column> columns = levelFootprint(16, 16);
    for (int x = 0; x < 4; x++) {
      int i = x * 16;
      columns.set(i, new SiteTerrainPolicy.Column(x, 0, -4));
    }

    SiteTerrainPolicy.Assessment assessment = SiteTerrainPolicy.assess(columns);
    assertFalse(assessment.allowed());
    assertTrue(assessment.reason().contains("too broad"));
  }

  @Test
  void stillRejectsHighGroundPastTheOrdinaryLimit() {
    List<SiteTerrainPolicy.Column> columns = levelFootprint(8, 8);
    columns.set(0, new SiteTerrainPolicy.Column(0, 0, 4));

    assertFalse(SiteTerrainPolicy.assess(columns).allowed());
  }

  @Test
  void rejectsAColumnTooDeepToFillSafely() {
    List<SiteTerrainPolicy.Column> columns = levelFootprint(8, 8);
    columns.set(0, new SiteTerrainPolicy.Column(0, 0, -7));

    assertFalse(SiteTerrainPolicy.assess(columns).allowed());
  }

  @Test
  void stillRejectsABroadSlopeInsideThePerColumnLimit() {
    List<SiteTerrainPolicy.Column> columns = levelFootprint(8, 8);
    for (int i = 0; i < columns.size(); i++) {
      SiteTerrainPolicy.Column column = columns.get(i);
      columns.set(i, new SiteTerrainPolicy.Column(column.x(), column.z(), 2));
    }

    assertFalse(SiteTerrainPolicy.assess(columns).allowed());
  }

  @Test
  void heightmapScreenLetsOneOutlierReachTheExactScan() {
    assertNull(new LocationValidator.Reading(65, 6, 1, 4, 0).screenedOut());
    assertTrue(new LocationValidator.Reading(65, 8, 2, 8, 0).screenedOut() != null);
  }

  private static List<SiteTerrainPolicy.Column> levelFootprint(int width, int depth) {
    List<SiteTerrainPolicy.Column> columns = new ArrayList<>();
    for (int x = 0; x < width; x++) {
      for (int z = 0; z < depth; z++) {
        columns.add(new SiteTerrainPolicy.Column(x, z, 0));
      }
    }
    return columns;
  }
}
