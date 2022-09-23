package com.quzzar.villagelife.village.buildings.old;

/*
import java.util.Random;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.utils.SerialPair;
import com.quzzar.villagelife.village.buildings.BuildProgress;
import com.quzzar.villagelife.village.buildings.Building;
import com.quzzar.villagelife.village.buildings.BuildingInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.Palette;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureEntityInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

import com.google.common.collect.Lists;

import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;

public class SlowlyBuildStructure {

    private BuildStructSaveData data;

    private ServerLevelAccessor levelAccess;

    private StructureTemplate template;
    private StructurePlaceSettings settings;

    private Rotation rotation;

    private BlockPos location;
    private BlockPos location1;
    private BlockPos location2;

    private String templateName;
    private Random random;

    private int magicInt;

    public SlowlyBuildStructure(ServerLevelAccessor levelAccess, Building building) {
        this(levelAccess, building.getInfo(), BlockPos.of(building.getOriginLocation()));
    }

    public SlowlyBuildStructure(ServerLevelAccessor levelAccess, BuildingInfo info, BlockPos location) {
        this.data = levelAccess.getLevel().getDataStorage().computeIfAbsent(BuildStructSaveData::load, BuildStructSaveData::new,
                Villagelife.MODID + "~" + info.getName() + "~" + location.getX() + "~" + location.getY() + "~" + location.getZ());

        if (data.getSeed() == 0L) {
            data.setSeed(new Random().nextLong());
            data.setDirty();
        }

        Villagelife.LOGGER.debug("Slowly Structure, Seed: " + data.getSeed());

        this.random = new Random(data.getSeed());

        this.templateName = info.getName();
        this.location = new BlockPos(location);

        this.levelAccess = levelAccess;
        this.rotation = Rotation.getRandom(this.random);
        this.template = levelAccess.getLevel().getStructureManager()
                .getOrCreate(new ResourceLocation(Villagelife.MODID, info.getName()));
        this.settings = new StructurePlaceSettings()
                .setRotation(this.rotation)
                .setRandom(this.random);

        BlockPos centerOffset = this.template.getBoundingBox(this.settings, BlockPos.ZERO).getCenter();
        centerOffset = centerOffset.subtract(new Vec3i(0, centerOffset.getY()-1, 0));
        location = location.offset(centerOffset.multiply(-1));
        
        this.location1 = location;
        this.location2 = location;

        this.magicInt = 2;

    }

    public BuildProgress getProgress(){
        return BuildProgress.fromInt(this.data.getProgress());
    }

    public Rotation getRotation(){
        return this.rotation;
    }

    public Building toBuilding(){
        return new Building(location, templateName, rotation);
    }


    // Temp Vars (for building in progress)
    private boolean temps_populated = false;
    private ArrayList<Long> temp_list1;
    private ArrayList<Long> temp_list2;
    private ArrayList<SerialPair<Long, Boolean>> temp_list3;
    private List<Palette> temp_palettesValue;
    private List<StructureTemplate.StructureBlockInfo> temp_list;
    private List<StructureTemplate.StructureBlockInfo> temp_structBlockInfoList;

    public void startBuilding() {

        // If not started, set to started & paused
        if(data.getProgress() == 0){
            boolean success = buildFirstPhase();
            if(success){
                data.setProgress(2);
                // No need to setDirty() because it will be executed next
            }
        }

        // If paused, start working again
        if(data.getProgress() == 2){
            data.setProgress(1);
            data.setDirty();

            // Populate Temp Vars
            temp_list1 = data.getList1();
            temp_list2 = data.getList2();
            temp_list3 = data.getList3();

            temp_palettesValue = null;
            try {
                Field palettesField = StructureTemplate.class.getDeclaredField("palettes");
                palettesField.setAccessible(true);
                temp_palettesValue = (List<Palette>) palettesField.get(template);
            } catch (Exception e) {
                e.printStackTrace();
            }
            temp_list = settings.getRandomPalette(temp_palettesValue, location1).blocks();
            temp_structBlockInfoList = StructureTemplate.processBlockInfos(levelAccess, location1, location2, settings, temp_list, template);
            temps_populated = true;

        }

        // If complete, stop
        if(data.getProgress() == 3){
            stopBuilding();
        }

    }
    public void updateBuilding() {
        if(data.getProgress() != 1){ return; }

        if(temps_populated){
            
            if(data.getIndex() < temp_structBlockInfoList.size()){

                progressMiddlePhase(
                        temp_list1,
                        temp_list2,
                        temp_list3,
                        temp_structBlockInfoList.get(data.getIndex()));

                data.setIndex(data.getIndex()+1);
                data.setDirty();

            } else {

                buildLastPhase();
                stopBuilding();
                data.setProgress(3);
                data.setDirty();

            }

        }

    }
    public void stopBuilding() {
        if(data.getProgress() == 1){
            data.setProgress(2);

            // Save Temp Vars
            data.setList1(temp_list1);
            data.setList2(temp_list2);
            data.setList3(temp_list3);

            // Set temp vars to null just in case
            temp_list1 = null;
            temp_list2 = null;
            temp_list3 = null;
            temp_palettesValue = null;
            temp_list = null;
            temp_structBlockInfoList = null;

            temps_populated = false;

            data.setDirty();

        }
    }

    private boolean buildFirstPhase() {

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
            List<StructureTemplate.StructureBlockInfo> list = settings.getRandomPalette(palettesValue, location1)
                    .blocks();
            if ((!list.isEmpty() || !settings.isIgnoreEntities() && !entityInfoListValue.isEmpty())
                    && sizeValue.getX() >= 1 && sizeValue.getY() >= 1 && sizeValue.getZ() >= 1) {
                
                // Changed to Serializable wrappers instead
                ArrayList<Long> list1 = Lists.newArrayListWithCapacity(settings.shouldKeepLiquids() ? list.size() : 0);
                ArrayList<Long> list2 = Lists.newArrayListWithCapacity(settings.shouldKeepLiquids() ? list.size() : 0);
                ArrayList<SerialPair<Long, Boolean>> list3 = Lists.newArrayListWithCapacity(list.size());
                int i = Integer.MAX_VALUE;
                int j = Integer.MAX_VALUE;
                int k = Integer.MAX_VALUE;
                int l = Integer.MIN_VALUE;
                int i1 = Integer.MIN_VALUE;
                int j1 = Integer.MIN_VALUE;

                // Save data
                data.setList1(list1);
                data.setList2(list2);
                data.setList3(list3);
                data.setI(i);
                data.setJ(j);
                data.setK(k);
                data.setL(l);
                data.setI1(i1);
                data.setJ1(j1);

                data.setDirty();

                return true;
            } else {
                return false;
            }
        }
    }

    private void progressMiddlePhase(ArrayList<Long> list1, ArrayList<Long> list2, ArrayList<SerialPair<Long, Boolean>> list3, StructureTemplate.StructureBlockInfo structBlockInfo) {

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
                data.setI(Math.min(data.getI(), blockpos.getX()));
                data.setJ(Math.min(data.getJ(), blockpos.getY()));
                data.setK(Math.min(data.getK(), blockpos.getZ()));
                data.setL(Math.max(data.getL(), blockpos.getX()));
                data.setI1(Math.max(data.getI1(), blockpos.getY()));
                data.setJ1(Math.max(data.getJ1(), blockpos.getZ()));
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

        // No need to setDirty() because it will be called just after this method executes

    }

    private void buildLastPhase() {

        ArrayList<Long> list1 = data.getList1();
        ArrayList<Long> list2 = data.getList2();
        ArrayList<SerialPair<Long, Boolean>> list3 = data.getList3();

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

        if (data.getI() <= data.getL()) {
            if (!settings.getKnownShape()) {
                DiscreteVoxelShape discretevoxelshape = new BitSetDiscreteVoxelShape(data.getL() - data.getI() + 1, data.getI1() - data.getJ() + 1, data.getJ1() - data.getK() + 1);
                int k1 = data.getI();
                int l1 = data.getJ();
                int j2 = data.getK();

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
                addEntitiesToWorldMethod.invoke(template, levelAccess, location1, settings);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // No need to setDirty() because it will be called just after this method executes

    }

}

*/