package com.quzzar.kithkyn.village.buildings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

class WallSegmentCatalogIntegrationTest {

  @Test
  void woodenWallUsesTheAuthoredWatchtowerAndGatehouseMotifs() {
    List<Long> ring = WallRoute.aroundBox(0, 64, 0, 64);
    Set<Long> gates = Set.of(
        BlockPos.asLong(32, 0, 0),
        BlockPos.asLong(64, 0, 32),
        BlockPos.asLong(32, 0, 64),
        BlockPos.asLong(0, 0, 32));
    List<Integer> ground = Collections.nCopies(ring.size(), 64);
    List<Integer> deck = WallTerraces.deckProfile(ground, WallTier.WOOD.height());

    List<WallSection> sections = WallSegmentCatalog.builtIn()
        .compile(ring, gates, ground, deck, WallTier.WOOD);
    Set<String> pieces = sections.stream()
        .flatMap(section -> section.blocks().stream())
        .map(block -> block.piece().name())
        .collect(Collectors.toSet());

    assertTrue(pieces.stream().anyMatch(piece -> piece.startsWith("LADDER_")));
    assertTrue(pieces.contains("SLAB"));
    assertTrue(pieces.stream().anyMatch(piece -> piece.startsWith("TRAPDOOR_")));
    assertTrue(pieces.contains("LANTERN"));
    assertTrue(pieces.contains("LANTERN_HANGING"));
    assertTrue(pieces.stream().anyMatch(piece -> piece.startsWith("CAMPFIRE_")));
  }

  @Test
  void woodenGatehousesAreOpenAndContainNoDoors() {
    List<Long> ring = WallRoute.aroundBox(0, 30, 0, 30);
    Set<Long> gates = Set.of(BlockPos.asLong(15, 0, 0));
    List<Integer> ground = Collections.nCopies(ring.size(), 64);
    List<Integer> deck = WallTerraces.deckProfile(ground, WallTier.WOOD.height());
    List<WallBlockPlan> blocks = WallSegmentCatalog.builtIn()
        .compile(ring, gates, ground, deck, WallTier.WOOD).stream()
        .flatMap(section -> section.blocks().stream())
        .toList();
    assertTrue(blocks.stream().noneMatch(block -> block.piece().name().startsWith("DOOR_")),
        "Wooden gates should be open passages, not isolated doors");
  }

  @Test
  void woodenLanternsAlwaysHavePlannedSupport() {
    List<Long> ring = WallRoute.aroundBox(0, 30, 0, 30);
    Set<Long> gates = Set.of(BlockPos.asLong(15, 0, 0));
    List<Integer> ground = Collections.nCopies(ring.size(), 64);
    List<Integer> deck = WallTerraces.deckProfile(ground, WallTier.WOOD.height());
    List<WallBlockPlan> blocks = WallSegmentCatalog.builtIn()
        .compile(ring, gates, ground, deck, WallTier.WOOD).stream()
        .flatMap(section -> section.blocks().stream())
        .toList();
    Set<Long> positions = blocks.stream()
        .map(WallBlockPlan::position)
        .collect(Collectors.toSet());

    for (WallBlockPlan block : blocks) {
      if (block.piece() == WallBlockPlan.Piece.LANTERN) {
        assertTrue(positions.contains(block.pos().below().asLong()),
            () -> "Standing lantern has no planned support at " + block.pos());
      } else if (block.piece() == WallBlockPlan.Piece.LANTERN_HANGING) {
        assertTrue(positions.contains(block.pos().above().asLong()),
            () -> "Hanging lantern has no planned support at " + block.pos());
      }
    }
  }

  @Test
  void woodenLanternsAreEmittedAfterTheirPlannedSupport() {
    List<Long> ring = WallRoute.aroundBox(0, 30, 0, 30);
    Set<Long> gates = Set.of(BlockPos.asLong(15, 0, 0));
    List<Integer> ground = Collections.nCopies(ring.size(), 64);
    List<Integer> deck = WallTerraces.deckProfile(ground, WallTier.WOOD.height());
    List<WallSection> sections = WallSegmentCatalog.builtIn()
        .compile(ring, gates, ground, deck, WallTier.WOOD);

    for (WallSection section : sections) {
      Map<Long, Integer> order = new HashMap<>();
      for (int index = 0; index < section.blocks().size(); index++) {
        order.put(section.blocks().get(index).position(), index);
      }
      for (int index = 0; index < section.blocks().size(); index++) {
        WallBlockPlan block = section.blocks().get(index);
        BlockPos support = switch (block.piece()) {
          case LANTERN -> block.pos().below();
          case LANTERN_HANGING -> block.pos().above();
          default -> null;
        };
        if (support == null) {
          continue;
        }
        Integer supportIndex = order.get(support.asLong());
        assertTrue(supportIndex != null && supportIndex < index,
            () -> block.piece() + " would be placed before its support at " + block.pos());
      }
    }
  }

