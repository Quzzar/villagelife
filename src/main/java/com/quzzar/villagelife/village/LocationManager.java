package com.quzzar.villagelife.village;

import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.buildings.Building;

import net.minecraft.core.BlockPos;
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

    @Nullable
    public static Container getNearestContainer(RealPerson person){

        Village village = person.getVillage();
        if(village == null){ return null; }

        BlockPos location = village.getNearestContainer(person.eyeBlockPosition());
        if(location == BlockPos.ZERO){ return null; }

        BlockEntity entity = person.getLevel().getBlockEntity(location);
        if(entity instanceof Container){
            return (Container) entity;
        }

        person.getLevel().setBlock(location, Blocks.EMERALD_BLOCK.defaultBlockState(), 2);
        Villagelife.LOGGER.debug(location.toShortString());

        Villagelife.LOGGER.debug("No container at location");
        return null;

    }

}
