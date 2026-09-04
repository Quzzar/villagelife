package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** The procedural starter catalog behind the authored wall-segment seam. */
final class BuiltInWallSegmentCatalog implements WallSegmentCatalog {

  static final BuiltInWallSegmentCatalog INSTANCE = new BuiltInWallSegmentCatalog();

  /** Long enough to read as a structure, short enough for several builders to share the ring. */
  private static final int MAX_SECTION_LENGTH = 7;
  private static final int WOOD_GATEHOUSE_RADIUS = 8;
  private static final int STONE_GATEHOUSE_RADIUS = 2;

  private BuiltInWallSegmentCatalog() {
  }

  @Override
  public List<WallSection> compile(List<Long> ring, Set<Long> gates, List<Integer> ground,
      List<Integer> deck, WallTier tier) {
    return compile(ring, gates, ground, deck, tier, Set.of());
  }

  @Override
  public List<WallSection> compile(List<Long> ring, Set<Long> gates, List<Integer> ground,
      List<Integer> deck, WallTier tier, Set<Long> towerExclusions) {
    if (ring.isEmpty() || ground.size() != ring.size() || deck.size() != ring.size()) {
      return List.of();
    }
    List<WallSectionKind> kinds = classify(ring, gates, deck, tier, towerExclusions);
    List<WallSection> sections = new ArrayList<>();
    int from = 0;
    while (from < ring.size()) {
      WallSectionKind kind = kinds.get(from);
      int to = from + 1;
      while (to < ring.size() && to - from < MAX_SECTION_LENGTH && kinds.get(to) == kind) {
        to++;
      }
      sections.add(new WallSection(kind,
          blocksFor(ring, gates, ground, deck, tier, from, to, kind)));
      from = to;
    }
    List<WallSection> normalized = withoutUnsupportedLanterns(
        withoutOverlaps(sections));
    return tier == WallTier.WOOD
        ? thinLinearLanterns(normalized)
        : normalized;
  }

  /**
   * Feature clearance may win an overlap containing a lamp's support without
   * touching the lamp itself. Drop that orphaned detail rather than placing a
   * lantern that immediately breaks or hangs in the air.
   */
  private static List<WallSection> withoutUnsupportedLanterns(
      List<WallSection> sections) {
    Set<Long> positions = sections.stream()
        .flatMap(section -> section.blocks().stream())
        .map(WallBlockPlan::position)
        .collect(java.util.stream.Collectors.toSet());
    List<WallSection> supported = new ArrayList<>(sections.size());
    for (WallSection section : sections) {
      List<WallBlockPlan> blocks = section.blocks().stream()
          .filter(block -> {
            BlockPos support = switch (block.piece()) {
              case LANTERN -> block.pos().below();
              case LANTERN_HANGING -> block.pos().above();
              default -> null;
            };
            return support == null || positions.contains(support.asLong());
          })
          .toList();
      if (!blocks.isEmpty()) {
        supported.add(new WallSection(section.kind(), blocks));
      }
    }
    return List.copyOf(supported);
  }

  /** Keeps towers and gates lit while halving the repeated lantern rhythm on runs. */
  private static List<WallSection> thinLinearLanterns(List<WallSection> sections) {
    List<WallSection> thinned = new ArrayList<>(sections.size());
    int linearOrdinal = 0;
    for (WallSection section : sections) {
      if (!isLinear(section.kind())) {
        thinned.add(section);
        continue;
      }
      boolean keepLantern = Math.floorMod(linearOrdinal++, 2) == 0;
      boolean emittedLantern = false;
      List<WallBlockPlan> blocks = new ArrayList<>(section.blocks().size());
      for (WallBlockPlan block : section.blocks()) {
        if (!isLantern(block.piece())) {
          blocks.add(block);
        } else if (keepLantern && !emittedLantern) {
          blocks.add(block);
          emittedLantern = true;
        }
      }
      thinned.add(new WallSection(section.kind(), blocks));
    }
    return List.copyOf(thinned);
  }

  private static boolean isLinear(WallSectionKind kind) {
    return kind == WallSectionKind.STRAIGHT
        || kind == WallSectionKind.DIAGONAL
        || kind == WallSectionKind.TERRACE;
  }

  private static boolean isLantern(WallBlockPlan.Piece piece) {
    return piece == WallBlockPlan.Piece.LANTERN
        || piece == WallBlockPlan.Piece.LANTERN_HANGING;
  }

  /**
   * Neighboring width bands and towers intentionally overlap. Compile every
   * world position once. Authored empty volume wins over neighboring generated
   * cells, then exact detail wins over a generic barrier, so
   * cost and progress cannot charge twice for the same block.
   */
  private static List<WallSection> withoutOverlaps(List<WallSection> sections) {
    Map<Long, WallBlockPlan> winners = new LinkedHashMap<>();
    for (WallSection section : sections) {
      for (WallBlockPlan block : section.blocks()) {
        WallBlockPlan existing = winners.get(block.position());
        if (existing == null || priority(block.role()) > priority(existing.role())) {
          winners.put(block.position(), block);
        }
      }
    }

    Set<Long> emitted = new HashSet<>();
    List<WallSection> normalized = new ArrayList<>();
    for (WallSection section : sections) {
      List<WallBlockPlan> blocks = section.blocks().stream()
          .filter(block -> block.role() != WallCellRole.CLEARANCE)
          .filter(block -> winners.get(block.position()).equals(block))
          .filter(block -> emitted.add(block.position()))
          .toList();
      if (!blocks.isEmpty()) {
        normalized.add(new WallSection(section.kind(), blocks));
      }
    }
    return List.copyOf(normalized);
  }

