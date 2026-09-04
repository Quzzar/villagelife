package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Derives defensive workstations from the exact geometry of a completed wall. */
public final class WallPosts {

  private static final int FEATURE_SEARCH_RADIUS = 9;

  private WallPosts() {
  }

  /**
   * Returns posts in staffing order: one sword at every gate, every tower's
   * crossbow, every gate's crossbow, then the second sword at every gate.
   */
  public static List<WallPost> plan(WallProject wall) {
    return plan(wall, null);
  }

  /** Plans posts with live terrain sampling for the two gate-base stations. */
  public static List<WallPost> plan(WallProject wall, @Nullable Level level) {
    if (wall == null || !wall.isComplete() || wall.getRing().isEmpty()) {
      return List.of();
    }
    List<Long> gates = orderedAnchors(wall.getRing(), wall.getGates());
    List<Long> towers = WallFeaturePlacement.towerAnchors(
        wall.getRing(), wall.getTowerExclusions());
    List<WallBlockPlan> blocks = wall.plannedBlocks();
    Set<Long> occupied = blocks.stream()
        .map(WallBlockPlan::position)
        .collect(java.util.stream.Collectors.toSet());
    Center center = centerOf(wall.getRing());

    List<WallPost> posts = new ArrayList<>();
    for (long gate : gates) {
      posts.add(gateBasePost(wall, occupied, center, gate, false, level));
    }
    for (long tower : towers) {
      posts.add(topPost(wall, blocks, occupied, center, tower,
          WallPost.Duty.WATCHTOWER_CROSSBOW));
    }
    for (long gate : gates) {
      posts.add(topPost(wall, blocks, occupied, center, gate,
          WallPost.Duty.GATE_CROSSBOW));
    }
    for (long gate : gates) {
      posts.add(gateBasePost(wall, occupied, center, gate, true, level));
    }
    return List.copyOf(posts);
  }

  private static WallPost gateBasePost(WallProject wall, Set<Long> occupied,
      Center center, long anchorLong, boolean second, @Nullable Level level) {
    List<Long> ring = wall.getRing();
    int index = ring.indexOf(anchorLong);
    BlockPos anchor = BlockPos.of(anchorLong);
    Vector inward = inwardFrom(center, anchor);
    Vector tangent = tangentAt(ring, index);
    int side = second ? 1 : -1;
    int baseY = wall.getGround().get(index);
    BlockPos preferred = new BlockPos(
        anchor.getX() + inward.x() * 2 + tangent.x() * 3 * side,
        baseY,
        anchor.getZ() + inward.z() * 2 + tangent.z() * 3 * side);
    BlockPos station = nearestClear(preferred, occupied, inward, tangent, level);
    BlockPos lookAt = new BlockPos(
        anchor.getX() - inward.x() * 4,
        station.getY() + 1,
        anchor.getZ() - inward.z() * 4);
    WallPost.Duty duty = second
        ? WallPost.Duty.GATE_SWORD_SECONDARY
        : WallPost.Duty.GATE_SWORD_PRIMARY;
    return new WallPost(station, lookAt, anchorLong, duty);
  }

  private static BlockPos nearestClear(BlockPos preferred, Set<Long> occupied,
      Vector inward, Vector tangent, @Nullable Level level) {
    List<BlockPos> candidates = new ArrayList<>();
    candidates.add(preferred);
    for (int distance = 1; distance <= 4; distance++) {
      candidates.add(preferred.offset(inward.x() * distance, 0, inward.z() * distance));
      candidates.add(preferred.offset(tangent.x() * distance, 0, tangent.z() * distance));
      candidates.add(preferred.offset(-tangent.x() * distance, 0, -tangent.z() * distance));
    }
    for (BlockPos horizontal : candidates) {
      BlockPos candidate = level == null
          ? horizontal
          : new BlockPos(horizontal.getX(),
              WallRaiser.surfaceY(level, horizontal.getX(), horizontal.getZ()),
              horizontal.getZ());
      if (isOpen(candidate, occupied)) {
        return candidate;
      }
    }
    return preferred;
  }

