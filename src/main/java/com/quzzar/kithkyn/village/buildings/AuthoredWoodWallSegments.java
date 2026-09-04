package com.quzzar.kithkyn.village.buildings;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.quzzar.kithkyn.Kithkyn;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/**
 * The authored wooden-wall vocabulary captured from the in-world wall lab.
 *
 * <p>The route remains procedural. These bundled NBT files supply its local
 * silhouette and functional details, then this adapter rotates them onto the
 * saved route. A missing or malformed asset logs once and leaves the structural
 * fallback intact, so a content-pack problem cannot open the defensive shell.</p>
 */
final class AuthoredWoodWallSegments {

  static final AuthoredWoodWallSegments INSTANCE = loadBundled();

  private static final String RESOURCE_ROOT =
      "data/kithkyn/structure/wall/wood/";
  private static final int CORNER_ANCHOR_X = 6;
  private static final int CORNER_ANCHOR_Z = 0;
  private static final int GATEHOUSE_ANCHOR_X = 8;
  private static final int GATEHOUSE_ANCHOR_Z = 2;
  /** Keeps irregular posts visible without letting a terrain step turn one into a mast. */
  private static final int LINEAR_DETAIL_HEADROOM = 2;

  private final Map<WallSectionKind, Template> templates;

  private AuthoredWoodWallSegments(Map<WallSectionKind, Template> templates) {
    this.templates = Map.copyOf(templates);
  }

  /** Adds the authored cells belonging to one classified route section. */
  List<WallBlockPlan> blocksFor(List<Long> ring, java.util.Set<Long> gates,
      List<Integer> ground, List<Integer> deck, int from, int to,
      WallSectionKind kind) {
    return cellsFor(ring, gates, ground, deck, from, to, kind).stream()
        .filter(block -> block.role() != WallCellRole.CLEARANCE)
        .toList();
  }

  /**
   * Adds authored blocks plus empty cells owned by a rigid feature. Clearance
   * cells let a gatehouse or tower replace the procedural run passing through
   * its footprint without becoming construction work themselves.
   */
  List<WallBlockPlan> cellsFor(List<Long> ring, java.util.Set<Long> gates,
      List<Integer> ground, List<Integer> deck, int from, int to,
      WallSectionKind kind) {
    Template template = this.templates.get(kind);
    if (template == null) {
      return List.of();
    }
    Map<Long, WallBlockPlan> blocks = new LinkedHashMap<>();
    switch (kind) {
      case STRAIGHT, DIAGONAL, TERRACE -> addLinear(
          blocks, template, ring, ground, deck, from, to, kind);
      case CORNER_TOWER -> addCorners(
          blocks, template, ring, ground, deck, from, to);
      case GATEHOUSE -> addGatehouses(
          blocks, template, ring, gates, ground, deck, from, to);
    }
    return blocks.values().stream()
        .sorted(Comparator
            .comparingInt((WallBlockPlan block) -> constructionPriority(block.piece()))
            .thenComparingInt(block -> block.pos().getY())
            .thenComparingInt(block -> supportPriority(block.piece()))
            .thenComparingLong(WallBlockPlan::position))
        .toList();
  }

  /**
   * A straight-like template contributes one authored vertical slice per route
   * column. The route owns horizontal placement and terrain height, which lets
   * the same art follow straight, diagonal and stepped terrain without shearing
   * a tower or leaving gaps below it.
   */
  private static void addLinear(Map<Long, WallBlockPlan> blocks, Template template,
      List<Long> ring, List<Integer> ground, List<Integer> deck,
      int from, int to, WallSectionKind kind) {
    int length = Math.min(to - from, template.routeLength());
    Map<Integer, Integer> postBottoms = new LinkedHashMap<>();
    for (Cell cell : template.cells()) {
      int ordinal = cell.x();
      if (ordinal < 0 || ordinal >= length) {
        continue;
      }
      int ringIndex = from + ordinal;
      BlockPos route = BlockPos.of(ring.get(ringIndex));
      Vec tangent = tangentAt(ring, ringIndex);
      Transform transform = kind == WallSectionKind.DIAGONAL
          || tangent.x() != 0 && tangent.z() != 0
              ? Transform.diagonal(tangent)
              : Transform.along(tangent);
      int base = deck.get(ringIndex) - (WallTier.WOOD.height() - 1);
      int topShift = Math.max(0,
          template.relativeTop(ordinal)
              - (WallTier.WOOD.height() - 1 + LINEAR_DETAIL_HEADROOM));
      int y = base + cell.y() - template.baseY(ordinal) - topShift;
      put(blocks, new BlockPos(route.getX(), y, route.getZ()),
          transform.piece(cell.piece()));
      if (cell.piece() == WallBlockPlan.Piece.POST) {
        postBottoms.merge(ringIndex, y, Math::min);
      }
    }
    for (Map.Entry<Integer, Integer> entry : postBottoms.entrySet()) {
      int ringIndex = entry.getKey();
      BlockPos route = BlockPos.of(ring.get(ringIndex));
      int terrainY = WallRaiser.seamFloor(ground, ringIndex);
      for (int y = terrainY; y < entry.getValue(); y++) {
        put(blocks, new BlockPos(route.getX(), y, route.getZ()),
            WallBlockPlan.Piece.POST, WallCellRole.BARRIER);
      }
    }
  }