  @Test
  void overlappingWoodenFeaturesCannotOrphanALantern() {
    List<Long> ring = WallRoute.aroundBox(0, 32, 0, 32);
    Set<Long> gates = WallPreview.cardinalGates(ring, 0, 32, 0, 32);
    List<Integer> ground = java.util.stream.IntStream.range(0, ring.size())
        .map(index -> 64 + Math.floorMod(index / 3, 6))
        .boxed()
        .toList();
    List<Integer> deck = WallTerraces.deckProfile(ground, WallTier.WOOD.height());
    List<WallBlockPlan> blocks = WallSegmentCatalog.builtIn()
        .compile(ring, gates, ground, deck, WallTier.WOOD).stream()
        .flatMap(section -> section.blocks().stream())
        .toList();
    Set<Long> positions = blocks.stream()
        .map(WallBlockPlan::position)
        .collect(Collectors.toSet());

    for (WallBlockPlan block : blocks) {
      BlockPos support = switch (block.piece()) {
        case LANTERN -> block.pos().below();
        case LANTERN_HANGING -> block.pos().above();
        default -> null;
      };
      if (support != null) {
        assertTrue(positions.contains(support.asLong()),
            () -> block.piece() + " lost its support during overlap normalization at "
                + block.pos());
      }
    }
  }

  @Test
  void ordinaryWoodenRunsLightOnlyEveryOtherSection() {
    List<Long> ring = WallRoute.aroundBox(0, 64, 0, 64);
    List<Integer> ground = Collections.nCopies(ring.size(), 64);
    List<Integer> deck = WallTerraces.deckProfile(ground, WallTier.WOOD.height());
    List<WallSection> sections = WallSegmentCatalog.builtIn()
        .compile(ring, Set.of(), ground, deck, WallTier.WOOD, turns(ring));
    int linearSections = 0;
    int litLinearSections = 0;
    for (WallSection section : sections) {
      if (!isLinear(section.kind())) {
        continue;
      }
      linearSections++;
      long lanterns = section.blocks().stream()
          .filter(block -> isLantern(block.piece()))
          .count();
      assertTrue(lanterns <= 1, "An ordinary wall section carries multiple lanterns");
      if (lanterns == 1) {
        litLinearSections++;
      }
    }

    assertTrue(litLinearSections <= Math.ceilDiv(linearSections, 2),
        "Ordinary wall runs are lit more often than every other section");
  }

  @Test
  void authoredFeaturesKeepOnlyTheirOutwardRoofLanterns() {
    List<Long> ring = WallRoute.aroundBox(0, 30, 0, 30);
    long gate = BlockPos.asLong(15, 0, 0);
    int gateIndex = ring.indexOf(gate);
    List<Integer> ground = Collections.nCopies(ring.size(), 64);
    List<Integer> deck = WallTerraces.deckProfile(ground, WallTier.WOOD.height());

    List<WallBlockPlan> gatehouse = AuthoredWoodWallSegments.INSTANCE.blocksFor(
        ring, Set.of(gate), ground, deck, gateIndex, gateIndex + 1,
        WallSectionKind.GATEHOUSE);
    int cornerIndex = ring.indexOf(firstTowerAnchor(ring));
    List<WallBlockPlan> tower = AuthoredWoodWallSegments.INSTANCE.blocksFor(
        ring, Set.of(), ground, deck, cornerIndex, cornerIndex + 1,
        WallSectionKind.CORNER_TOWER);

    assertEquals(10, countLanterns(gatehouse));
    assertEquals(4, countLanterns(tower));
  }

