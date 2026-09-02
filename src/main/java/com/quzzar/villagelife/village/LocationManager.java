package com.quzzar.villagelife.village;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.buildings.Building;
import com.quzzar.villagelife.village.buildings.BuildingUpgrade;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

public class LocationManager {
    
    public static BlockPos getJobLocation(RealPerson person){

        Village village = person.getVillage();
        if(village == null){ return BlockPos.ZERO; }

        JobAssignment job = village.getJobAssignment(person.getUUID());
        if(job == null){ return BlockPos.ZERO; }

        Building building = village.getBuilding(job.getBuildingUUID());
        if(building == null){ return BlockPos.ZERO; }
        // A workplace being rebuilt one level up has no usable station: the
        // worker keeps the job and waits at the campfire until it is ready.
        if(village.isBeingRebuilt(job.getBuildingUUID())){ return BlockPos.ZERO; }

        int foundCount = 0;
        for(Entry<Long, Occupation> entry : building.getInfo().getWorkLocations().entrySet()) {
            if(entry.getValue() == job.getOccupation()){
                if(foundCount == job.getStationIndex()){
                    return BlockPos.of(building.getOriginLocation()).offset(BlockPos.of(entry.getKey()).rotate(building.getRotation()));
                }
            }
            foundCount++;
        }
        Villagelife.LOGGER.debug("Couldn't find job index");
        return BlockPos.ZERO;

    }

    public static BlockPos getBedLocation(RealPerson person){

        Village village = person.getVillage();
        if(village == null){ return BlockPos.ZERO; }

        BedAssignment bed = village.getBedAssignment(person.getUUID());
        if(bed == null){ return BlockPos.ZERO; }

        Building building = village.getBuilding(bed.getBuildingUUID());
        if(building == null){ return BlockPos.ZERO; }
        // Same for a bed: the house is a building site until the upgrade finishes.
        if(village.isBeingRebuilt(bed.getBuildingUUID())){ return BlockPos.ZERO; }

        int foundCount = 0;
        for(long longloc : building.getInfo().getBedLocations()) {
            if(foundCount == bed.getBedIndex()){
                return BlockPos.of(building.getOriginLocation()).offset(BlockPos.of(longloc).rotate(building.getRotation()));
            }
            foundCount++;
        }
        Villagelife.LOGGER.debug("Couldn't find bed index");
        return BlockPos.ZERO;

    }

    public static BlockPos getVillageCenter(RealPerson person){

        Village village = person.getVillage();
        if(village == null){ return BlockPos.ZERO; }
        return BlockPos.of(village.getTownCenter().getCenterLocation());

    }

    @Nullable
    public static Building getJobBuilding(RealPerson person){

        Village village = person.getVillage();
        if(village == null){ return null; }

        JobAssignment job = village.getJobAssignment(person.getUUID());
        if(job == null){ return null; }
        
        return village.getBuilding(job.getBuildingUUID());

    }

    /**
     * The container positions of the worker's own workplace, in world space, in
     * the order the structure declares them. Empty when the worker has no job
     * building or it declares none; a caller that can settle for any village
     * container falls back to {@link #getNearestContainerPos} itself.
     */
    public static List<BlockPos> getJobContainerPositions(RealPerson person){

        Building building = getJobBuilding(person);
        if(building == null || building.getInfo() == null){ return List.of(); }

        ArrayList<BlockPos> positions = new ArrayList<>();
        BlockPos origin = BlockPos.of(building.getOriginLocation());
        for(Long local : building.getInfo().getContainerLocations()) {
            positions.add(origin.offset(BlockPos.of(local).rotate(building.getRotation())));
        }
        return positions;

    }

    @Nullable
    /** Where the nearest village container is, for a worker who needs to walk to it. */
    public static BlockPos getNearestContainerPos(RealPerson person){

        Village village = person.getVillage();
        if(village == null){ return null; }

        BlockPos location = village.getNearestContainer(BlockPos.containing(person.getEyePosition()));
        return location == BlockPos.ZERO ? null : location;

    }

    public static Container getNearestContainer(RealPerson person){

        Village village = person.getVillage();
        if(village == null){ return null; }

        BlockPos location = village.getNearestContainer(BlockPos.containing(person.getEyePosition()));
        if(location == BlockPos.ZERO){ return null; }

        BlockEntity entity = person.level().getBlockEntity(location);
        if(entity instanceof Container){
            return (Container) entity;
        }

        Villagelife.LOGGER.debug(location.toShortString());

        Villagelife.LOGGER.debug("No container at location");
        return null;

    }

    /** A building's way in: the cell just outside its lowest door, and the box the building stands in. */
    public record Entrance(BlockPos doorstep, BoundingBox bounds) {
        public boolean contains(BlockPos pos) {
            return bounds.isInside(pos);
        }
    }

    /**
     * Where to walk to get into a building: the cell just outside its lowest
     * door. A path aimed straight at something indoors stalls against the
     * nearest outside wall when the door is on the far side, because the
     * pathfinder's budget runs out on the open ground before it finds the way
     * round (the level-3 house at Wildflower Downs, whose door faced away from
     * the village: everyone stopped under the upstairs chest, outside). From
     * the doorstep the rest is a dozen nodes. Null when the building has no
     * door, its footprint is unknown, or its chunks are not resident: this
     * never pages a chunk in.
     */
    @Nullable
    public static Entrance getEntrance(ServerLevel level, Building building){

        BoundingBox local = BuildingUpgrade.footprintOf(level, building);
        if(local == null){ return null; }
        BlockPos origin = BlockPos.of(building.getOriginLocation());
        BoundingBox bounds = local.moved(origin.getX(), origin.getY(), origin.getZ());
        for(BlockPos corner : List.of(
                new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()),
                new BlockPos(bounds.maxX(), bounds.minY(), bounds.minZ()),
                new BlockPos(bounds.minX(), bounds.minY(), bounds.maxZ()),
                new BlockPos(bounds.maxX(), bounds.minY(), bounds.maxZ()))) {
            if(!level.hasChunkAt(corner)){ return null; }
        }

        BlockPos door = null;
        for(BlockPos pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            BlockState state = level.getBlockState(pos);
            if(state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                    && (door == null || pos.getY() < door.getY())){
                door = pos.immutable();
            }
        }
        if(door == null){ return null; }

        // A door sits in a wall; of its two neighbours the one farther from the
        // building's middle is the outside.
        BlockPos centre = bounds.getCenter();
        Direction facing = level.getBlockState(door).getValue(DoorBlock.FACING);
        BlockPos front = door.relative(facing);
        BlockPos back = door.relative(facing.getOpposite());
        return new Entrance(front.distSqr(centre) >= back.distSqr(centre) ? front : back, bounds);

    }

}
