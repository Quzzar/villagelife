package com.quzzar.kithkyn.village;

import java.util.Set;
import java.util.stream.Collectors;

import com.quzzar.kithkyn.Kithkyn;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The village gathering point: a custom POI over lit campfire states, per the
 * POI research (issue #11). Used purely as a tracked spatial anchor — Village
 * owns the idle roster, so tickets go unused. A doused or broken campfire
 * drops out of the POI index, and the village falls back to its center point.
 */
public class KithkynPoiTypes {

    public static final DeferredRegister<PoiType> POI_TYPES = DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, Kithkyn.MODID);

    public static final DeferredHolder<PoiType, PoiType> CAMPFIRE = POI_TYPES.register("campfire",
            () -> new PoiType(litCampfireStates(), 1, 1));

    private static Set<BlockState> litCampfireStates() {
        return Blocks.CAMPFIRE.getStateDefinition().getPossibleStates().stream()
                .filter(state -> state.getValue(CampfireBlock.LIT))
                .collect(Collectors.toSet());
    }
}
