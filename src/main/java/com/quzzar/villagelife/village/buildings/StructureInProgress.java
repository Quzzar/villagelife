package com.quzzar.villagelife.village.buildings;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import com.google.common.collect.Lists;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.utils.SerialPair;
import com.quzzar.villagelife.village.VillageManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.Palette;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureEntityInfo;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;

public class StructureInProgress implements Serializable {

    private BuildProgress progress;
    private int index;

    private ArrayList<Long> list1;
    private ArrayList<Long> list2;
    private ArrayList<SerialPair<Long, Boolean>> list3;
    private int i;
    private int j;
    private int k;
    private int l;
    private int i1;
    private int j1;

    ///

    private Building building;

    private Rotation rotation;

    private long location1;
    private long location2;

    private Random random;

    private int magicInt;

    public StructureTemplate getStructureTemplate(){
        return VillageManager.getLevelAccessor().getLevel().getStructureManager()
                .getOrCreate(new ResourceLocation(Villagelife.MODID, building.getInfo().getName()));
    }

    public StructurePlaceSettings getStructurePlaceSettings(){
        return new StructurePlaceSettings()
            .setRotation(this.rotation)
            .setRandom(this.random);
    }

    public StructureInProgress(Building building, Random random) {
        
        this.building = building;

        this.progress = BuildProgress.NOT_STARTED;
        this.index = 0;
        this.list1 = null;
        this.list2 = null;
        this.list3 = null;
        this.i = Integer.MAX_VALUE;
        this.j = Integer.MAX_VALUE;
        this.k = Integer.MAX_VALUE;
        this.l = Integer.MIN_VALUE;
        this.i1 = Integer.MIN_VALUE;
        this.j1 = Integer.MIN_VALUE;

        this.random = random;
        this.rotation = building.getRotation();

        this.magicInt = 2;

    }

    public StructureInProgress setOriginLocation(BlockPos location){

        this.building.setOriginLocation(location.asLong());
        
        this.location1 = location.asLong();
        this.location2 = location.asLong();

        return this;

    }

    public BuildProgress getProgress(){
        return this.progress;
    }

    public Rotation getRotation(){
        return this.rotation;
    }

    public Building getBuilding(){
        return this.building;
    }


    // Temp Vars (for building in progress)
    private transient List<Palette> temp_palettesValue;
    private transient List<StructureTemplate.StructureBlockInfo> temp_list;
    private transient List<StructureTemplate.StructureBlockInfo> temp_structBlockInfoList;

