package com.quzzar.kithkyn.village.buildings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TownLayoutTest {

  @Test
  void frontageSlotsLeaveOneLaneAndAlignAlongTheNeighbour() {
    TownLayout.Footprint anchor = new TownLayout.Footprint(0, 0, 4, 6);
    TownLayout.Footprint candidate = new TownLayout.Footprint(0, 0, 2, 2);

    var origins = TownLayout.frontageOrigins(anchor, candidate, 1);

    assertTrue(origins.contains(new TownLayout.Origin(6, 0)));
    assertTrue(origins.contains(new TownLayout.Origin(6, 2)));
    assertTrue(origins.contains(new TownLayout.Origin(6, 4)));
  }

  @Test
  void relationshipRewardsAContinuousEdgeAndAnInfillCorner() {
    Set<String> claims = new HashSet<>();
    for (int z = 0; z <= 4; z++) {
      claims.add(cell(-2, z));
    }
    for (int x = 0; x <= 4; x++) {
      claims.add(cell(x, -2));
    }

    TownLayout.Relationship relationship = TownLayout.relationship(
        new TownLayout.Footprint(0, 0, 4, 4), 1,
        (x, z) -> claims.contains(cell(x, z)));

    assertEquals(2, relationship.adjacentSides());
    assertEquals(10, relationship.frontage());
  }

  @Test
  void upgradeOriginsCanExtendInEveryDirectionWhileContainingTheOldFootprint() {
    TownLayout.Footprint standing = new TownLayout.Footprint(10, 10, 12, 12);
    TownLayout.Footprint upgrade = new TownLayout.Footprint(0, 0, 4, 4);

    var origins = TownLayout.containingOrigins(standing, upgrade);

    assertEquals(new TownLayout.Origin(9, 9), origins.getFirst());
    assertTrue(origins.contains(new TownLayout.Origin(8, 8)));
    assertTrue(origins.contains(new TownLayout.Origin(10, 10)));
    assertEquals(9, origins.size());
  }

  private static String cell(int x, int z) {
    return x + "," + z;
  }
}