  @Test
  void wetPreferredGateMovesToTheNearestDrySocketOnItsSide() {
    List<Long> ring = WallRoute.aroundBox(0, 40, 0, 40);
    long preferred = BlockPos.asLong(20, 0, 0);

    Set<Long> gates = WallFeaturePlacement.chooseGates(
        ring, Set.of(preferred), candidate -> candidate != preferred);

    assertEquals(1, gates.size());
    long moved = gates.iterator().next();
    assertEquals(0, BlockPos.getZ(moved));
    assertEquals(1, Math.abs(BlockPos.getX(moved) - 20));
  }

  @Test
  void wetCornerCanSuppressItsAuthoredTower() {
    List<Long> ring = WallRoute.aroundBox(0, 30, 0, 30);
    List<Integer> ground = Collections.nCopies(ring.size(), 64);
    List<Integer> deck = WallTerraces.deckProfile(ground, WallTier.WOOD.height());
    long excluded = firstTowerAnchor(ring);
    long normalTowers = WallSegmentCatalog.builtIn()
        .compile(ring, Set.of(), ground, deck, WallTier.WOOD).stream()
        .filter(section -> section.kind() == WallSectionKind.CORNER_TOWER)
        .count();
    long dryTowers = WallSegmentCatalog.builtIn()
        .compile(ring, Set.of(), ground, deck, WallTier.WOOD, Set.of(excluded)).stream()
        .filter(section -> section.kind() == WallSectionKind.CORNER_TOWER)
        .count();

    assertEquals(normalTowers - 1, dryTowers);
  }

  @Test
  void authoredPostFoundationUsesItsExactTerrainColumn() {
    assertEquals(List.of(
        new BlockPos(12, 61, -4),
        new BlockPos(12, 62, -4),
        new BlockPos(12, 63, -4)),
        WallRaiser.foundationPositions(new BlockPos(12, 64, -4), 61));
  }

  @Test
  void exposedSoilAddsOneEmbeddedFoundationCourse() {
    assertEquals(List.of(
        new BlockPos(12, 60, -4),
        new BlockPos(12, 61, -4),
        new BlockPos(12, 62, -4),
        new BlockPos(12, 63, -4)),
        WallRaiser.foundationPositions(new BlockPos(12, 64, -4), 61, true));
  }

  @Test
  void steepTerrainCannotPullTheWallDeckFarAboveLocalGround() {
    List<Integer> ground = new java.util.ArrayList<>();
    for (int index = 0; index < 16; index++) {
      ground.add(60);
    }
    for (int index = 0; index < 16; index++) {
      ground.add(84);
    }

    List<Integer> deck = WallTerraces.deckProfile(ground, WallTier.WOOD.height());

    for (int index = 0; index < ground.size(); index++) {
      assertTrue(deck.get(index) <= ground.get(index) + 5,
          "Steep terrain raised the wall "
              + (deck.get(index) - ground.get(index)) + " blocks above local ground");
    }
  }

  @Test
  void elevatedWatchtowerClimbShaftContinuesDownToTerrain() {
    List<Long> ring = WallRoute.aroundBox(0, 30, 0, 30);
    int corner = ring.indexOf(firstTowerAnchor(ring));
    List<Integer> ground = Collections.nCopies(ring.size(), 60);
    List<Integer> deck = Collections.nCopies(ring.size(), 66);
    int towerBase = 66 - (WallTier.WOOD.height() - 1);
    List<WallBlockPlan> tower = AuthoredWoodWallSegments.INSTANCE.blocksFor(
        ring, Set.of(), ground, deck, corner, corner + 1,
        WallSectionKind.CORNER_TOWER);

    Set<Long> climbColumns = tower.stream()
        .filter(block -> isLadderOrTrapdoor(block.piece()))
        .map(block -> BlockPos.asLong(block.pos().getX(), 0, block.pos().getZ()))
        .collect(Collectors.toSet());
    assertTrue(!climbColumns.isEmpty());
    Set<Long> shaftColumns = tower.stream()
        .filter(block -> isLadderOrTrapdoor(block.piece())
            || block.piece() == WallBlockPlan.Piece.PARAPET
                && block.pos().getY() <= towerBase + 1
                && isAdjacentToAny(block.pos(), climbColumns))
        .map(block -> BlockPos.asLong(block.pos().getX(), 0, block.pos().getZ()))
        .collect(Collectors.toSet());
    for (long column : shaftColumns) {
      int lowest = tower.stream()
          .filter(block -> BlockPos.asLong(
              block.pos().getX(), 0, block.pos().getZ()) == column)
          .filter(block -> isLadderOrTrapdoor(block.piece())
              || block.piece() == WallBlockPlan.Piece.PARAPET)
          .mapToInt(block -> block.pos().getY())
          .min()
          .orElseThrow();
      assertTrue(lowest <= 60,
          () -> "Watchtower climb shaft stops above terrain at "
              + BlockPos.getX(column) + ", " + BlockPos.getZ(column));
    }
  }

