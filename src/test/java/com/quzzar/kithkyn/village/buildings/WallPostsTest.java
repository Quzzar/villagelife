package com.quzzar.kithkyn.village.buildings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class WallPostsTest {

  @Test
  void postsFollowTheDefensiveStaffingOrder() {
    List<Long> ring = WallRoute.aroundBox(0, 64, 0, 64);
    Set<Long> gates = Set.of(
        BlockPos.asLong(32, 0, 0),
        BlockPos.asLong(64, 0, 32),
        BlockPos.asLong(32, 0, 64),
        BlockPos.asLong(0, 0, 32));
    List<Integer> ground = Collections.nCopies(ring.size(), 64);
    List<Integer> deck = WallTerraces.deckProfile(ground, WallTier.WOOD.height());
    WallProject wall = WallProject.completed(
        ring, gates, ground, deck, WallTier.WOOD, VillageStyle.PLAINS, Set.of());

    List<WallPost> posts = WallPosts.plan(wall);
    int towerCount = WallFeaturePlacement.towerAnchors(ring, Set.of()).size();

    assertEquals(gates.size() * 3 + towerCount, posts.size());
    assertEquals(Collections.nCopies(gates.size(), WallPost.Duty.GATE_SWORD_PRIMARY),
        posts.subList(0, gates.size()).stream().map(WallPost::duty).toList());
    assertEquals(Collections.nCopies(towerCount, WallPost.Duty.WATCHTOWER_CROSSBOW),
        posts.subList(gates.size(), gates.size() + towerCount).stream()
            .map(WallPost::duty).toList());
    assertEquals(Collections.nCopies(gates.size(), WallPost.Duty.GATE_CROSSBOW),
        posts.subList(gates.size() + towerCount, gates.size() * 2 + towerCount).stream()
            .map(WallPost::duty).toList());
    assertEquals(Collections.nCopies(gates.size(), WallPost.Duty.GATE_SWORD_SECONDARY),
        posts.subList(gates.size() * 2 + towerCount, posts.size()).stream()
            .map(WallPost::duty).toList());
  }

  @Test
  void everyElevatedPostHasSupportAndHeadroomInWoodAndStone() {
    for (WallTier tier : WallTier.values()) {
      List<Long> ring = WallRoute.aroundBox(0, 64, 0, 64);
      Set<Long> gates = Set.of(BlockPos.asLong(32, 0, 0));
      List<Integer> ground = Collections.nCopies(ring.size(), 64);
      List<Integer> deck = WallTerraces.deckProfile(ground, tier.height());
      WallProject wall = WallProject.completed(
          ring, gates, ground, deck, tier, VillageStyle.PLAINS, Set.of());
      Set<Long> occupied = wall.plannedBlocks().stream()
          .map(WallBlockPlan::position)
          .collect(Collectors.toSet());

      for (WallPost post : WallPosts.plan(wall)) {
        assertFalse(occupied.contains(post.position().asLong()),
            () -> tier + " post intersects the wall at " + post.position());
        assertFalse(occupied.contains(post.position().above().asLong()),
            () -> tier + " post has no headroom at " + post.position());
        if (post.duty().usesCrossbow()) {
          assertTrue(occupied.contains(post.position().below().asLong()),
              () -> tier + " elevated post has no support at " + post.position());
        }
      }
    }
  }

  @Test
  void eachGateGetsTwoDistinctGroundPosts() {
    List<Long> ring = WallRoute.aroundBox(0, 48, 0, 48);
    long gate = BlockPos.asLong(24, 0, 0);
    List<Integer> ground = Collections.nCopies(ring.size(), 64);
    List<Integer> deck = WallTerraces.deckProfile(ground, WallTier.WOOD.height());
    WallProject wall = WallProject.completed(
        ring, Set.of(gate), ground, deck, WallTier.WOOD, VillageStyle.PLAINS, Set.of());
    List<WallPost> gateGuards = WallPosts.plan(wall).stream()
        .filter(post -> post.anchor() == gate && !post.duty().usesCrossbow())
        .toList();

    assertEquals(2, gateGuards.size());
    assertNotEquals(gateGuards.get(0).position(), gateGuards.get(1).position());
  }

  @Test
  void excludedTowerCreatesNoJob() {
    List<Long> ring = WallRoute.aroundBox(0, 48, 0, 48);
    long excluded = WallFeaturePlacement.towerAnchors(ring, Set.of()).get(0);
    List<Integer> ground = Collections.nCopies(ring.size(), 64);
    List<Integer> deck = WallTerraces.deckProfile(ground, WallTier.WOOD.height());
    WallProject wall = WallProject.completed(
        ring, Set.of(), ground, deck, WallTier.WOOD, VillageStyle.PLAINS, Set.of(excluded));

    assertTrue(WallPosts.plan(wall).stream().noneMatch(post -> post.anchor() == excluded));
  }
}
