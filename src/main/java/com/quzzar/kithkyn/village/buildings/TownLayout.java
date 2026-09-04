package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Block-space geometry for compact village growth.
 *
 * <p>A planned slot is not a lot on an abstract grid. It is a footprint placed
 * one lane away from a footprint already standing, with one of their edges
 * aligned. Repeating that small relationship produces streets, rows and
 * courtyards without imposing a rigid town plan on uneven terrain.</p>
 */
final class TownLayout {

  @FunctionalInterface
  interface ClaimedGround {
    boolean contains(int x, int z);
  }

  record Footprint(int minX, int minZ, int maxX, int maxZ) {

    Footprint {
      if (minX > maxX || minZ > maxZ) {
        throw new IllegalArgumentException("A footprint must have positive spans");
      }
    }

    Footprint moved(Origin origin) {
      return new Footprint(minX + origin.x(), minZ + origin.z(),
          maxX + origin.x(), maxZ + origin.z());
    }
  }

  record Origin(int x, int z) {
  }

  /** How strongly a legal site continues the fabric already standing. */
  record Relationship(int adjacentSides, int frontage) {
  }

  private TownLayout() {
  }

  /**
   * Origins that put {@code candidate} beside {@code anchor}, leaving exactly
   * {@code laneWidth} clear blocks between them. Each side is tried with the
   * beginnings, centres and ends of the two edges aligned.
   */
  static List<Origin> frontageOrigins(Footprint anchor, Footprint candidate, int laneWidth) {
    int separation = laneWidth + 1;
    Set<Origin> origins = new LinkedHashSet<>();

    for (int z : alignedOrigins(anchor.minZ(), anchor.maxZ(), candidate.minZ(), candidate.maxZ())) {
      origins.add(new Origin(anchor.minX() - separation - candidate.maxX(), z));
      origins.add(new Origin(anchor.maxX() + separation - candidate.minX(), z));
    }
    for (int x : alignedOrigins(anchor.minX(), anchor.maxX(), candidate.minX(), candidate.maxX())) {
      origins.add(new Origin(x, anchor.minZ() - separation - candidate.maxZ()));
      origins.add(new Origin(x, anchor.maxZ() + separation - candidate.minZ()));
    }
    return List.copyOf(origins);
  }

  /**
   * Every origin at which {@code candidate} fully contains {@code standing}.
   * Closest-centred placements come first, so an upgrade expands evenly unless
   * terrain or a neighbour makes a directional extension cheaper.
   */
  static List<Origin> containingOrigins(Footprint standing, Footprint candidate) {
    int minOriginX = standing.maxX() - candidate.maxX();
    int maxOriginX = standing.minX() - candidate.minX();
    int minOriginZ = standing.maxZ() - candidate.maxZ();
    int maxOriginZ = standing.minZ() - candidate.minZ();
    if (minOriginX > maxOriginX || minOriginZ > maxOriginZ) {
      return List.of();
    }

    List<Origin> origins = new ArrayList<>();
    for (int x = minOriginX; x <= maxOriginX; x++) {
      for (int z = minOriginZ; z <= maxOriginZ; z++) {
        origins.add(new Origin(x, z));
      }
    }
    origins.sort(Comparator.comparingInt(origin -> centreShiftSqr(standing, candidate.moved(origin))));
    return List.copyOf(origins);
  }

  /** Measures claimed frontage across the lane on each of a site's four sides. */
  static Relationship relationship(Footprint candidate, int laneWidth, ClaimedGround claimed) {
    int separation = laneWidth + 1;
    int sides = 0;
    int frontage = 0;

    int west = claimedAlongZ(candidate.minX() - separation, candidate.minZ(), candidate.maxZ(), claimed);
    int east = claimedAlongZ(candidate.maxX() + separation, candidate.minZ(), candidate.maxZ(), claimed);
    int north = claimedAlongX(candidate.minZ() - separation, candidate.minX(), candidate.maxX(), claimed);
    int south = claimedAlongX(candidate.maxZ() + separation, candidate.minX(), candidate.maxX(), claimed);
    for (int edge : new int[] {west, east, north, south}) {
      if (edge > 0) {
        sides++;
        frontage += edge;
      }
    }
    return new Relationship(sides, frontage);
  }

  private static List<Integer> alignedOrigins(int anchorMin, int anchorMax, int candidateMin, int candidateMax) {
    List<Integer> alignments = new ArrayList<>(3);
    alignments.add(anchorMin - candidateMin);
    alignments.add(Math.floorDiv(anchorMin + anchorMax - candidateMin - candidateMax, 2));
    alignments.add(anchorMax - candidateMax);
    return alignments;
  }

  private static int centreShiftSqr(Footprint first, Footprint second) {
    int deltaX = first.minX() + first.maxX() - second.minX() - second.maxX();
    int deltaZ = first.minZ() + first.maxZ() - second.minZ() - second.maxZ();
    return deltaX * deltaX + deltaZ * deltaZ;
  }

  private static int claimedAlongZ(int x, int minZ, int maxZ, ClaimedGround claimed) {
    int count = 0;
    for (int z = minZ; z <= maxZ; z++) {
      if (claimed.contains(x, z)) {
        count++;
      }
    }
    return count;
  }

  private static int claimedAlongX(int z, int minX, int maxX, ClaimedGround claimed) {
    int count = 0;
    for (int x = minX; x <= maxX; x++) {
      if (claimed.contains(x, z)) {
        count++;
      }
    }
    return count;
  }
}
