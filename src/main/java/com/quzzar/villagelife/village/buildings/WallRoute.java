package com.quzzar.villagelife.village.buildings;

import java.util.List;

import net.minecraft.core.BlockPos;

/** Adapts the pure wall route geometry to packed Minecraft block positions. */
public final class WallRoute {

  private WallRoute() {
  }

  public static List<Long> aroundBox(int minX, int maxX, int minZ, int maxZ) {
    return WallRouteGeometry.aroundBox(minX, maxX, minZ, maxZ).stream()
        .map(point -> BlockPos.asLong(point.x(), 0, point.z()))
        .toList();
  }
}