  private static WallPost topPost(WallProject wall, List<WallBlockPlan> blocks,
      Set<Long> occupied, Center center, long anchorLong, WallPost.Duty duty) {
    BlockPos anchor = BlockPos.of(anchorLong);
    Set<Long> footprint = featureFootprint(wall, anchorLong, duty);
    BlockPos station = blocks.stream()
        .filter(block -> isFloor(block.piece()))
        .map(WallBlockPlan::pos)
        .filter(floor -> footprint.contains(column(floor)))
        .map(BlockPos::above)
        .filter(candidate -> isOpen(candidate, occupied))
        .min(Comparator
            .comparingInt((BlockPos candidate) -> candidate.getY()).reversed()
            .thenComparingLong(candidate -> horizontalDistance(candidate, anchor)))
        .orElseGet(() -> fallbackTop(wall, anchorLong));
    Vector inward = inwardFrom(center, anchor);
    BlockPos lookAt = new BlockPos(
        anchor.getX() - inward.x() * 8,
        station.getY() + 1,
        anchor.getZ() - inward.z() * 8);
    return new WallPost(station, lookAt, anchorLong, duty);
  }

  private static Set<Long> featureFootprint(WallProject wall, long anchor,
      WallPost.Duty duty) {
    List<Long> ring = wall.getRing();
    int index = ring.indexOf(anchor);
    WallSectionKind kind = duty == WallPost.Duty.WATCHTOWER_CROSSBOW
        ? WallSectionKind.CORNER_TOWER
        : WallSectionKind.GATEHOUSE;
    if (wall.getTier() == WallTier.WOOD) {
      return AuthoredWoodWallSegments.INSTANCE.footprintAt(ring, index, kind);
    }
    BlockPos center = BlockPos.of(anchor);
    Set<Long> footprint = new HashSet<>();
    for (int x = -FEATURE_SEARCH_RADIUS; x <= FEATURE_SEARCH_RADIUS; x++) {
      for (int z = -FEATURE_SEARCH_RADIUS; z <= FEATURE_SEARCH_RADIUS; z++) {
        footprint.add(BlockPos.asLong(center.getX() + x, 0, center.getZ() + z));
      }
    }
    return Set.copyOf(footprint);
  }

  private static BlockPos fallbackTop(WallProject wall, long anchorLong) {
    int index = wall.getRing().indexOf(anchorLong);
    BlockPos anchor = BlockPos.of(anchorLong);
    return new BlockPos(anchor.getX(), wall.getDeck().get(index) + 1, anchor.getZ());
  }

  private static boolean isFloor(WallBlockPlan.Piece piece) {
    return piece == WallBlockPlan.Piece.WALKWAY
        || piece == WallBlockPlan.Piece.SLAB
        || piece.name().startsWith("STEP_");
  }

  private static boolean isOpen(BlockPos position, Set<Long> occupied) {
    return !occupied.contains(position.asLong())
        && !occupied.contains(position.above().asLong());
  }

  private static List<Long> orderedAnchors(List<Long> ring, Set<Long> anchors) {
    return ring.stream().filter(anchors::contains).toList();
  }

  private static Center centerOf(List<Long> ring) {
    long x = 0;
    long z = 0;
    for (long column : ring) {
      x += BlockPos.getX(column);
      z += BlockPos.getZ(column);
    }
    return new Center((double) x / ring.size(), (double) z / ring.size());
  }

  private static Vector inwardFrom(Center center, BlockPos anchor) {
    int x = Integer.signum((int) Math.round(center.x() - anchor.getX()));
    int z = Integer.signum((int) Math.round(center.z() - anchor.getZ()));
    if (Math.abs(center.x() - anchor.getX()) >= Math.abs(center.z() - anchor.getZ())) {
      z = 0;
    } else {
      x = 0;
    }
    return new Vector(x, z);
  }

  private static Vector tangentAt(List<Long> ring, int index) {
    BlockPos before = BlockPos.of(ring.get(Math.floorMod(index - 1, ring.size())));
    BlockPos after = BlockPos.of(ring.get((index + 1) % ring.size()));
    return new Vector(
        Integer.signum(after.getX() - before.getX()),
        Integer.signum(after.getZ() - before.getZ()));
  }

  private static long column(BlockPos position) {
    return BlockPos.asLong(position.getX(), 0, position.getZ());
  }

  private static long horizontalDistance(BlockPos first, BlockPos second) {
    long x = (long) first.getX() - second.getX();
    long z = (long) first.getZ() - second.getZ();
    return x * x + z * z;
  }

  private record Center(double x, double z) {
  }

  private record Vector(int x, int z) {
  }
}
