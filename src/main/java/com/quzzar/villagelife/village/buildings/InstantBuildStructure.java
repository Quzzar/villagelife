package com.quzzar.villagelife.village.buildings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import com.google.common.collect.Lists;
import com.mojang.datafixers.util.Pair;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.village.VillageManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.Palette;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureEntityInfo;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;

public class InstantBuildStructure {

    private ServerLevelAccessor levelAccess;

    private StructureTemplate template;
    private StructurePlaceSettings settings;

    private Building building;
    private Rotation rotation;
    
    private BlockPos location1;
    private BlockPos location2;
    
    private Random random;

    private int magicInt;
    
    public InstantBuildStructure(Building building, Random random) {

        this.levelAccess = VillageManager.getLevelAccessor();

        this.building = building;

        this.random = random;

        this.rotation = building.getRotation();

        this.template = levelAccess.getLevel().getStructureManager()
                .getOrCreate(new ResourceLocation(Villagelife.MODID, building.getInfo().getPath()));
        this.settings = new StructurePlaceSettings()
                .setRotation(this.rotation)
                .setRandom(this.random);

        this.magicInt = 2;

    }

    public InstantBuildStructure setOriginLocation(BlockPos location, HashSet<Long> claimGrid){

        BoundingBox bounds = this.template.getBoundingBox(this.settings, BlockPos.ZERO);

        BlockPos centerOffset = bounds.getCenter();
        centerOffset = centerOffset.subtract(new Vec3i(0, centerOffset.getY()-1, 0));
        location = location.offset(centerOffset.multiply(-1));
        if(this.rotation == Rotation.CLOCKWISE_180){
            location = location.offset(1, 0, 0);
        } else if(this.rotation == Rotation.COUNTERCLOCKWISE_90){
            location = location.offset(0, 0, 1);
        }

        this.building.setOriginLocation(location.asLong());
        this.building.setCenterLocation(location.offset(centerOffset).asLong());
        this.building.setRadius(LocationValidator.getBuildingRadius(bounds));
        
        this.location1 = location;
        this.location2 = location;


        for(int x = bounds.minX(); x <= bounds.maxX(); x++){
          for(int z = bounds.minZ(); z <= bounds.maxZ(); z++){
            VillageManager.getLevelAccessor().setBlock(location.offset(x, 0, z), Blocks.CLAY.defaultBlockState(), 2); // TODO, remove
            claimGrid.add(BlockPos.asLong(location.getX()+x, 0, location.getZ()+z));
          }
        }

        return this;

    }

    public Rotation getRotation(){
        return this.rotation;
    }

    public Building getBuilding(){
        return this.building;
    }

    public boolean buildInstantly() {

        List<Palette> palettesValue = null;
        List<StructureEntityInfo> entityInfoListValue = null;
        Vec3i sizeValue = null;
        Method addEntitiesToWorldMethod = null;
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

            addEntitiesToWorldMethod = StructureTemplate.class.getDeclaredMethod("addEntitiesToWorld",
                    ServerLevelAccessor.class, BlockPos.class, StructurePlaceSettings.class);
            addEntitiesToWorldMethod.setAccessible(true);
        } catch (Exception e1) {
            e1.printStackTrace();
        }