  @Test
  void woodenSpecialFeaturesOwnTheirRouteColumns() {
    List<Long> ring = WallRoute.aroundBox(0, 30, 0, 30);
    Set<Long> gates = Set.of(BlockPos.asLong(15, 0, 0));
    List<Integer> ground = Collections.nCopies(ring.size(), 64);
    List<Integer> deck = WallTerraces.deckProfile(ground, WallTier.WOOD.height());
    List<WallSection> sections = WallSegmentCatalog.builtIn()
        .compile(ring, gates, ground, deck, WallTier.WOOD);

    for (WallSection section : sections) {
      if (section.kind() != WallSectionKind.GATEHOUSE
          && section.kind() != WallSectionKind.CORNER_TOWER) {
        continue;
      }
      assertTrue(section.blocks().stream()
              .noneMatch(block -> block.piece() == WallBlockPlan.Piece.BODY),
          () -> section.kind() + " still contains the procedural palisade shell");
    }

    List<WallBlockPlan> blocks = sections.stream()
        .flatMap(section -> section.blocks().stream())
        .toList();
    int gateIndex = ring.indexOf(BlockPos.asLong(15, 0, 0));
    List<WallBlockPlan> gatehouse = AuthoredWoodWallSegments.INSTANCE.blocksFor(
        ring, gates, ground, deck, gateIndex - 2, gateIndex + 3,
        WallSectionKind.GATEHOUSE);
    assertFeatureOwnsVolume(blocks, gatehouse, 64, "gatehouse");

    int cornerIndex = ring.indexOf(BlockPos.asLong(24, 0, 0));
    List<WallBlockPlan> corner = AuthoredWoodWallSegments.INSTANCE.blocksFor(
        ring, gates, ground, deck, cornerIndex, cornerIndex + 1,
        WallSectionKind.CORNER_TOWER);
    assertFeatureOwnsVolume(blocks, corner, 64, "corner tower");
  }

  @Test
  void authoredWoodResolvesThroughTheVillageStyle() {
    WallBlockPlan post = new WallBlockPlan(
        BlockPos.ZERO.asLong(), WallBlockPlan.Piece.POST, WallCellRole.EXACT);
    WallBlockPlan slab = new WallBlockPlan(
        BlockPos.ZERO.asLong(), WallBlockPlan.Piece.SLAB, WallCellRole.EXACT);

    assertEquals(Blocks.STRIPPED_SPRUCE_LOG,
        post.desiredState(WallTier.WOOD, VillageStyle.TAIGA).getBlock());
    assertEquals(Blocks.SPRUCE_SLAB,
        slab.desiredState(WallTier.WOOD, VillageStyle.SNOWY).getBlock());
    assertEquals(Blocks.STRIPPED_ACACIA_LOG,
        post.desiredState(WallTier.WOOD, VillageStyle.SAVANNA).getBlock());
  }

  @Test
  void regionalWallWoodRemainsEligibleForAStoneUpgrade() {
    assertTrue(WoodWallPalette.isWallWood(Blocks.STRIPPED_OAK_LOG));
    assertTrue(WoodWallPalette.isWallWood(Blocks.STRIPPED_SPRUCE_LOG));
    assertTrue(WoodWallPalette.isWallWood(Blocks.ACACIA_FENCE));
    assertTrue(!WoodWallPalette.isWallWood(Blocks.STONE_BRICKS));
  }