    public void startBuilding() {

        // If not started, set to started & paused
        if(progress == BuildProgress.NOT_STARTED){
            boolean success = buildFirstPhase();
            if(success){
                progress = BuildProgress.IN_PROGRESS_PAUSED;
                // No need to setDirty() because it will be executed next
            }
        }

        // If paused, start working again
        if(progress == BuildProgress.IN_PROGRESS_PAUSED){
            progress = BuildProgress.IN_PROGRESS_WORKING;

            // Populate Temp Vars
            if(temp_palettesValue == null){
                StructureTemplate template = getStructureTemplate();
                StructurePlaceSettings settings = getStructurePlaceSettings();
                try {
                    Field palettesField = StructureTemplate.class.getDeclaredField("palettes");
                    palettesField.setAccessible(true);
                    temp_palettesValue = (List<Palette>) palettesField.get(template);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                temp_list = settings.getRandomPalette(temp_palettesValue, BlockPos.of(location1)).blocks();
                temp_structBlockInfoList = StructureTemplate.processBlockInfos(VillageManager.getLevelAccessor(), BlockPos.of(location1), BlockPos.of(location2), settings, temp_list, template);
            }

        }

        // If complete, stop
        if(progress == BuildProgress.COMPLETE){
            stopBuilding();
        }

    }
    public void updateBuilding() {
        if(progress != BuildProgress.IN_PROGRESS_WORKING){ return; }

        if(temp_palettesValue != null){
            
            if(index < temp_structBlockInfoList.size()){

                progressMiddlePhase(temp_structBlockInfoList.get(index));

                index++;

            } else {

                buildLastPhase();
                stopBuilding();
                progress = BuildProgress.COMPLETE;

            }

        }

    }
    public void stopBuilding() {
        if(progress == BuildProgress.IN_PROGRESS_WORKING){
            progress = BuildProgress.IN_PROGRESS_PAUSED;
        }
    }

    private boolean buildFirstPhase() {

        StructureTemplate template = getStructureTemplate();
        StructurePlaceSettings settings = getStructurePlaceSettings();

        // Reflection so we can even do this outside of StructureTemplate in the first place
        List<Palette> palettesValue = null;
        List<StructureEntityInfo> entityInfoListValue = null;
        Vec3i sizeValue = null;
        try {
            Field palettesField = StructureTemplate.class.getDeclaredField("palettes");
            palettesField.setAccessible(true);
            palettesValue = (List<Palette>) palettesField.get(template);

            Field entityInfoListField = StructureTemplate.class.getDeclaredField("entityInfoList");
            entityInfoListField.setAccessible(true);
            entityInfoListValue = (List<StructureEntityInfo>) entityInfoListField.get(template);

            Field sizeField = StructureTemplate.class.getDeclaredField("size");
            sizeField.setAccessible(true);
            sizeValue = (Vec3i) sizeField.get(template);
        } catch (Exception e1) {
            e1.printStackTrace();
        }

        // OG code
        if (palettesValue.isEmpty()) {
            return false;
        } else {
            List<StructureTemplate.StructureBlockInfo> list = settings.getRandomPalette(palettesValue, BlockPos.of(location1))
                    .blocks();
            if ((!list.isEmpty() || !settings.isIgnoreEntities() && !entityInfoListValue.isEmpty())
                    && sizeValue.getX() >= 1 && sizeValue.getY() >= 1 && sizeValue.getZ() >= 1) {
                
                // Changed to Serializable wrappers instead
                list1 = Lists.newArrayListWithCapacity(settings.shouldKeepLiquids() ? list.size() : 0);
                list2 = Lists.newArrayListWithCapacity(settings.shouldKeepLiquids() ? list.size() : 0);
                list3 = Lists.newArrayListWithCapacity(list.size());
                i = Integer.MAX_VALUE;
                j = Integer.MAX_VALUE;
                k = Integer.MAX_VALUE;
                l = Integer.MIN_VALUE;
                i1 = Integer.MIN_VALUE;
                j1 = Integer.MIN_VALUE;

                return true;
            } else {
                return false;
            }
        }
    }

    private void progressMiddlePhase(StructureTemplate.StructureBlockInfo structBlockInfo) {

        ServerLevelAccessor levelAccess = VillageManager.getLevelAccessor();
        StructurePlaceSettings settings = getStructurePlaceSettings();

        // OG code
        BlockPos blockpos = structBlockInfo.pos;
        if (settings.getBoundingBox() == null || settings.getBoundingBox().isInside(blockpos)) {
            FluidState fluidstate = settings.shouldKeepLiquids() ? levelAccess.getFluidState(blockpos) : null;
            BlockState blockstate = structBlockInfo.state.mirror(settings.getMirror())
                    .rotate(settings.getRotation());
            if (structBlockInfo.nbt != null) {
                BlockEntity blockentity = levelAccess.getBlockEntity(blockpos);
                Clearable.tryClear(blockentity);
                levelAccess.setBlock(blockpos, Blocks.BARRIER.defaultBlockState(), 20);
            }

            if (levelAccess.setBlock(blockpos, blockstate, magicInt)) {
                i = Math.min(i, blockpos.getX());
                j = Math.min(j, blockpos.getY());
                k = Math.min(k, blockpos.getZ());
                l = Math.max(l, blockpos.getX());
                i1 = Math.max(i1, blockpos.getY());
                j1 = Math.max(j1, blockpos.getZ());
                list3.add(SerialPair.of(blockpos.asLong(), (structBlockInfo.nbt != null)));// Edited
                if (structBlockInfo.nbt != null) {
                    BlockEntity blockentity1 = levelAccess.getBlockEntity(blockpos);
                    if (blockentity1 != null) {
                        if (blockentity1 instanceof RandomizableContainerBlockEntity) {
                            structBlockInfo.nbt.putLong("LootTableSeed", random.nextLong());
                        }

                        blockentity1.load(structBlockInfo.nbt);
                    }
                }

                if (fluidstate != null) {
                    if (blockstate.getFluidState().isSource()) {
                        list2.add(blockpos.asLong());// Edited
                    } else if (blockstate.getBlock() instanceof LiquidBlockContainer) {
                        ((LiquidBlockContainer) blockstate.getBlock()).placeLiquid(levelAccess, blockpos,
                                blockstate, fluidstate);
                        if (!fluidstate.isSource()) {
                            list1.add(blockpos.asLong());// Edited
                        }
                    }
                }
            }
        }

    }

    private void buildLastPhase() {

        ServerLevelAccessor levelAccess = VillageManager.getLevelAccessor();
        StructureTemplate template = getStructureTemplate();
        StructurePlaceSettings settings = getStructurePlaceSettings();

        // More reflection to get private method
        Method addEntitiesToWorldMethod = null;
        try {
            addEntitiesToWorldMethod = StructureTemplate.class.getDeclaredMethod("addEntitiesToWorld",
                    ServerLevelAccessor.class, BlockPos.class, StructurePlaceSettings.class);
            addEntitiesToWorldMethod.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // OG code
        boolean flag = true;
        Direction[] adirection = new Direction[] { Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH,
                Direction.WEST };

        while (flag && !list1.isEmpty()) {
            flag = false;
            Iterator<Long> iterator = list1.iterator();// Edited

            while (iterator.hasNext()) {
                BlockPos blockpos3 = BlockPos.of(iterator.next());
                FluidState fluidstate2 = levelAccess.getFluidState(blockpos3);

                for (int i2 = 0; i2 < adirection.length && !fluidstate2.isSource(); ++i2) {
                    BlockPos blockpos1 = blockpos3.relative(adirection[i2]);
                    FluidState fluidstate1 = levelAccess.getFluidState(blockpos1);
                    if (fluidstate1.isSource() && !list2.contains(blockpos1.asLong())) {
                        fluidstate2 = fluidstate1;
                    }
                }

                if (fluidstate2.isSource()) {
                    BlockState blockstate1 = levelAccess.getBlockState(blockpos3);
                    Block block = blockstate1.getBlock();
                    if (block instanceof LiquidBlockContainer) {
                        ((LiquidBlockContainer) block).placeLiquid(levelAccess, blockpos3, blockstate1, fluidstate2);
                        flag = true;
                        iterator.remove();
                    }
                }
            }
        }

        if (i <= l) {
            if (!settings.getKnownShape()) {
                DiscreteVoxelShape discretevoxelshape = new BitSetDiscreteVoxelShape(l - i + 1, i1 - j + 1, j1 - k + 1);
                int k1 = i;
                int l1 = j;
                int j2 = k;

                for (SerialPair<Long, Boolean> pair1 : list3) {// Edited
                    BlockPos blockpos2 = BlockPos.of(pair1.getFirst());
                    discretevoxelshape.fill(blockpos2.getX() - k1, blockpos2.getY() - l1, blockpos2.getZ() - j2);
                }

                StructureTemplate.updateShapeAtEdge(levelAccess, magicInt, discretevoxelshape, k1, l1, j2);
            }

            for (SerialPair<Long, Boolean> pair : list3) {// Edited
                BlockPos blockpos4 = BlockPos.of(pair.getFirst());
                if (!settings.getKnownShape()) {
                    BlockState blockstate2 = levelAccess.getBlockState(blockpos4);
                    BlockState blockstate3 = Block.updateFromNeighbourShapes(blockstate2, levelAccess, blockpos4);
                    if (blockstate2 != blockstate3) {
                        levelAccess.setBlock(blockpos4, blockstate3, magicInt & -2 | 16);
                    }

                    levelAccess.blockUpdated(blockpos4, blockstate3.getBlock());
                }

                if (pair.getSecond()) {// Edited
                    BlockEntity blockentity2 = levelAccess.getBlockEntity(blockpos4);
                    if (blockentity2 != null) {
                        blockentity2.setChanged();
                    }
                }
            }
        }

        if (!settings.isIgnoreEntities()) {
            try {
                addEntitiesToWorldMethod.invoke(template, levelAccess, BlockPos.of(location1), settings);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    }

}