  private static void addCorners(Map<Long, WallBlockPlan> blocks, Template template,
      List<Long> ring, List<Integer> ground, List<Integer> deck, int from, int to) {
    for (int index = from; index < to; index++) {
      Vec incoming = delta(ring, previous(index, ring.size()), index);
      Vec outgoing = delta(ring, index, next(index, ring.size()));
      if (incoming.equals(outgoing)) {
        continue;
      }
      Transform transform = Transform.corner(incoming, outgoing);
      if (transform == null) {
        continue;
      }
      BlockPos anchor = BlockPos.of(ring.get(index));
      int base = deck.get(index) - (WallTier.WOOD.height() - 1);
      addRigid(blocks, template, ring, ground, anchor, base, transform,
          CORNER_ANCHOR_X, CORNER_ANCHOR_Z, WallSectionKind.CORNER_TOWER);
    }
  }

  private static void addGatehouses(Map<Long, WallBlockPlan> blocks, Template template,
      List<Long> ring, java.util.Set<Long> gates, List<Integer> ground,
      List<Integer> deck, int from, int to) {
    for (int index = from; index < to; index++) {
      if (!gates.contains(ring.get(index))) {
        continue;
      }
      BlockPos anchor = BlockPos.of(ring.get(index));
      int base = deck.get(index) - (WallTier.WOOD.height() - 1);
      addRigid(blocks, template, ring, ground, anchor, base,
          Transform.along(tangentAt(ring, index)),
          GATEHOUSE_ANCHOR_X, GATEHOUSE_ANCHOR_Z, WallSectionKind.GATEHOUSE);
    }
  }

  private static void addRigid(Map<Long, WallBlockPlan> blocks, Template template,
      List<Long> ring, List<Integer> ground, BlockPos anchor, int baseY,
      Transform transform, int anchorX, int anchorZ, WallSectionKind kind) {
    for (int x = 0; x < template.sizeX(); x++) {
      for (int y = 0; y < template.sizeY(); y++) {
        for (int z = 0; z < template.sizeZ(); z++) {
          Vec offset = transform.apply(x - anchorX, z - anchorZ);
          put(blocks, new BlockPos(anchor.getX() + offset.x(), baseY + y,
              anchor.getZ() + offset.z()), WallBlockPlan.Piece.BODY,
              WallCellRole.CLEARANCE);
        }
      }
    }
    List<PlacedCell> placedCells = new ArrayList<>(template.cells().size());
    Map<Long, Integer> postBottoms = new LinkedHashMap<>();
    for (Cell cell : template.cells()) {
      if (isInwardRoofLantern(kind, cell)) {
        continue;
      }
      Vec offset = transform.apply(cell.x() - anchorX, cell.z() - anchorZ);
      BlockPos position = new BlockPos(
          anchor.getX() + offset.x(), baseY + cell.y(), anchor.getZ() + offset.z());
      WallBlockPlan.Piece piece = transform.piece(cell.piece());
      placedCells.add(new PlacedCell(position, piece));
      put(blocks, position, piece);
      if (piece == WallBlockPlan.Piece.POST) {
        long column = BlockPos.asLong(position.getX(), 0, position.getZ());
        postBottoms.merge(column, position.getY(), Math::min);
      }
    }
    for (Map.Entry<Long, Integer> entry : postBottoms.entrySet()) {
      int x = BlockPos.getX(entry.getKey());
      int z = BlockPos.getZ(entry.getKey());
      int terrainY = nearestGround(ring, ground, x, z);
      for (int y = terrainY; y < entry.getValue(); y++) {
        put(blocks, new BlockPos(x, y, z), WallBlockPlan.Piece.POST,
            WallCellRole.BARRIER);
      }
    }
    extendClimbShaft(blocks, placedCells, ring, ground, baseY);
  }