  @Test
  void authoredPostsGrowFoundationsDownToSlopedTerrain() {
    List<Long> ring = WallRoute.aroundBox(0, 30, 0, 30);
    long gate = BlockPos.asLong(15, 0, 0);
    int gateIndex = ring.indexOf(gate);
    List<Integer> ground = new java.util.ArrayList<>(
        Collections.nCopies(ring.size(), 60));
    for (int offset = 4; offset < 8; offset++) {
      ground.set(Math.floorMod(gateIndex + offset, ring.size()), 68);
    }
    List<Integer> deck = WallTerraces.deckProfile(ground, WallTier.WOOD.height());

    List<WallSection> sections = WallSegmentCatalog.builtIn()
        .compile(ring, Set.of(gate), ground, deck, WallTier.WOOD);
    List<WallBlockPlan> blocks = sections.stream()
        .flatMap(section -> section.blocks().stream())
        .toList();
    Set<Long> postColumns = blocks.stream()
        .filter(block -> block.piece() == WallBlockPlan.Piece.POST)
        .map(block -> BlockPos.asLong(block.pos().getX(), 0, block.pos().getZ()))
        .collect(Collectors.toCollection(HashSet::new));
    Map<Long, Integer> lowestPlanned = new HashMap<>();
    for (WallBlockPlan block : blocks) {
      long column = BlockPos.asLong(block.pos().getX(), 0, block.pos().getZ());
      lowestPlanned.merge(column, block.pos().getY(), Math::min);
    }

    for (long column : postColumns) {
      int naturalGround = nearestGround(ring, ground,
          BlockPos.getX(column), BlockPos.getZ(column));
      assertTrue(lowestPlanned.get(column) <= naturalGround,
          () -> "Authored post floats above terrain at "
              + BlockPos.getX(column) + ", " + BlockPos.getZ(column));
    }
  }

  @Test
  void authoredRunsFollowTheSmoothedTerraceDeckSocket() {
    List<Long> route = java.util.stream.IntStream.range(0, 7)
        .mapToObj(x -> BlockPos.asLong(x, 0, 0))
        .toList();
    List<Integer> ground = Collections.nCopies(route.size(), 60);
    List<WallBlockPlan> low = AuthoredWoodWallSegments.INSTANCE.blocksFor(
        route, Set.of(), ground, Collections.nCopies(route.size(), 62),
        0, route.size(), WallSectionKind.STRAIGHT);
    List<WallBlockPlan> raised = AuthoredWoodWallSegments.INSTANCE.blocksFor(
        route, Set.of(), ground, Collections.nCopies(route.size(), 66),
        0, route.size(), WallSectionKind.STRAIGHT);

    Map<Long, Integer> lowTops = highestByColumn(low);
    Map<Long, Integer> raisedTops = highestByColumn(raised);
    assertEquals(lowTops.keySet(), raisedTops.keySet());
    for (long column : lowTops.keySet()) {
      assertEquals(lowTops.get(column) + 4, raisedTops.get(column));
    }
  }

  @Test
  void authoredLinearSilhouetteCannotShootMoreThanTwoCoursesAboveItsDeck() {
    List<Long> route = java.util.stream.IntStream.range(0, 7)
        .mapToObj(x -> BlockPos.asLong(x, 0, 0))
        .toList();
    List<Integer> ground = Collections.nCopies(route.size(), 60);
    List<Integer> deck = List.of(62, 62, 62, 62, 63, 63, 63);
    List<WallBlockPlan> blocks = AuthoredWoodWallSegments.INSTANCE.blocksFor(
        route, Set.of(), ground, deck, 0, route.size(), WallSectionKind.STRAIGHT);

    for (WallBlockPlan block : blocks) {
      int routeIndex = BlockPos.getX(block.position());
      assertTrue(block.pos().getY() <= deck.get(routeIndex) + 2,
          () -> "Authored detail shoots above its terrace at " + block.pos());
    }
  }

  @Test
  void authoredSupportsAreEmittedBeforeAttachedDetails() {
    List<Long> ring = WallRoute.aroundBox(0, 30, 0, 30);
    long gate = BlockPos.asLong(15, 0, 0);
    int gateIndex = ring.indexOf(gate);
    List<WallBlockPlan> blocks = AuthoredWoodWallSegments.INSTANCE.blocksFor(
        ring, Set.of(gate), Collections.nCopies(ring.size(), 60),
        Collections.nCopies(ring.size(), 66), gateIndex - 2, gateIndex + 3,
        WallSectionKind.GATEHOUSE);

    int lastSupport = -1;
    int firstAttachment = blocks.size();
    for (int index = 0; index < blocks.size(); index++) {
      if (isAttachment(blocks.get(index).piece())) {
        firstAttachment = Math.min(firstAttachment, index);
      } else {
        lastSupport = index;
      }
    }
    assertTrue(lastSupport < firstAttachment,
        "An attached detail would be built before the authored structure supporting it");
  }