  private static int priority(WallCellRole role) {
    return switch (role) {
      case BARRIER -> 0;
      case EXACT, FOUNDATION -> 1;
      case CLEARANCE -> 2;
    };
  }

  private static List<WallSectionKind> classify(List<Long> ring, Set<Long> gates,
      List<Integer> deck, WallTier tier, Set<Long> towerExclusions) {
    List<WallSectionKind> kinds = new ArrayList<>(ring.size());
    int gatehouseRadius = tier == WallTier.WOOD
        ? WOOD_GATEHOUSE_RADIUS
        : STONE_GATEHOUSE_RADIUS;
    for (int i = 0; i < ring.size(); i++) {
      Delta incoming = delta(ring, previous(i, ring.size()), i);
      Delta outgoing = delta(ring, i, next(i, ring.size()));
      if (distanceToGate(ring, gates, i) <= gatehouseRadius) {
        kinds.add(WallSectionKind.GATEHOUSE);
      } else if (isTowerAnchor(incoming, outgoing)
          && !towerExclusions.contains(ring.get(i))) {
        kinds.add(WallSectionKind.CORNER_TOWER);
      } else if (deck.get(i).intValue() != deck.get(previous(i, ring.size())).intValue()
          || deck.get(i).intValue() != deck.get(next(i, ring.size())).intValue()) {
        kinds.add(WallSectionKind.TERRACE);
      } else if (outgoing.x() != 0 && outgoing.z() != 0) {
        kinds.add(WallSectionKind.DIAGONAL);
      } else {
        kinds.add(WallSectionKind.STRAIGHT);
      }
    }
    return kinds;
  }

  private static List<WallBlockPlan> blocksFor(List<Long> ring, Set<Long> gates,
      List<Integer> ground, List<Integer> deck, WallTier tier, int from, int to,
      WallSectionKind sectionKind) {
    Map<Long, WallBlockPlan> blocks = new LinkedHashMap<>();
    for (int i = from; i < to; i++) {
      long column = ring.get(i);
      int x = BlockPos.getX(column);
      int z = BlockPos.getZ(column);
      Delta tangent = tangentAt(ring, i);
      Delta inward = new Delta(-tangent.z(), tangent.x());
      int floor = WallRaiser.seamFloor(ground, i);
      int top = deck.get(i);
      boolean gateCenter = gates.contains(column);
      boolean gateOpening = tier == WallTier.WOOD
          ? distanceToGate(ring, gates, i) <= 1
          : gateCenter;
      boolean tower = sectionKind == WallSectionKind.CORNER_TOWER
          || tier == WallTier.STONE
              && distanceToGate(ring, gates, i) == STONE_GATEHOUSE_RADIUS;

      if (tier == WallTier.WOOD) {
        addPalisadeColumn(blocks, x, z, floor, top, gateOpening);
      } else {
        addStoneColumn(blocks, ring, ground, deck, i, x, z, floor, top, gateCenter,
            tangent, inward);
      }
      if (tower && tier == WallTier.STONE) {
        addTower(blocks, x, z, floor, top, tier);
      }
    }
    if (tier == WallTier.WOOD) {
      for (WallBlockPlan block : AuthoredWoodWallSegments.INSTANCE.cellsFor(
          ring, gates, ground, deck, from, to, sectionKind)) {
        put(blocks, block.pos().getX(), block.pos().getY(), block.pos().getZ(),
            block.piece(), block.role());
      }
    }
    return List.copyOf(blocks.values());
  }

  private static void addPalisadeColumn(Map<Long, WallBlockPlan> blocks, int x, int z,
      int floor, int top, boolean gateOpening) {
    for (int y = floor; y <= top; y++) {
      if (!gateOpening) {
        put(blocks, x, y, z, WallBlockPlan.Piece.BODY, WallCellRole.BARRIER);
      }
    }
  }