  /** The live review keeps the outward lamps and removes the visually busy inward pair. */
  private static boolean isInwardRoofLantern(WallSectionKind kind, Cell cell) {
    if (cell.piece() != WallBlockPlan.Piece.LANTERN) {
      return false;
    }
    return switch (kind) {
      case GATEHOUSE -> cell.y() == 7 && cell.z() == 0
          && (cell.x() == 6 || cell.x() == 10);
      case CORNER_TOWER -> cell.y() == 6 && cell.z() == 2
          && (cell.x() == 6 || cell.x() == 8);
      default -> false;
    };
  }

  /**
   * A rigid feature can sit above terrain sampled by its route anchor. Its
   * climb shaft is structural in the same way as its log legs: repeat the
   * lowest ladder, trapdoor and adjacent low fence cells until each exact
   * terrain column is reached.
   */
  private static void extendClimbShaft(Map<Long, WallBlockPlan> blocks,
      List<PlacedCell> placedCells, List<Long> ring, List<Integer> ground, int baseY) {
    Set<Long> climbColumns = placedCells.stream()
        .filter(cell -> isClimbPiece(cell.piece()))
        .map(cell -> column(cell.position()))
        .collect(java.util.stream.Collectors.toSet());
    Map<Long, PlacedCell> bottoms = new LinkedHashMap<>();
    for (PlacedCell cell : placedCells) {
      boolean climbPiece = isClimbPiece(cell.piece());
      boolean lowAdjacentFence = cell.piece() == WallBlockPlan.Piece.PARAPET
          && cell.position().getY() <= baseY + 1
          && isAdjacentToAny(column(cell.position()), climbColumns);
      if (climbPiece || lowAdjacentFence) {
        bottoms.merge(column(cell.position()), cell,
            (left, right) -> left.position().getY() <= right.position().getY()
                ? left
                : right);
      }
    }
    for (PlacedCell bottom : bottoms.values()) {
      BlockPos position = bottom.position();
      put(blocks, position, bottom.piece(), WallCellRole.FOUNDATION);
      int terrainY = nearestGround(ring, ground, position.getX(), position.getZ());
      for (int y = terrainY; y < position.getY(); y++) {
        put(blocks, new BlockPos(position.getX(), y, position.getZ()),
            bottom.piece(), WallCellRole.FOUNDATION);
      }
    }
  }

  private static boolean isClimbPiece(WallBlockPlan.Piece piece) {
    return piece.name().startsWith("LADDER_")
        || piece.name().startsWith("TRAPDOOR_");
  }

  private static boolean isAdjacentToAny(long column, Set<Long> candidates) {
    int x = BlockPos.getX(column);
    int z = BlockPos.getZ(column);
    return candidates.stream().anyMatch(candidate ->
        Math.abs(BlockPos.getX(candidate) - x) <= 1
            && Math.abs(BlockPos.getZ(candidate) - z) <= 1);
  }

  private static long column(BlockPos position) {
    return BlockPos.asLong(position.getX(), 0, position.getZ());
  }

  private static void put(Map<Long, WallBlockPlan> blocks, BlockPos pos,
      WallBlockPlan.Piece piece) {
    put(blocks, pos, piece, WallCellRole.EXACT);
  }

  private static void put(Map<Long, WallBlockPlan> blocks, BlockPos pos,
      WallBlockPlan.Piece piece, WallCellRole role) {
    WallBlockPlan existing = blocks.get(pos.asLong());
    if (existing == null
        || localPriority(role) > localPriority(existing.role())) {
      blocks.put(pos.asLong(), new WallBlockPlan(pos.asLong(), piece, role));
    }
  }

  private static int localPriority(WallCellRole role) {
    return switch (role) {
      case CLEARANCE -> 0;
      case BARRIER -> 1;
      case EXACT -> 2;
      case FOUNDATION -> 3;
    };
  }