  @Test
  void compiledWallClosesFlatRollingAndCliffTerrain() {
    List<Long> ring = WallRoute.aroundBox(0, 30, 0, 30);
    List<Integer> flat = Collections.nCopies(ring.size(), 64);
    List<Integer> rolling = new java.util.ArrayList<>(ring.size());
    List<Integer> cliff = new java.util.ArrayList<>(ring.size());
    for (int index = 0; index < ring.size(); index++) {
      rolling.add(64 + Math.floorMod(index / 5, 3));
      cliff.add(index >= ring.size() / 4 && index < ring.size() / 2 ? 72 : 62);
    }

    assertClosedShell(ring, flat);
    assertClosedShell(ring, rolling);
    assertClosedShell(ring, cliff);
  }

  @Test
  void arbitraryAreaPreviewPlacesFourCardinalGatesOnTheRoute() {
    List<Long> ring = WallRoute.aroundBox(-20, 20, -30, 30);

    Set<Long> gates = WallPreview.cardinalGates(ring, -20, 20, -30, 30);

    assertEquals(Set.of(
        BlockPos.asLong(0, 0, -30),
        BlockPos.asLong(20, 0, 0),
        BlockPos.asLong(0, 0, 30),
        BlockPos.asLong(-20, 0, 0)), gates);
  }

  @Test
  void wallProjectPersistsItsRegionalStyleAndDefaultsOldSavesToPlains() {
    List<Long> ring = WallRoute.aroundBox(0, 30, 0, 30);
    List<Integer> ground = Collections.nCopies(ring.size(), 64);
    WallProject project = new WallProject(
        ring, Set.of(), ground, WallTier.WOOD, VillageStyle.TAIGA);

    var encoded = WallProject.CODEC.encodeStart(JsonOps.INSTANCE, project).getOrThrow();
    WallProject restored = WallProject.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
    encoded.getAsJsonObject().remove("style");
    WallProject restoredLegacy = WallProject.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

    assertEquals(VillageStyle.TAIGA, restored.getStyle());
    assertEquals(VillageStyle.PLAINS, restoredLegacy.getStyle());
  }

  @Test
  void wallProjectPersistsSuppressedWetTowers() {
    List<Long> ring = WallRoute.aroundBox(0, 30, 0, 30);
    List<Integer> ground = Collections.nCopies(ring.size(), 64);
    long excluded = firstTowerAnchor(ring);
    WallProject project = new WallProject(
        ring, Set.of(), ground, WallTier.WOOD, VillageStyle.PLAINS,
        Set.of(excluded));

    var encoded = WallProject.CODEC.encodeStart(JsonOps.INSTANCE, project).getOrThrow();
    WallProject restored = WallProject.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

    assertEquals(Set.of(excluded), restored.getTowerExclusions());
  }

  private static void assertClosedShell(List<Long> ring, List<Integer> ground) {
    List<Integer> deck = WallTerraces.deckProfile(ground, WallTier.WOOD.height());
    List<WallBlockPlan> blocks = WallSegmentCatalog.builtIn()
        .compile(ring, Set.of(), ground, deck, WallTier.WOOD, turns(ring)).stream()
        .flatMap(section -> section.blocks().stream())
        .toList();
    Set<Long> positions = blocks.stream()
        .map(WallBlockPlan::position)
        .collect(Collectors.toSet());

    assertEquals(blocks.size(), positions.size());
    for (int index = 0; index < ring.size(); index++) {
      int x = BlockPos.getX(ring.get(index));
      int z = BlockPos.getZ(ring.get(index));
      for (int y = WallRaiser.seamFloor(ground, index); y <= deck.get(index); y++) {
        assertTrue(positions.contains(BlockPos.asLong(x, y, z)),
            "Open wall seam at " + x + ", " + y + ", " + z);
      }
    }
  }

  private static Map<Long, Integer> highestByColumn(List<WallBlockPlan> blocks) {
    Map<Long, Integer> highest = new HashMap<>();
    for (WallBlockPlan block : blocks) {
      BlockPos pos = block.pos();
      highest.merge(BlockPos.asLong(pos.getX(), 0, pos.getZ()), pos.getY(), Math::max);
    }
    return highest;
  }

