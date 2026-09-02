package com.quzzar.villagelife.savedata;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.WandererPool;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The server-wide village registry, persisted as codec-backed NBT in the
 * overworld's data storage. This object IS the village manager: access it
 * through {@link com.quzzar.villagelife.village.VillageManager#get}.
 */
public class VillageManagerSaveData extends SavedData {

    /** Bump when the layout changes; older formats (including the pre-codec Java-serialized blob) start fresh. */
    public static final int FORMAT_VERSION = 2;

    private static final Codec<Map<String, Village>> VILLAGES_CODEC = Codec.unboundedMap(Codec.STRING, Village.CODEC);

    public static final SavedData.Factory<VillageManagerSaveData> FACTORY = new SavedData.Factory<>(
            VillageManagerSaveData::new, VillageManagerSaveData::load, null);

    private final Map<String, Village> villages = new HashMap<>();

    /** Everyone on the road beyond the horizon: one list for the whole server (docs/population-and-labor.md). */
    private final WandererPool wanderers = new WandererPool(this::setDirty);

    // Runtime-only: the level this registry belongs to, re-attached on access.
    private ServerLevel level;

    // Runtime-only: sites whose founding name is still being generated, so the
    // generation tick cannot found the same site twice while the name is in
    // flight. Touched on the server thread only.
    private final Set<Long> pendingFoundings = new HashSet<>();

    public VillageManagerSaveData() {
    }

    public static VillageManagerSaveData load(CompoundTag tag, HolderLookup.Provider registries) {
        VillageManagerSaveData data = new VillageManagerSaveData();

        int format = tag.getInt("Format");
        if (format < FORMAT_VERSION) {
            if (tag.contains("villages")) {
                Villagelife.LOGGER.error("=======================================================================");
                Villagelife.LOGGER.error("Found village data in the old Java-serialized format. That format is");
                Villagelife.LOGGER.error("no longer supported and cannot be migrated; starting with no villages.");
                Villagelife.LOGGER.error("(Existing villagers and buildings in the world are unaffected, but they");
                Villagelife.LOGGER.error("no longer belong to a registered village.)");
                Villagelife.LOGGER.error("=======================================================================");
            }
            return data;
        }

        VILLAGES_CODEC.parse(NbtOps.INSTANCE, tag.get("Villages"))
                .resultOrPartial(error -> Villagelife.LOGGER.error("Failed to load village data: {}", error))
                .ifPresent(data.villages::putAll);
        if (tag.contains("Wanderers")) {
            WandererPool.CODEC.parse(NbtOps.INSTANCE, tag.get("Wanderers"))
                    .resultOrPartial(error -> Villagelife.LOGGER.error("Failed to load the wanderers beyond the horizon: {}", error))
                    .ifPresent(data.wanderers::load);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Format", FORMAT_VERSION);
        Tag encoded = VILLAGES_CODEC.encodeStart(NbtOps.INSTANCE, Map.copyOf(villages))
                .resultOrPartial(error -> Villagelife.LOGGER.error("Failed to save village data: {}", error))
                .orElse(null);
        if (encoded != null) {
            tag.put("Villages", encoded);
        }
        Tag road = WandererPool.CODEC.encodeStart(NbtOps.INSTANCE, List.copyOf(wanderers.entries()))
                .resultOrPartial(error -> Villagelife.LOGGER.error("Failed to save the wanderers beyond the horizon: {}", error))
                .orElse(null);
        if (road != null) {
            tag.put("Wanderers", road);
        }
        return tag;
    }

    /** Re-binds this registry (and every village in it) to its level. Called by VillageManager on access. */
    public void attach(ServerLevel level) {
        if (this.level != level) {
            this.level = level;
            villages.values().forEach(village -> village.attach(level));
        }
    }

    public void registerVillage(ServerLevelAccessor levelAccess, BlockPos location) {
        registerVillage(levelAccess, location, null);
    }

    /**
     * Founds a village at the site in the given style, or, with none given, in
     * the style the biome there calls for ({@link com.quzzar.villagelife.village.buildings.VillageStyle#fromBiome}).
     */
    public void registerVillage(ServerLevelAccessor levelAccess, BlockPos location,
            @javax.annotation.Nullable com.quzzar.villagelife.village.buildings.VillageStyle style) {
        // One name for life (#60): the LLM name is requested BEFORE the camp is
        // placed, and founding runs when it lands moments later, so the village
        // never carries a provisional name. The wait opens a short window in
        // which VillageGeneration re-offers the same site every tick (the map
        // holds no village there yet), so in-flight sites are remembered and
        // skipped.
        long site = location.asLong();
        if (!pendingFoundings.add(site)) {
            return;
        }
        ServerLevel serverLevel = level != null ? level : levelAccess.getLevel();
        com.quzzar.villagelife.village.VillageNamer.requestFoundingName(serverLevel, location, name -> {
            pendingFoundings.remove(site);
            Village village = new Village(name);
            village.setStyle(style != null ? style
                    : com.quzzar.villagelife.village.buildings.VillageStyle.fromBiome(serverLevel.getBiome(location)));
            village.attach(serverLevel);
            villages.put(village.getID(), village);
            village.initNew(location);
            setDirty();
        });
    }

    /** Runs every second, driven by the overworld tick handler. */
    public void tick(ServerLevel level) {
        attach(level);
        villages.values().forEach(village -> village.update(level));
        if (!villages.isEmpty()) {
            setDirty();
        }
    }

    public Village getVillage(String uuid) {
        return villages.get(uuid);
    }

    /** The village whose town center is closest to the given position, or null if none exist. */
    public Village getNearestVillage(BlockPos pos) {
        Village nearest = null;
        double nearestDistSqr = Double.MAX_VALUE;
        for (Village village : villages.values()) {
            if (village.getTownCenter() == null) {
                continue;
            }
            double distSqr = pos.distSqr(BlockPos.of(village.getTownCenter().getCenterLocation()));
            if (distSqr < nearestDistSqr) {
                nearestDistSqr = distSqr;
                nearest = village;
            }
        }
        return nearest;
    }

    public Map<String, Village> getVillages() {
        return villages;
    }

    public WandererPool getWanderers() {
        return wanderers;
    }

}
