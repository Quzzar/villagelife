package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/** Chooses dry, structurally valid sockets for rigid wall features. */
public final class WallFeaturePlacement {

  private static final int GATE_HALF_LENGTH = 8;

  private WallFeaturePlacement() {
  }

  /**
   * Stable route anchors for the wall's non-gate watchtowers. A wet or otherwise
   * suppressed corner is omitted, matching the feature compiler exactly.
   */
  public static List<Long> towerAnchors(List<Long> ring, Set<Long> exclusions) {
    List<Long> anchors = new ArrayList<>();
    for (int index = 0; index < ring.size(); index++) {
      long anchor = ring.get(index);
      if (isTowerAnchor(ring, index) && !exclusions.contains(anchor)) {
        anchors.add(anchor);
      }
    }
    return List.copyOf(anchors);
  }

  /** Relocates wet gates and suppresses corner towers whose footprint reaches water. */
  public static Plan resolve(ServerLevel level, List<Long> ring, Set<Long> preferredGates) {
    Set<Long> gates = chooseGates(ring, preferredGates,
        anchor -> isDry(level, AuthoredWoodWallSegments.INSTANCE.footprintAt(
            ring, ring.indexOf(anchor), WallSectionKind.GATEHOUSE)));
    Set<Long> towerExclusions = new HashSet<>();
    for (long anchor : towerAnchors(ring, Set.of())) {
      int index = ring.indexOf(anchor);
      Set<Long> footprint = AuthoredWoodWallSegments.INSTANCE.footprintAt(
          ring, index, WallSectionKind.CORNER_TOWER);
      if (!isDry(level, footprint)) {
        towerExclusions.add(anchor);
      }
    }
    return new Plan(gates, Set.copyOf(towerExclusions));
  }

  /**
   * Keeps each gate on its original side and moves it the shortest distance to
   * a dry socket with enough straight wall for the full authored gatehouse.
   */
  static Set<Long> chooseGates(List<Long> ring, Set<Long> preferredGates,
      LongPredicate dryGate) {
    Set<Long> chosen = new HashSet<>();
    for (long preferred : preferredGates) {
      int preferredIndex = ring.indexOf(preferred);
      if (preferredIndex < 0) {
        continue;
      }
      Delta side = tangentAt(ring, preferredIndex);
      List<Integer> candidates = new ArrayList<>();
      for (int index = 0; index < ring.size(); index++) {
        if (tangentAt(ring, index).equals(side)
            && hasStraightClearance(ring, index)
            && dryGate.test(ring.get(index))) {
          candidates.add(index);
        }
      }
      candidates.stream()
          .min(Comparator.comparingInt(index -> cyclicDistance(
              preferredIndex, index, ring.size())))
          .ifPresent(index -> chosen.add(ring.get(index)));
    }
    return Set.copyOf(chosen);
  }

  private static boolean isDry(ServerLevel level, Set<Long> footprint) {
    if (footprint.isEmpty()) {
      return false;
    }
    for (long column : footprint) {
      int x = BlockPos.getX(column);
      int z = BlockPos.getZ(column);
      BlockPos probe = new BlockPos(x, level.getMinBuildHeight(), z);
      if (!level.hasChunkAt(probe)) {
        return false;
      }
      int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
      if (!level.getFluidState(new BlockPos(x, top - 1, z)).isEmpty()) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasStraightClearance(List<Long> ring, int center) {
    Delta tangent = tangentAt(ring, center);
    for (int offset = -GATE_HALF_LENGTH; offset <= GATE_HALF_LENGTH; offset++) {
      int index = Math.floorMod(center + offset, ring.size());
      if (isTurn(ring, index) || !tangentAt(ring, index).equals(tangent)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isTurn(List<Long> ring, int index) {
    return !delta(ring, previous(index, ring.size()), index)
        .equals(delta(ring, index, next(index, ring.size())));
  }

  private static boolean isTowerAnchor(List<Long> ring, int index) {
    Delta incoming = delta(ring, previous(index, ring.size()), index);
    Delta outgoing = delta(ring, index, next(index, ring.size()));
    return !incoming.equals(outgoing)
        && !isDiagonal(incoming)
        && isDiagonal(outgoing);
  }

  private static boolean isDiagonal(Delta delta) {
    return delta.x() != 0 && delta.z() != 0;
  }

  private static Delta tangentAt(List<Long> ring, int index) {
    Delta outgoing = delta(ring, index, next(index, ring.size()));
    if (outgoing.x() != 0 || outgoing.z() != 0) {
      return outgoing;
    }
    return delta(ring, previous(index, ring.size()), index);
  }

  private static Delta delta(List<Long> ring, int from, int to) {
    return new Delta(
        Integer.signum(BlockPos.getX(ring.get(to)) - BlockPos.getX(ring.get(from))),
        Integer.signum(BlockPos.getZ(ring.get(to)) - BlockPos.getZ(ring.get(from))));
  }

  private static int cyclicDistance(int first, int second, int size) {
    int direct = Math.abs(first - second);
    return Math.min(direct, size - direct);
  }

  private static int previous(int index, int size) {
    return Math.floorMod(index - 1, size);
  }

  private static int next(int index, int size) {
    return (index + 1) % size;
  }

  public record Plan(Set<Long> gates, Set<Long> towerExclusions) {
  }

  private record Delta(int x, int z) {
  }
}
