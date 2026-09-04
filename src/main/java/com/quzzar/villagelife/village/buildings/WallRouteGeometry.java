package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.List;

/** Pure eight-direction route geometry, kept independent of the Minecraft runtime. */
final class WallRouteGeometry {

  record Point(int x, int z) {
  }

  private WallRouteGeometry() {
  }

  static List<Point> aroundBox(int minX, int maxX, int minZ, int maxZ) {
    int width = maxX - minX;
    int depth = maxZ - minZ;
    if (width < 4 || depth < 4) {
      return rectangle(minX, maxX, minZ, maxZ);
    }
    int cut = Math.max(2, Math.min(6, Math.min(width, depth) / 4));
    List<Point> corners = List.of(
        new Point(minX + cut, minZ),
        new Point(maxX - cut, minZ),
        new Point(maxX, minZ + cut),
        new Point(maxX, maxZ - cut),
        new Point(maxX - cut, maxZ),
        new Point(minX + cut, maxZ),
        new Point(minX, maxZ - cut),
        new Point(minX, minZ + cut));
    List<Point> ring = new ArrayList<>();
    for (int i = 0; i < corners.size(); i++) {
      append(ring, corners.get(i), corners.get((i + 1) % corners.size()));
    }
    return List.copyOf(ring);
  }

  private static void append(List<Point> ring, Point from, Point to) {
    int dx = Integer.signum(to.x() - from.x());
    int dz = Integer.signum(to.z() - from.z());
    int x = from.x();
    int z = from.z();
    while (x != to.x() || z != to.z()) {
      ring.add(new Point(x, z));
      x += dx;
      z += dz;
    }
  }

  private static List<Point> rectangle(int minX, int maxX, int minZ, int maxZ) {
    List<Point> ring = new ArrayList<>();
    append(ring, new Point(minX, minZ), new Point(maxX, minZ));
    append(ring, new Point(maxX, minZ), new Point(maxX, maxZ));
    append(ring, new Point(maxX, maxZ), new Point(minX, maxZ));
    append(ring, new Point(minX, maxZ), new Point(minX, minZ));
    return List.copyOf(ring);
  }
}