  private static void addStoneColumn(Map<Long, WallBlockPlan> blocks, List<Long> ring,
      List<Integer> ground, List<Integer> deck, int index, int x, int z, int floor,
      int top, boolean gate, Delta tangent, Delta inward) {
    int openingTop = ground.get(index) + 2;
    if (tangent.x() != 0 && tangent.z() != 0) {
      // A diagonal chain of single blocks touches only at corners. Its 3x3
      // rasterized slices overlap into a real deck; the two extreme normal
      // corners become parapets and the middle band remains walkable.
      for (int ox = -1; ox <= 1; ox++) {
        for (int oz = -1; oz <= 1; oz++) {
          int cross = ox * inward.x() + oz * inward.z();
          boolean edge = Math.abs(cross) == 2;
          addStoneStack(blocks, x + ox, z + oz, floor, top, openingTop,
              false, !edge, edge, walkwayPiece(ring, deck, index, tangent));
        }
      }
    } else {
      for (int across = -1; across <= 1; across++) {
        int px = x + inward.x() * across;
        int pz = z + inward.z() * across;
        addStoneStack(blocks, px, pz, floor, top, openingTop, gate,
            across == 0, across != 0, walkwayPiece(ring, deck, index, tangent));
      }
    }
  }

  private static void addStoneStack(Map<Long, WallBlockPlan> blocks, int x, int z,
      int floor, int top, int openingTop, boolean gate, boolean walkway,
      boolean parapet, WallBlockPlan.Piece walkwayPiece) {
    for (int y = floor; y <= top; y++) {
      if (gate && y <= openingTop) {
        continue;
      }
      WallBlockPlan.Piece piece = walkway && y == top
          ? walkwayPiece
          : WallBlockPlan.Piece.BODY;
      put(blocks, x, y, z, piece,
          piece == WallBlockPlan.Piece.BODY ? WallCellRole.BARRIER : WallCellRole.EXACT);
    }
    if (!gate && parapet) {
      put(blocks, x, top + 1, z, WallBlockPlan.Piece.PARAPET, WallCellRole.EXACT);
    }
  }

  private static WallBlockPlan.Piece walkwayPiece(List<Long> ring, List<Integer> deck,
      int index, Delta tangent) {
    int here = deck.get(index);
    int before = deck.get(previous(index, deck.size()));
    int after = deck.get(next(index, deck.size()));
    if (after > here) {
      return WallBlockPlan.step(horizontal(tangent));
    }
    if (before > here) {
      return WallBlockPlan.step(horizontal(new Delta(-tangent.x(), -tangent.z())));
    }
    return WallBlockPlan.Piece.WALKWAY;
  }

  /** A compact 3x3 tower at every corner and on both sides of a gatehouse. */
  private static void addTower(Map<Long, WallBlockPlan> blocks, int x, int z, int floor,
      int wallTop, WallTier tier) {
    int towerTop = wallTop + (tier == WallTier.STONE ? 2 : 1);
    for (int ox = -1; ox <= 1; ox++) {
      for (int oz = -1; oz <= 1; oz++) {
        for (int y = floor; y <= towerTop; y++) {
          WallBlockPlan.Piece piece = y == towerTop
              ? WallBlockPlan.Piece.WALKWAY
              : WallBlockPlan.Piece.BODY;
          put(blocks, x + ox, y, z + oz, piece,
              piece == WallBlockPlan.Piece.BODY ? WallCellRole.BARRIER : WallCellRole.EXACT);
        }
        if (Math.abs(ox) == 1 || Math.abs(oz) == 1) {
          put(blocks, x + ox, towerTop + 1, z + oz,
              WallBlockPlan.Piece.PARAPET, WallCellRole.EXACT);
        }
      }
    }
  }

  private static void put(Map<Long, WallBlockPlan> blocks, int x, int y, int z,
      WallBlockPlan.Piece piece, WallCellRole role) {
    long position = BlockPos.asLong(x, y, z);
    WallBlockPlan existing = blocks.get(position);
    if (existing == null
        || replacementPriority(role) > replacementPriority(existing.role())) {
      blocks.put(position, new WallBlockPlan(position, piece, role));
    }
  }

  private static int replacementPriority(WallCellRole role) {
    return switch (role) {
      case CLEARANCE -> 0;
      case BARRIER -> 1;
      case EXACT -> 2;
      case FOUNDATION -> 3;
    };
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

  private static Direction horizontal(Delta delta) {
    if (Math.abs(delta.x()) >= Math.abs(delta.z())) {
      return delta.x() >= 0 ? Direction.EAST : Direction.WEST;
    }
    return delta.z() >= 0 ? Direction.SOUTH : Direction.NORTH;
  }

  /** One tower per chamfer, not one at both ends of the same six-block corner. */
  private static boolean isTowerAnchor(Delta incoming, Delta outgoing) {
    return !incoming.equals(outgoing)
        && !isDiagonal(incoming)
        && isDiagonal(outgoing);
  }

  private static boolean isDiagonal(Delta delta) {
    return delta.x() != 0 && delta.z() != 0;
  }

  private static int distanceToGate(List<Long> ring, Set<Long> gates, int index) {
    int best = Integer.MAX_VALUE;
    for (int i = 0; i < ring.size(); i++) {
      if (gates.contains(ring.get(i))) {
        int direct = Math.abs(i - index);
        best = Math.min(best, Math.min(direct, ring.size() - direct));
      }
    }
    return best;
  }

  private static int previous(int index, int size) {
    return Math.floorMod(index - 1, size);
  }

  private static int next(int index, int size) {
    return (index + 1) % size;
  }

  private record Delta(int x, int z) {
  }
}