        if (palettesValue.isEmpty()) {
            return false;
        } else {
            List<StructureTemplate.StructureBlockInfo> list = settings.getRandomPalette(palettesValue, location1)
                    .blocks();
            if ((!list.isEmpty() || !settings.isIgnoreEntities() && !entityInfoListValue.isEmpty())
                    && sizeValue.getX() >= 1 && sizeValue.getY() >= 1 && sizeValue.getZ() >= 1) {
                BoundingBox boundingbox = settings.getBoundingBox();
                List<BlockPos> list1 = Lists.newArrayListWithCapacity(settings.shouldKeepLiquids() ? list.size() : 0);
                List<BlockPos> list2 = Lists.newArrayListWithCapacity(settings.shouldKeepLiquids() ? list.size() : 0);
                List<Pair<BlockPos, CompoundTag>> list3 = Lists.newArrayListWithCapacity(list.size());
                int i = Integer.MAX_VALUE;
                int j = Integer.MAX_VALUE;
                int k = Integer.MAX_VALUE;
                int l = Integer.MIN_VALUE;
                int i1 = Integer.MIN_VALUE;
                int j1 = Integer.MIN_VALUE;

                for (StructureTemplate.StructureBlockInfo structuretemplate$structureblockinfo : StructureTemplate
                        .processBlockInfos(levelAccess, location1, location2, settings, list, template)) {
                    BlockPos blockpos = structuretemplate$structureblockinfo.pos;
                    if (boundingbox == null || boundingbox.isInside(blockpos)) {
                        FluidState fluidstate = settings.shouldKeepLiquids() ? levelAccess.getFluidState(blockpos)
                                : null;
                        BlockState blockstate = structuretemplate$structureblockinfo.state.mirror(settings.getMirror())
                                .rotate(settings.getRotation());
                        if (structuretemplate$structureblockinfo.nbt != null) {
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
                            list3.add(Pair.of(blockpos, structuretemplate$structureblockinfo.nbt));
                            if (structuretemplate$structureblockinfo.nbt != null) {
                                BlockEntity blockentity1 = levelAccess.getBlockEntity(blockpos);
                                if (blockentity1 != null) {
                                    if (blockentity1 instanceof RandomizableContainerBlockEntity) {
                                        structuretemplate$structureblockinfo.nbt.putLong("LootTableSeed",
                                                random.nextLong());
                                    }

                                    blockentity1.load(structuretemplate$structureblockinfo.nbt);
                                }
                            }

                            if (fluidstate != null) {
                                if (blockstate.getFluidState().isSource()) {
                                    list2.add(blockpos);
                                } else if (blockstate.getBlock() instanceof LiquidBlockContainer) {
                                    ((LiquidBlockContainer) blockstate.getBlock()).placeLiquid(levelAccess, blockpos,
                                            blockstate, fluidstate);
                                    if (!fluidstate.isSource()) {
                                        list1.add(blockpos);
                                    }
                                }
                            }
                        }
                    }
                }

                boolean flag = true;
                Direction[] adirection = new Direction[] { Direction.UP, Direction.NORTH, Direction.EAST,
                        Direction.SOUTH, Direction.WEST };

                while (flag && !list1.isEmpty()) {
                    flag = false;
                    Iterator<BlockPos> iterator = list1.iterator();

                    while (iterator.hasNext()) {
                        BlockPos blockpos3 = iterator.next();
                        FluidState fluidstate2 = levelAccess.getFluidState(blockpos3);

                        for (int i2 = 0; i2 < adirection.length && !fluidstate2.isSource(); ++i2) {
                            BlockPos blockpos1 = blockpos3.relative(adirection[i2]);
                            FluidState fluidstate1 = levelAccess.getFluidState(blockpos1);
                            if (fluidstate1.isSource() && !list2.contains(blockpos1)) {
                                fluidstate2 = fluidstate1;
                            }
                        }

                        if (fluidstate2.isSource()) {
                            BlockState blockstate1 = levelAccess.getBlockState(blockpos3);
                            Block block = blockstate1.getBlock();
                            if (block instanceof LiquidBlockContainer) {
                                ((LiquidBlockContainer) block).placeLiquid(levelAccess, blockpos3, blockstate1,
                                        fluidstate2);
                                flag = true;
                                iterator.remove();
                            }
                        }
                    }
                }

                if (i <= l) {
                    if (!settings.getKnownShape()) {
                        DiscreteVoxelShape discretevoxelshape = new BitSetDiscreteVoxelShape(l - i + 1, i1 - j + 1,
                                j1 - k + 1);
                        int k1 = i;
                        int l1 = j;
                        int j2 = k;

                        for (Pair<BlockPos, CompoundTag> pair1 : list3) {
                            BlockPos blockpos2 = pair1.getFirst();
                            discretevoxelshape.fill(blockpos2.getX() - k1, blockpos2.getY() - l1,
                                    blockpos2.getZ() - j2);
                        }

                        StructureTemplate.updateShapeAtEdge(levelAccess, magicInt, discretevoxelshape, k1, l1, j2);
                    }

                    for (Pair<BlockPos, CompoundTag> pair : list3) {
                        BlockPos blockpos4 = pair.getFirst();
                        if (!settings.getKnownShape()) {
                            BlockState blockstate2 = levelAccess.getBlockState(blockpos4);
                            BlockState blockstate3 = Block.updateFromNeighbourShapes(blockstate2, levelAccess,
                                    blockpos4);
                            if (blockstate2 != blockstate3) {
                                levelAccess.setBlock(blockpos4, blockstate3, magicInt & -2 | 16);
                            }

                            levelAccess.blockUpdated(blockpos4, blockstate3.getBlock());
                        }

                        if (pair.getSecond() != null) {
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

                return true;
            } else {
                return false;
            }
        }
    }

}