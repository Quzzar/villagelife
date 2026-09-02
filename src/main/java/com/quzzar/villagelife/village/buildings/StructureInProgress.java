package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import com.google.common.collect.Lists;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.savedata.PlacedBlockStore;
import com.quzzar.villagelife.village.BlockOwnership;
import com.quzzar.villagelife.utils.VillagelifeCodecs;

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

public class StructureInProgress {

    /** A block this project has already placed, tracked so the finishing pass can update shapes and block entities. */
    public record PlacedBlock(long pos, boolean hasNbt) {
        public static final Codec<PlacedBlock> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.LONG.fieldOf("pos").forGetter(PlacedBlock::pos),
                Codec.BOOL.fieldOf("has_nbt").forGetter(PlacedBlock::hasNbt)
        ).apply(inst, PlacedBlock::new));
    }

    public static final Codec<StructureInProgress> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Building.CODEC.fieldOf("building").forGetter(s -> s.building),
            VillagelifeCodecs.forEnum(BuildProgress.class).fieldOf("progress").forGetter(s -> s.progress),
            Codec.LONG.listOf().optionalFieldOf("prep_break", java.util.List.of()).forGetter(s -> java.util.List.copyOf(s.prepBreak)),
            Codec.LONG.listOf().optionalFieldOf("prep_fill", java.util.List.of()).forGetter(s -> java.util.List.copyOf(s.prepFill)),
            Codec.INT.fieldOf("index").forGetter(s -> s.index),
            Codec.LONG.fieldOf("location").forGetter(s -> s.location1),
            Codec.LONG.fieldOf("location2").forGetter(s -> s.location2),
            Rotation.CODEC.fieldOf("rotation").forGetter(s -> s.rotation),
            Codec.INT.fieldOf("block_flags").forGetter(s -> s.magicInt),
            Codec.LONG.listOf().optionalFieldOf("pending_liquids", List.of()).forGetter(s -> listOrEmpty(s.list1)),
            Codec.LONG.listOf().optionalFieldOf("liquid_sources", List.of()).forGetter(s -> listOrEmpty(s.list2)),
            PlacedBlock.CODEC.listOf().optionalFieldOf("placed_blocks", List.of()).forGetter(s -> listOrEmpty(s.list3)),
            Codec.INT.listOf().fieldOf("bounds").forGetter(s -> List.of(s.i, s.j, s.k, s.l, s.i1, s.j1))
    ).apply(inst, StructureInProgress::fromCodec));

    private static <T> List<T> listOrEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static StructureInProgress fromCodec(Building building, BuildProgress progress,
            java.util.List<Long> prepBreak, java.util.List<Long> prepFill, int index, long location1,
            long location2, Rotation rotation, int magicInt, List<Long> list1, List<Long> list2,
            List<PlacedBlock> list3, List<Integer> bounds) {
        StructureInProgress s = new StructureInProgress(building, new Random());
        // A project can only be saved while its transient placement state is gone, so
        // it resumes as paused; startBuilding() rebuilds the block list from the template.
        s.progress = progress == BuildProgress.IN_PROGRESS_WORKING ? BuildProgress.IN_PROGRESS_PAUSED : progress;
        s.prepBreak = new java.util.ArrayList<>(prepBreak);
        s.prepFill = new java.util.ArrayList<>(prepFill);
        s.index = index;
        s.location1 = location1;
        s.location2 = location2;
        s.rotation = rotation;
        s.magicInt = magicInt;
        s.list1 = new ArrayList<>(list1);
        s.list2 = new ArrayList<>(list2);
        s.list3 = new ArrayList<>(list3);
        if (bounds.size() >= 6) {
            s.i = bounds.get(0);
            s.j = bounds.get(1);
            s.k = bounds.get(2);
            s.l = bounds.get(3);
            s.i1 = bounds.get(4);
            s.j1 = bounds.get(5);
        }
        return s;
    }

    private BuildProgress progress;
    /** Ground work owed before the first structure block goes down (#69). */
    private java.util.ArrayList<Long> prepBreak = new java.util.ArrayList<>();
    private java.util.ArrayList<Long> prepFill = new java.util.ArrayList<>();
    private int index;

    private ArrayList<Long> list1;
    private ArrayList<Long> list2;
    private ArrayList<PlacedBlock> list3;
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

    // Runtime-only: the level this project builds in, re-attached by the owning
    // Village whenever the project is accessed. Never persisted.
    private ServerLevelAccessor level;

    public void attach(ServerLevelAccessor level) {
        this.level = level;
    }

    public StructureTemplate getStructureTemplate(){
        return level.getLevel().getStructureManager()
                .getOrCreate(ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, building.getInfo().getName()));
    }

    public StructurePlaceSettings getStructurePlaceSettings(){
        // Keep-liquids off, as the instant path has it: a block placed into water
        // replaces it rather than becoming waterlogged, so neither a pond beside the
        // site nor the structure's own spill can turn a well's rim into sources.
        return new StructurePlaceSettings()
            .setRotation(this.rotation)
            .setLiquidSettings(net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings.IGNORE_WATERLOGGING)
            .setRandom(net.minecraft.util.RandomSource.create(this.random.nextLong()));
    }

    public StructureInProgress(Building building, Random random) {

        this.building = building;

        // A project opens by gathering its recipe, not by breaking ground: no
        // block is placed and no material is spent until the builder has the
        // whole recipe in hand and it is consumed at commit.
        this.progress = BuildProgress.GATHERING;
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

    /**
     * Ground work this site owes before building can start. Stored now but not
     * begun: the project is still gathering its recipe, and prep waits until the
     * materials are committed (see {@link #commitFromBuilder}).
     */
    public void setPrepWork(SitePreparation.PrepWork work) {
        this.prepBreak = new java.util.ArrayList<>(work.toBreak());
        this.prepFill = new java.util.ArrayList<>(work.toFill());
    }

    public boolean isGathering() {
        return this.progress == BuildProgress.GATHERING;
    }

    /**
     * The atomic commit. When the builder's pack holds the WHOLE recipe, consume
     * exactly the recipe amounts and move the project on to ground work (or
     * straight to building if the site is clear). Until then this does nothing
     * and returns false, so a half-gathered recipe never commits and never pays.
     *
     * This is the only place materials are spent. A builder killed before it
     * returns true drops what they carried and the village is no poorer for the
     * attempt; after it returns true, construction owes nothing and any builder
     * can finish it.
     */
    public boolean commitFromBuilder(net.minecraft.world.Container pack,
            java.util.List<net.minecraft.world.item.ItemStack> recipe) {
        if (this.progress != BuildProgress.GATHERING) {
            return false;
        }
        if (!Materials.covers(Materials.tally(pack), recipe)) {
            return false; // the recipe is not all here yet
        }
        Materials.spend(pack, recipe);
        this.progress = remainingPrepWork() > 0
                ? BuildProgress.PREPARING
                : BuildProgress.NOT_STARTED;
        return true;
    }

    public int remainingPrepWork() {
        return prepBreak.size() + prepFill.size();
    }

    /**
     * One swing of ground work: take away a block that is in the way, or raise
     * a column that sits below the build plane. Cleared blocks go into village
     * storage rather than the ground, because clearing a wooded site is a
     * lumber harvest that happens to also make room. Returns false when the
     * village cannot pay for fill, so the caller can complain rather than spin.
     */
    public boolean prepareStep(com.quzzar.villagelife.village.Village village,
            net.minecraft.world.entity.Entity worker) {
        if (level == null) {
            return true;
        }
        if (!prepBreak.isEmpty()) {
            BlockPos pos = BlockPos.of(prepBreak.remove(prepBreak.size() - 1));
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            if (!state.isAir() && level.getBlockEntity(pos) == null) {
                if (level.getLevel() != null) {
                    for (net.minecraft.world.item.ItemStack drop : net.minecraft.world.level.block.Block
                            .getDrops(state, level.getLevel(), pos, null)) {
                        village.placeItemStackIntoVillage(drop, worker);
                    }
                }
                level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            }
            return true;
        }
        if (!prepFill.isEmpty()) {
            BlockPos pos = BlockPos.of(prepFill.get(prepFill.size() - 1));
            net.minecraft.world.item.ItemStack paid = village.gatherItemStackFromVillage(
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT, 1));
            if (paid.isEmpty()) {
                return false;
            }
            prepFill.remove(prepFill.size() - 1);
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 3);
            return true;
        }
        return true;
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

        if (level == null) {
            return;
        }

        // Ground work first: nothing is placed until the site is ready.
        if(progress == BuildProgress.PREPARING){
            if (remainingPrepWork() > 0) {
                return;
            }
            progress = BuildProgress.NOT_STARTED;
        }

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
                temp_palettesValue = template.palettes;
                temp_list = settings.getRandomPalette(temp_palettesValue, BlockPos.of(location1)).blocks();
                temp_structBlockInfoList = liquidsLast(StructureTemplate.processBlockInfos(level,
                        BlockPos.of(location1), BlockPos.of(location2), settings, temp_list, template));
            }

        }

        // If complete, stop
        if(progress == BuildProgress.COMPLETE){
            stopBuilding();
        }

    }
    public void updateBuilding() {
        if(progress != BuildProgress.IN_PROGRESS_WORKING){ return; }
        if(level == null){ return; }

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

    /** World position of the next block this build will place, or null when none remains. */
    @javax.annotation.Nullable
    public BlockPos peekNextBlockPos() {
        if (temp_structBlockInfoList == null || index >= temp_structBlockInfoList.size()) {
            return null;
        }
        return temp_structBlockInfoList.get(index).pos();
    }

    /** State of the next block this build will place, or null when none remains. */
    @javax.annotation.Nullable
    public BlockState peekNextBlockState() {
        if (temp_structBlockInfoList == null || index >= temp_structBlockInfoList.size()) {
            return null;
        }
        return temp_structBlockInfoList.get(index).state();
    }

    private boolean buildFirstPhase() {

        StructureTemplate template = getStructureTemplate();
        StructurePlaceSettings settings = getStructurePlaceSettings();

        // Opened up via META-INF/accesstransformer.cfg instead of reflection.
        List<Palette> palettesValue = template.palettes;
        List<StructureEntityInfo> entityInfoListValue = template.entityInfoList;
        Vec3i sizeValue = template.size;

        // OG code
        if (palettesValue.isEmpty()) {
            return false;
        } else {
            List<StructureTemplate.StructureBlockInfo> list = settings.getRandomPalette(palettesValue, BlockPos.of(location1))
                    .blocks();
            if ((!list.isEmpty() || !settings.isIgnoreEntities() && !entityInfoListValue.isEmpty())
                    && sizeValue.getX() >= 1 && sizeValue.getY() >= 1 && sizeValue.getZ() >= 1) {

                list1 = Lists.newArrayListWithCapacity(settings.shouldApplyWaterlogging() ? list.size() : 0);
                list2 = Lists.newArrayListWithCapacity(settings.shouldApplyWaterlogging() ? list.size() : 0);
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

    /**
     * The build order with every block that carries a liquid moved to the end.
     * A block-by-block build that set a well's water down before its rim let the
     * pool spread over the ground, and each trapdoor and fence then placed into
     * that spill was waterlogged by the keep-liquids rule and became a source of
     * its own: the well leaked from its rim, and left source blocks around it.
     * Placed last, a liquid only ever meets the blocks that were meant to hold it.
     * The order is a stable partition, so a saved build index still means the
     * same block after a restart.
     */
    private static List<StructureTemplate.StructureBlockInfo> liquidsLast(
            List<StructureTemplate.StructureBlockInfo> infos) {
        List<StructureTemplate.StructureBlockInfo> ordered = new java.util.ArrayList<>(infos.size());
        List<StructureTemplate.StructureBlockInfo> liquids = new java.util.ArrayList<>();
        for (StructureTemplate.StructureBlockInfo info : infos) {
            (info.state().getFluidState().isEmpty() ? ordered : liquids).add(info);
        }
        ordered.addAll(liquids);
        return ordered;
    }

    private void progressMiddlePhase(StructureTemplate.StructureBlockInfo structBlockInfo) {

        ServerLevelAccessor levelAccess = this.level;
        StructurePlaceSettings settings = getStructurePlaceSettings();

        // OG code
        BlockPos blockpos = structBlockInfo.pos();
        if (settings.getBoundingBox() == null || settings.getBoundingBox().isInside(blockpos)) {
            FluidState fluidstate = settings.shouldApplyWaterlogging() ? levelAccess.getFluidState(blockpos) : null;
            BlockState blockstate = structBlockInfo.state().mirror(settings.getMirror())
                    .rotate(settings.getRotation());
            if (structBlockInfo.nbt() != null) {
                BlockEntity blockentity = levelAccess.getBlockEntity(blockpos);
                Clearable.tryClear(blockentity);
                levelAccess.setBlock(blockpos, Blocks.BARRIER.defaultBlockState(), 20);
            }

            if (levelAccess.setBlock(blockpos, blockstate, magicInt)) {
                // The laid block is the village's (docs/block-ownership.md), recorded
                // here as the instant path records its stamp: without it a cabin the
                // builder raised read as a fellable tree to every guard on patrol. A
                // plant the template carries is put down to grow, and is nobody's.
                if (!blockstate.isAir() && !BlockOwnership.isPlanted(blockstate)) {
                    PlacedBlockStore.get(levelAccess.getLevel()).markVillagePlaced(blockpos);
                }
                i = Math.min(i, blockpos.getX());
                j = Math.min(j, blockpos.getY());
                k = Math.min(k, blockpos.getZ());
                l = Math.max(l, blockpos.getX());
                i1 = Math.max(i1, blockpos.getY());
                j1 = Math.max(j1, blockpos.getZ());
                list3.add(new PlacedBlock(blockpos.asLong(), structBlockInfo.nbt() != null));
                if (structBlockInfo.nbt() != null) {
                    BlockEntity blockentity1 = levelAccess.getBlockEntity(blockpos);
                    if (blockentity1 != null) {
                        if (blockentity1 instanceof RandomizableContainerBlockEntity) {
                            structBlockInfo.nbt().putLong("LootTableSeed", random.nextLong());
                        }

                        blockentity1.loadWithComponents(structBlockInfo.nbt(), levelAccess.registryAccess());
                    }
                }

                if (fluidstate != null) {
                    if (blockstate.getFluidState().isSource()) {
                        list2.add(blockpos.asLong());
                    } else if (blockstate.getBlock() instanceof LiquidBlockContainer) {
                        ((LiquidBlockContainer) blockstate.getBlock()).placeLiquid(levelAccess, blockpos,
                                blockstate, fluidstate);
                        if (!fluidstate.isSource()) {
                            list1.add(blockpos.asLong());
                        }
                    }
                }
            }
        }

    }

    private void buildLastPhase() {

        ServerLevelAccessor levelAccess = this.level;
        StructureTemplate template = getStructureTemplate();
        StructurePlaceSettings settings = getStructurePlaceSettings();


        // OG code
        boolean flag = true;
        Direction[] adirection = new Direction[] { Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH,
                Direction.WEST };

        while (flag && !list1.isEmpty()) {
            flag = false;
            Iterator<Long> iterator = list1.iterator();

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

                for (PlacedBlock placed : list3) {
                    BlockPos blockpos2 = BlockPos.of(placed.pos());
                    discretevoxelshape.fill(blockpos2.getX() - k1, blockpos2.getY() - l1, blockpos2.getZ() - j2);
                }

                StructureTemplate.updateShapeAtEdge(levelAccess, magicInt, discretevoxelshape, k1, l1, j2);
            }

            for (PlacedBlock placed : list3) {
                BlockPos blockpos4 = BlockPos.of(placed.pos());
                if (!settings.getKnownShape()) {
                    BlockState blockstate2 = levelAccess.getBlockState(blockpos4);
                    BlockState blockstate3 = Block.updateFromNeighbourShapes(blockstate2, levelAccess, blockpos4);
                    if (blockstate2 != blockstate3) {
                        levelAccess.setBlock(blockpos4, blockstate3, magicInt & -2 | 16);
                    }

                    levelAccess.blockUpdated(blockpos4, blockstate3.getBlock());
                }

                if (placed.hasNbt()) {
                    BlockEntity blockentity2 = levelAccess.getBlockEntity(blockpos4);
                    if (blockentity2 != null) {
                        blockentity2.setChanged();
                    }
                }
            }
        }

        if (!settings.isIgnoreEntities()) {
            template.addEntitiesToWorld(levelAccess, BlockPos.of(location1), settings);
            // Stock a building ships with belongs to the village from its first breath:
            // marked before the hunter's next scan can read the pen as game.
            com.quzzar.villagelife.village.FarmedStock.markAnimalsWithin(levelAccess,
                    template.getBoundingBox(settings, BlockPos.of(location1)));
        }

    }

}