  private static int nearestGround(List<Long> ring, List<Integer> ground, int x, int z) {
    int nearest = 0;
    long bestDistance = Long.MAX_VALUE;
    for (int index = 0; index < ring.size(); index++) {
      long dx = (long) BlockPos.getX(ring.get(index)) - x;
      long dz = (long) BlockPos.getZ(ring.get(index)) - z;
      long distance = dx * dx + dz * dz;
      if (distance < bestDistance) {
        nearest = index;
        bestDistance = distance;
      }
    }
    return ground.get(nearest);
  }

  private static AuthoredWoodWallSegments loadBundled() {
    Map<WallSectionKind, Template> templates = new EnumMap<>(WallSectionKind.class);
    load(templates, WallSectionKind.STRAIGHT, "straight.nbt");
    load(templates, WallSectionKind.DIAGONAL, "diagonal.nbt");
    load(templates, WallSectionKind.TERRACE, "terrace.nbt");
    load(templates, WallSectionKind.CORNER_TOWER, "corner_tower.nbt");
    load(templates, WallSectionKind.GATEHOUSE, "gatehouse.nbt");
    return new AuthoredWoodWallSegments(templates);
  }

  private static void load(Map<WallSectionKind, Template> templates,
      WallSectionKind kind, String file) {
    String path = RESOURCE_ROOT + file;
    try (InputStream input = AuthoredWoodWallSegments.class.getClassLoader()
        .getResourceAsStream(path)) {
      if (input == null) {
        Kithkyn.LOGGER.error("Missing authored wall segment {}", path);
        return;
      }
      templates.put(kind, readTemplate(input));
    } catch (IOException | RuntimeException exception) {
      Kithkyn.LOGGER.error("Could not read authored wall segment {}", path, exception);
    }
  }

  private static Template readTemplate(InputStream input) throws IOException {
    CompoundTag root = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
    ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
    ListTag serializedBlocks = root.getList("blocks", Tag.TAG_COMPOUND);
    List<Cell> cells = new ArrayList<>(serializedBlocks.size());
    for (int index = 0; index < serializedBlocks.size(); index++) {
      CompoundTag serialized = serializedBlocks.getCompound(index);
      ListTag pos = serialized.getList("pos", Tag.TAG_INT);
      CompoundTag paletteEntry = palette.getCompound(serialized.getInt("state"));
      WallBlockPlan.Piece piece = pieceFor(paletteEntry);
      if (piece != null) {
        cells.add(new Cell(pos.getInt(0), pos.getInt(1), pos.getInt(2), piece));
      }
    }
    cells.sort(Comparator
        .comparingInt((Cell cell) -> constructionPriority(cell.piece()))
        .thenComparingInt(Cell::y)
        .thenComparingInt(cell -> supportPriority(cell.piece()))
        .thenComparingInt(Cell::x)
        .thenComparingInt(Cell::z));
    ListTag size = root.getList("size", Tag.TAG_INT);
    int routeLength = size.getInt(0);
    int[] baseY = new int[routeLength];
    int[] topY = new int[routeLength];
    java.util.Arrays.fill(baseY, Integer.MAX_VALUE);
    java.util.Arrays.fill(topY, Integer.MIN_VALUE);
    for (Cell cell : cells) {
      if (cell.x() >= 0 && cell.x() < routeLength) {
        baseY[cell.x()] = Math.min(baseY[cell.x()], cell.y());
        topY[cell.x()] = Math.max(topY[cell.x()], cell.y());
      }
    }
    for (int index = 0; index < baseY.length; index++) {
      if (baseY[index] == Integer.MAX_VALUE) {
        baseY[index] = 0;
        topY[index] = 0;
      }
    }
    return new Template(List.copyOf(cells), baseY, topY,
        size.getInt(0), size.getInt(1), size.getInt(2));
  }

  private static int supportPriority(WallBlockPlan.Piece piece) {
    return switch (piece) {
      case POST, BEAM_NORTH_SOUTH, BEAM_EAST_WEST, BODY, WALKWAY -> 0;
      case SLAB, PARAPET, STEP_NORTH, STEP_EAST, STEP_SOUTH, STEP_WEST -> 1;
      default -> 2;
    };
  }

