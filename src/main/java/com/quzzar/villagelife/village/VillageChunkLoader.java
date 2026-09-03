package com.quzzar.villagelife.village;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.quzzar.villagelife.Villagelife;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;

/**
 * Keeps a village's chunks loaded and ticking when no player is near, so it
 * keeps building, mining, farming, and defending itself unattended
 * (docs/village-loading.md).
 *
 * <p>One {@link TicketController} owns every village's tickets. Each village is
 * one owner (a UUID derived from its id), holding the set of chunks it currently
 * wants loaded: its footprint, a perimeter, and a bubble around each roaming
 * resident. Every reconcile diffs the village's freshly computed desire against
 * what is held and forces or unforces only the delta. Tickets are requested with
 * ticking, so entities run: villagers work, guards fight, and mobs spawn.
 *
 * <p>The desired set is derived state, not saved state. On world load the
 * validation callback drops every ticket this controller had, and the per-second
 * reconcile rebuilds from scratch, so a village that no longer qualifies (the
 * mode changed, or a hybrid village's grace ran out) holds nothing.
 */
public final class VillageChunkLoader {

  /** Chunks of perimeter held around the village's own footprint. */
  public static final int PERIMETER_CHUNKS = 2;
  /** Chunks of bubble held around each resident who roams beyond the footprint. */
  public static final int MEMBER_BUBBLE_CHUNKS = 1;
  /** Minecraft days a hybrid village stays loaded after a player's last visit. */
  public static final int HYBRID_GRACE_DAYS = 6;
  /** The grace window in ticks: {@value #HYBRID_GRACE_DAYS} days of 24000 ticks. */
  public static final long HYBRID_GRACE_TICKS = HYBRID_GRACE_DAYS * 24000L;
  /** Blocks of slack on the visit check, so standing in the perimeter counts as a visit. */
  public static final int VISIT_PADDING = PERIMETER_CHUNKS * 16;

  private static final ResourceLocation CONTROLLER_ID =
      ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "village");

  /** The one controller, set when the mod bus fires registration. Null before then. */
  private static TicketController controller;

  /** Chunks currently forced per village id, so a reconcile can diff against them. */
  private static final Map<String, Set<Long>> held = new HashMap<>();

  private VillageChunkLoader() {
  }

  /** Registers the controller on the mod event bus (wired from the mod constructor). */
  public static void onRegisterTicketControllers(RegisterTicketControllersEvent event) {
    controller = new TicketController(CONTROLLER_ID, VillageChunkLoader::validateTickets);
    event.register(controller);
  }

  /**
   * Drops every ticket this controller saved, on world load, so nothing stale
   * survives a restart or a config change. The reconcile pass rebuilds what the
   * active mode actually wants within a second of the village's first tick.
   */
  private static void validateTickets(ServerLevel level, TicketHelper helper) {
    for (UUID owner : new ArrayList<>(helper.getEntityTickets().keySet())) {
      helper.removeAllTickets(owner);
    }
    for (BlockPos owner : new ArrayList<>(helper.getBlockTickets().keySet())) {
      helper.removeAllTickets(owner);
    }
  }

  /**
   * Brings the forced chunks in line with what the village wants right now,
   * forcing and unforcing only the difference. A village that wants nothing (the
   * mode is off, or a hybrid village has gone dormant) ends up holding nothing.
   */
  public static void reconcile(ServerLevel level, Village village) {
    if (controller == null) {
      return; // registration has not run yet; nothing can be forced
    }
    Set<Long> desired = village.desiredLoadedChunks(level);
    Set<Long> current = held.computeIfAbsent(village.getID(), key -> new HashSet<>());
    if (current.equals(desired)) {
      if (current.isEmpty()) {
        held.remove(village.getID());
      }
      return;
    }
    UUID owner = ownerFor(village.getID());
    for (long chunk : desired) {
      if (!current.contains(chunk)) {
        controller.forceChunk(level, owner, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), true, true);
      }
    }
    for (long chunk : current) {
      if (!desired.contains(chunk)) {
        controller.forceChunk(level, owner, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false, true);
      }
    }
    if (desired.isEmpty()) {
      held.remove(village.getID());
    } else {
      current.clear();
      current.addAll(desired);
    }
  }

  /** How many chunks this village currently holds forced. Read-only, for diagnostics. */
  public static int heldChunkCount(String villageId) {
    Set<Long> current = held.get(villageId);
    return current == null ? 0 : current.size();
  }

  /** Unforces everything a village held, when it is unmade. */
  public static void release(ServerLevel level, String villageId) {
    Set<Long> current = held.remove(villageId);
    if (controller == null || current == null || current.isEmpty()) {
      return;
    }
    UUID owner = ownerFor(villageId);
    for (long chunk : current) {
      controller.forceChunk(level, owner, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false, true);
    }
  }

  /** A stable per-village ticket owner derived from the village id. */
  private static UUID ownerFor(String villageId) {
    return UUID.nameUUIDFromBytes(("villagelife:village:" + villageId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
