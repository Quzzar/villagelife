package com.quzzar.villagelife.village;

import com.quzzar.villagelife.configuration.VillagelifeConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Founds villagelife villages during ordinary play, on a grid spaced like vanilla's
 * villages, so a fresh world fills with living villages instead of the vanilla ones the
 * datapack turns off. This is founding, not a static structure: villages reshape terrain
 * and run a live simulation, so they cannot be placed during raw chunk generation. Instead
 * this runs on the server tick and founds a site the moment a player has explored up to it -
 * exactly when the ground is loaded and safe to build on.
 *
 * <p>Gated by {@link VillagelifeConfig#GenerateVillages}: off means no villages appear in the
 * world (a village can still be founded by hand with {@code /villagelife create-village}).
 */
public final class VillageGeneration {

    private VillageGeneration() {}

    /** Chunks between candidate sites, matching vanilla villages (spacing 34). */
    private static final int SPACING = 34;
    /** How far a site is kept from its region's far edge, matching vanilla (separation 8). */
    private static final int SEPARATION = 8;
    /** Our own placement salt, so our grid is stable per world but distinct. */
    private static final long SALT = 0x76_69_6C_6CL; // "vill"

    /** No new village is founded within this many blocks of an existing one. */
    private static final int MIN_SEPARATION_BLOCKS = SPACING * 16 / 2;

    /**
     * Each second, for every player, founds the nearest unoccupied village site whose ground
     * has loaded. At most one founding per call, so the terrain work never spikes.
     */
    public static void tick(ServerLevel level) {
        if (!VillagelifeConfig.GenerateVillages) {
            return;
        }
        long seed = level.getSeed();
        for (ServerPlayer player : level.players()) {
            ChunkPos here = player.chunkPosition();
            int regionX = Math.floorDiv(here.x, SPACING);
            int regionZ = Math.floorDiv(here.z, SPACING);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (tryFoundInRegion(level, seed, regionX + dx, regionZ + dz)) {
                        return;
                    }
                }
            }
        }
    }

    /** Chunks out from the site that must be loaded before founding (covers the ~35-block camp reach). */
    private static final int FOOTPRINT_CHUNK_RADIUS = 3;

    /** Founds this region's village if its footprint is loaded, dry, and not already settled. */
    private static boolean tryFoundInRegion(ServerLevel level, long seed, int regionX, int regionZ) {
        ChunkPos site = villageChunk(seed, regionX, regionZ);
        BlockPos column = new BlockPos(site.getMiddleBlockX(), level.getSeaLevel(), site.getMiddleBlockZ());
        // Require the whole camp FOOTPRINT loaded, not just the site chunk. Founding levels the
        // ground under the camp, and an ungenerated neighbour column reads as the world floor -
        // which fills a giant dirt pillar at the village edge (the "settling rough / no ground
        // under X" advisory). Waiting until a player has genuinely generated the footprint makes
        // the leveller read real terrain everywhere.
        if (!footprintLoaded(level, column)) {
            return false;
        }
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, column);
        if (level.getFluidState(surface.below()).is(FluidTags.WATER)) {
            return false; // don't drop a village on an ocean or lake
        }
        Village nearest = VillageManager.get(level).getNearestVillage(surface);
        if (nearest != null && nearest.getTownCenter() != null
                && surface.distSqr(BlockPos.of(nearest.getTownCenter().getCenterLocation()))
                    < (double) MIN_SEPARATION_BLOCKS * MIN_SEPARATION_BLOCKS) {
            return false; // a village already stands near this site
        }
        VillageManager.get(level).registerVillage(level, surface);
        return true;
    }

    /** True only when every chunk within FOOTPRINT_CHUNK_RADIUS of the site is genuinely loaded. */
    private static boolean footprintLoaded(ServerLevel level, BlockPos center) {
        for (int cx = -FOOTPRINT_CHUNK_RADIUS; cx <= FOOTPRINT_CHUNK_RADIUS; cx++) {
            for (int cz = -FOOTPRINT_CHUNK_RADIUS; cz <= FOOTPRINT_CHUNK_RADIUS; cz++) {
                if (!level.isLoaded(center.offset(cx * 16, 0, cz * 16))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The deterministic candidate chunk for a region: one jittered site per SPACING-chunk
     * cell, seeded by the world seed so it is stable across sessions. Uses the same region
     * hashing constants as vanilla structure placement, so our villages sit on a grid of the
     * same density as vanilla's.
     */
    private static ChunkPos villageChunk(long seed, int regionX, int regionZ) {
        RandomSource random = RandomSource.create(seed + regionX * 341873128712L + regionZ * 132897987541L + SALT);
        int offsetX = random.nextInt(SPACING - SEPARATION);
        int offsetZ = random.nextInt(SPACING - SEPARATION);
        return new ChunkPos(regionX * SPACING + offsetX, regionZ * SPACING + offsetZ);
    }
}
