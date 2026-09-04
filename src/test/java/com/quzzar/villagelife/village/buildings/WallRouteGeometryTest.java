package com.quzzar.villagelife.village.buildings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

class WallRouteGeometryTest {

  @Test
  void clipsABoxIntoOneContinuousEightDirectionRing() {
    List<WallRouteGeometry.Point> ring = WallRouteGeometry.aroundBox(0, 20, 0, 20);

    assertEquals(ring.size(), new HashSet<>(ring).size());
    assertTrue(ring.contains(new WallRouteGeometry.Point(10, 0)));
    assertTrue(ring.contains(new WallRouteGeometry.Point(20, 10)));
    assertTrue(hasDiagonalStep(ring));
    for (int i = 0; i < ring.size(); i++) {
      WallRouteGeometry.Point from = ring.get(i);
      WallRouteGeometry.Point to = ring.get((i + 1) % ring.size());
      int dx = Math.abs(to.x() - from.x());
      int dz = Math.abs(to.z() - from.z());
      assertTrue(dx <= 1 && dz <= 1 && dx + dz > 0);
    }
  }

  private static boolean hasDiagonalStep(List<WallRouteGeometry.Point> ring) {
    for (int i = 0; i < ring.size(); i++) {
      WallRouteGeometry.Point from = ring.get(i);
      WallRouteGeometry.Point to = ring.get((i + 1) % ring.size());
      if (from.x() != to.x() && from.z() != to.z()) {
        return true;
      }
    }
    return false;
  }
}