  /** Blocks that attachments depend on must exist before those attachments update. */
  private static int constructionPriority(WallBlockPlan.Piece piece) {
    return switch (piece) {
      case TRAPDOOR_NORTH, TRAPDOOR_EAST, TRAPDOOR_SOUTH, TRAPDOOR_WEST,
          LADDER_NORTH, LADDER_EAST, LADDER_SOUTH, LADDER_WEST,
          LANTERN, LANTERN_HANGING,
          CAMPFIRE_NORTH, CAMPFIRE_EAST, CAMPFIRE_SOUTH, CAMPFIRE_WEST -> 1;
      default -> 0;
    };
  }

  private static WallBlockPlan.Piece pieceFor(CompoundTag paletteEntry) {
    String name = paletteEntry.getString("Name");
    CompoundTag properties = paletteEntry.getCompound("Properties");
    return switch (name) {
      case "minecraft:stripped_oak_log" -> switch (properties.getString("axis")) {
        case "x" -> WallBlockPlan.Piece.BEAM_EAST_WEST;
        case "z" -> WallBlockPlan.Piece.BEAM_NORTH_SOUTH;
        default -> WallBlockPlan.Piece.POST;
      };
      case "minecraft:oak_fence" -> WallBlockPlan.Piece.PARAPET;
      case "minecraft:oak_slab" -> WallBlockPlan.Piece.SLAB;
      case "minecraft:oak_trapdoor" -> WallBlockPlan.trapdoorPiece(
          horizontal(properties.getString("facing")));
      case "minecraft:ladder" -> WallBlockPlan.ladderPiece(
          horizontal(properties.getString("facing")));
      case "minecraft:lantern" -> "true".equals(properties.getString("hanging"))
          ? WallBlockPlan.Piece.LANTERN_HANGING
          : WallBlockPlan.Piece.LANTERN;
      case "minecraft:campfire" -> WallBlockPlan.campfirePiece(
          horizontal(properties.getString("facing")));
      default -> null;
    };
  }

  private static Direction horizontal(String name) {
    Direction direction = Direction.byName(name);
    return direction != null && direction.getAxis().isHorizontal()
        ? direction
        : Direction.NORTH;
  }

  private static Vec tangentAt(List<Long> ring, int index) {
    Vec outgoing = delta(ring, index, next(index, ring.size()));
    if (outgoing.x() != 0 || outgoing.z() != 0) {
      return outgoing;
    }
    return delta(ring, previous(index, ring.size()), index);
  }

  private static Vec delta(List<Long> ring, int from, int to) {
    return new Vec(
        Integer.signum(BlockPos.getX(ring.get(to)) - BlockPos.getX(ring.get(from))),
        Integer.signum(BlockPos.getZ(ring.get(to)) - BlockPos.getZ(ring.get(from))));
  }

  private static int previous(int index, int size) {
    return Math.floorMod(index - 1, size);
  }

  private static int next(int index, int size) {
    return (index + 1) % size;
  }

  private record Cell(int x, int y, int z, WallBlockPlan.Piece piece) {
  }

  private record PlacedCell(BlockPos position, WallBlockPlan.Piece piece) {
  }

  /** Horizontal cells occupied by a rigid feature at one route anchor. */
  Set<Long> footprintAt(List<Long> ring, int index, WallSectionKind kind) {
    Template template = this.templates.get(kind);
    if (template == null || ring.isEmpty()) {
      return Set.of();
    }
    Transform transform;
    int anchorX;
    int anchorZ;
    if (kind == WallSectionKind.GATEHOUSE) {
      transform = Transform.along(tangentAt(ring, index));
      anchorX = GATEHOUSE_ANCHOR_X;
      anchorZ = GATEHOUSE_ANCHOR_Z;
    } else if (kind == WallSectionKind.CORNER_TOWER) {
      Vec incoming = delta(ring, previous(index, ring.size()), index);
      Vec outgoing = delta(ring, index, next(index, ring.size()));
      transform = Transform.corner(incoming, outgoing);
      if (transform == null) {
        return Set.of();
      }
      anchorX = CORNER_ANCHOR_X;
      anchorZ = CORNER_ANCHOR_Z;
    } else {
      return Set.of();
    }
    BlockPos anchor = BlockPos.of(ring.get(index));
    java.util.Set<Long> footprint = new java.util.HashSet<>();
    for (int x = 0; x < template.sizeX(); x++) {
      for (int z = 0; z < template.sizeZ(); z++) {
        Vec offset = transform.apply(x - anchorX, z - anchorZ);
        footprint.add(BlockPos.asLong(anchor.getX() + offset.x(), 0,
            anchor.getZ() + offset.z()));
      }
    }
    return Set.copyOf(footprint);
  }