  private static void assertFeatureOwnsVolume(List<WallBlockPlan> compiled,
      List<WallBlockPlan> feature, int baseY, String label) {
    Set<Long> authored = feature.stream()
        .map(WallBlockPlan::position)
        .collect(Collectors.toSet());
    int minX = feature.stream().mapToInt(block -> block.pos().getX()).min().orElseThrow();
    int maxX = feature.stream().mapToInt(block -> block.pos().getX()).max().orElseThrow();
    int minZ = feature.stream().mapToInt(block -> block.pos().getZ()).min().orElseThrow();
    int maxZ = feature.stream().mapToInt(block -> block.pos().getZ()).max().orElseThrow();
    int maxY = feature.stream().mapToInt(block -> block.pos().getY()).max().orElseThrow();

    for (WallBlockPlan block : compiled) {
      BlockPos pos = block.pos();
      if (pos.getX() < minX || pos.getX() > maxX
          || pos.getZ() < minZ || pos.getZ() > maxZ
          || pos.getY() < baseY || pos.getY() > maxY) {
        continue;
      }
      assertTrue(authored.contains(block.position()),
          () -> "Foreign wall cell crosses the " + label + " at " + pos);
    }
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

  private static long firstTowerAnchor(List<Long> ring) {
    for (int index = 0; index < ring.size(); index++) {
      int previous = Math.floorMod(index - 1, ring.size());
      int next = (index + 1) % ring.size();
      int incomingX = Integer.signum(BlockPos.getX(ring.get(index))
          - BlockPos.getX(ring.get(previous)));
      int incomingZ = Integer.signum(BlockPos.getZ(ring.get(index))
          - BlockPos.getZ(ring.get(previous)));
      int outgoingX = Integer.signum(BlockPos.getX(ring.get(next))
          - BlockPos.getX(ring.get(index)));
      int outgoingZ = Integer.signum(BlockPos.getZ(ring.get(next))
          - BlockPos.getZ(ring.get(index)));
      boolean incomingDiagonal = incomingX != 0 && incomingZ != 0;
      boolean outgoingDiagonal = outgoingX != 0 && outgoingZ != 0;
      if ((incomingX != outgoingX || incomingZ != outgoingZ)
          && !incomingDiagonal && outgoingDiagonal) {
        return ring.get(index);
      }
    }
    throw new AssertionError("Expected a turn in the wall route");
  }

  private static Set<Long> turns(List<Long> ring) {
    Set<Long> turns = new HashSet<>();
    for (int index = 0; index < ring.size(); index++) {
      int previous = Math.floorMod(index - 1, ring.size());
      int next = (index + 1) % ring.size();
      int incomingX = Integer.signum(BlockPos.getX(ring.get(index))
          - BlockPos.getX(ring.get(previous)));
      int incomingZ = Integer.signum(BlockPos.getZ(ring.get(index))
          - BlockPos.getZ(ring.get(previous)));
      int outgoingX = Integer.signum(BlockPos.getX(ring.get(next))
          - BlockPos.getX(ring.get(index)));
      int outgoingZ = Integer.signum(BlockPos.getZ(ring.get(next))
          - BlockPos.getZ(ring.get(index)));
      if (incomingX != outgoingX || incomingZ != outgoingZ) {
        turns.add(ring.get(index));
      }
    }
    return Set.copyOf(turns);
  }

  private static boolean isLadderOrTrapdoor(WallBlockPlan.Piece piece) {
    return piece.name().startsWith("LADDER_")
        || piece.name().startsWith("TRAPDOOR_");
  }

  private static boolean isAttachment(WallBlockPlan.Piece piece) {
    return isLadderOrTrapdoor(piece)
        || isLantern(piece)
        || piece.name().startsWith("CAMPFIRE_");
  }

  private static boolean isLantern(WallBlockPlan.Piece piece) {
    return piece == WallBlockPlan.Piece.LANTERN
        || piece == WallBlockPlan.Piece.LANTERN_HANGING;
  }

  private static long countLanterns(List<WallBlockPlan> blocks) {
    return blocks.stream().filter(block -> isLantern(block.piece())).count();
  }

  private static boolean isLinear(WallSectionKind kind) {
    return kind == WallSectionKind.STRAIGHT
        || kind == WallSectionKind.DIAGONAL
        || kind == WallSectionKind.TERRACE;
  }

  private static boolean isAdjacentToAny(BlockPos position, Set<Long> columns) {
    return columns.stream().anyMatch(column ->
        Math.abs(BlockPos.getX(column) - position.getX()) <= 1
            && Math.abs(BlockPos.getZ(column) - position.getZ()) <= 1);
  }
}