  private record Template(List<Cell> cells, int[] baseY, int[] topY,
      int sizeX, int sizeY, int sizeZ) {
    int routeLength() {
      return this.baseY.length;
    }

    int baseY(int ordinal) {
      return this.baseY[ordinal];
    }

    int relativeTop(int ordinal) {
      return this.topY[ordinal] - this.baseY[ordinal];
    }
  }

  private record Vec(int x, int z) {
  }

  /** One square-grid rotation or reflection from template space to world space. */
  private record Transform(int xx, int xz, int zx, int zz) {

    private static final List<Transform> SQUARE_SYMMETRIES = List.of(
        new Transform(1, 0, 0, 1),
        new Transform(0, 1, -1, 0),
        new Transform(-1, 0, 0, -1),
        new Transform(0, -1, 1, 0),
        new Transform(-1, 0, 0, 1),
        new Transform(1, 0, 0, -1),
        new Transform(0, 1, 1, 0),
        new Transform(0, -1, -1, 0));

    static Transform along(Vec tangent) {
      if (tangent.x() != 0 && tangent.z() != 0) {
        return diagonal(tangent);
      }
      return new Transform(
          tangent.x(), tangent.z(), -tangent.z(), tangent.x());
    }

    static Transform diagonal(Vec tangent) {
      int x = tangent.x() == 0 ? 1 : tangent.x();
      int z = tangent.z() == 0 ? 1 : tangent.z();
      return new Transform(x, 0, 0, z);
    }

    static Transform corner(Vec incoming, Vec outgoing) {
      for (Transform transform : SQUARE_SYMMETRIES) {
        if (transform.apply(1, 0).equals(incoming)
            && transform.apply(1, 1).equals(outgoing)) {
          return transform;
        }
        if (transform.apply(-1, -1).equals(incoming)
            && transform.apply(-1, 0).equals(outgoing)) {
          return transform;
        }
      }
      return null;
    }

    Vec apply(int x, int z) {
      return new Vec(this.xx * x + this.zx * z, this.xz * x + this.zz * z);
    }

    WallBlockPlan.Piece piece(WallBlockPlan.Piece piece) {
      return switch (piece) {
        case BEAM_EAST_WEST -> beam(apply(1, 0));
        case BEAM_NORTH_SOUTH -> beam(apply(0, 1));
        case TRAPDOOR_NORTH, TRAPDOOR_EAST, TRAPDOOR_SOUTH, TRAPDOOR_WEST ->
            WallBlockPlan.trapdoorPiece(direction(apply(directionVector(piece))));
        case LADDER_NORTH, LADDER_EAST, LADDER_SOUTH, LADDER_WEST ->
            WallBlockPlan.ladderPiece(direction(apply(directionVector(piece))));
        case CAMPFIRE_NORTH, CAMPFIRE_EAST, CAMPFIRE_SOUTH, CAMPFIRE_WEST ->
            WallBlockPlan.campfirePiece(direction(apply(directionVector(piece))));
        default -> piece;
      };
    }

    private Vec apply(Vec vector) {
      return apply(vector.x(), vector.z());
    }

    private static WallBlockPlan.Piece beam(Vec vector) {
      return vector.x() != 0
          ? WallBlockPlan.Piece.BEAM_EAST_WEST
          : WallBlockPlan.Piece.BEAM_NORTH_SOUTH;
    }

    private static Direction direction(Vec vector) {
      if (vector.x() > 0) {
        return Direction.EAST;
      }
      if (vector.x() < 0) {
        return Direction.WEST;
      }
      return vector.z() > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static Vec directionVector(WallBlockPlan.Piece piece) {
      return switch (piece) {
        case TRAPDOOR_NORTH, LADDER_NORTH, CAMPFIRE_NORTH -> new Vec(0, -1);
        case TRAPDOOR_EAST, LADDER_EAST, CAMPFIRE_EAST -> new Vec(1, 0);
        case TRAPDOOR_SOUTH, LADDER_SOUTH, CAMPFIRE_SOUTH -> new Vec(0, 1);
        case TRAPDOOR_WEST, LADDER_WEST, CAMPFIRE_WEST -> new Vec(-1, 0);
        default -> throw new IllegalArgumentException("Piece has no horizontal direction: " + piece);
      };
    }
  }
}
