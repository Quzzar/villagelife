package com.quzzar.villagelife.entities.ai.goals;

import java.util.List;

import com.google.common.collect.ImmutableSet;
import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;

public class WorkInMineGoal extends Goal {

    private static final int RADIUS = 2;
    protected int breakTime;
    protected int lastBreakProgress = -1;
    protected boolean hasBrokenBlock = false;

    protected RealPerson person;
    protected final BlockPos workLocation;
    protected final Rotation workRotation;

    protected Block block = null;
    protected BlockPos currentOffset = null;
    private int inwardOffset = 1;

    public WorkInMineGoal(RealPerson person) {
        this.person = person;
        this.workLocation = LocationManager.getJobLocation(person);
        if(this.workLocation != BlockPos.ZERO){
            this.workRotation = LocationManager.getJobBuilding(person).getRotation();
        } else {
            this.workRotation = Rotation.NONE;
        }
    }

    @Override
    public boolean canUse() {
        locateNextBlock();
        return this.block != null && this.currentOffset != null && !shouldInterrupt();
    }

    @Override
    public boolean canContinueToUse() {
        return this.breakTime <= getBlockBreakTime() && !shouldInterrupt();
    }

    @Override
    public void start() {
        this.breakTime = 0;
    }

    @Override
    public void stop() {
        this.person.level.destroyBlockProgress(this.person.getId(), getBlockPos(), -1);
        this.block = null;
        this.currentOffset = null;
        this.inwardOffset = 1;
        this.hasBrokenBlock = false;
    }

    @Override
    public void tick() {

        final BlockPos blockPos = getBlockPos();
        if(this.workLocation.distSqr(person.blockPosition()) > 10.0D){
            this.person.getNavigation().moveTo(this.workLocation.getX(), this.workLocation.getY(), this.workLocation.getZ(), 0.5D);
            return;
        } else {
            this.person.getLookControl().setLookAt(blockPos.getX(), blockPos.getY(), blockPos.getZ(), 30.0F, 30.0F);
        }
        
        if (this.person.getRandom().nextInt(2) == 0) {
            if (!this.person.swinging) {
                this.person.swing(this.person.getUsedItemHand());
            }
        }

        ++this.breakTime;
        int i = (int) ((float) this.breakTime / (float) getBlockBreakTime() * 10.0F);
        if (i != this.lastBreakProgress) {
            this.person.level.destroyBlockProgress(this.person.getId(), blockPos, i);
            this.lastBreakProgress = i;
        }

        if (this.breakTime >= getBlockBreakTime() && !this.hasBrokenBlock) {

            if(!this.person.level.isClientSide){
                List<ItemStack> items = Block.getDrops(this.block.defaultBlockState(), (ServerLevel) this.person.level, blockPos, this.person.level.getBlockEntity(blockPos), this.person, this.person.getMainHandItem());
                this.person.level.removeBlock(blockPos, false);

                //Utils.insertItems(LocationManager.getNearestContainer(this.person), items, this.person);
                this.person.addItems(items);

                this.hasBrokenBlock = true;
                
                /*
                this.person.getMainHandItem().hurtAndBreak(1, this.person, (p_40992_) -> {
                    p_40992_.broadcastBreakEvent(EquipmentSlot.MAINHAND);
                });*/
                this.person.level.playSound((Player) null, blockPos.getX(), blockPos.getY(), blockPos.getZ(), this.block.defaultBlockState().getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F, this.person.getRandom().nextFloat() * 0.4F + 0.8F);
            }

        }

    }

    protected BlockPos getBlockPos(){
        return workLocation.offset(currentOffset.rotate(workRotation));
    }

    protected int getBlockBreakTime(){ // TODO, pickaxe in hand should make it faster (include enchants)
        return (int) (block.defaultDestroyTime()*10);
    }

    protected boolean shouldInterrupt(){
        return this.person.getLastHurtByMob() != null
                || this.person.isFreezing()
                || this.person.isOnFire()
                || this.person.getLevel().isNight()
                || this.person.isInterrupted();
    }

    protected void locateNextBlock(){
        if(this.workLocation == BlockPos.ZERO){ return; }

        if(currentOffset == null){
            this.currentOffset = new BlockPos(-1*(RADIUS+1), -1, RADIUS-1);
        }

        do {
            
            this.currentOffset = currentOffset.offset(1, 0, 0);

            if(currentOffset.getX() >= RADIUS+1){
                this.currentOffset = new BlockPos(-1*RADIUS, currentOffset.getY(), currentOffset.getZ()-1);
            }
            if(currentOffset.getZ() <= -1*(RADIUS+1 + inwardOffset)){
                this.currentOffset = new BlockPos(-1*RADIUS, currentOffset.getY()-1, 1-inwardOffset);
                inwardOffset++;
            }

            this.block = this.person.level.getBlockState(getBlockPos()).getBlock(); //

        } while (this.block == Blocks.AIR || this.block == Blocks.TORCH || this.block == Blocks.WALL_TORCH || this.block == Blocks.LANTERN);

        if(this.block == Blocks.WATER
                || this.block == Blocks.LAVA
                || this.block == Blocks.BEDROCK
                || (this.block.defaultBlockState().requiresCorrectToolForDrops() && !person.getMainHandItem().isCorrectToolForDrops(this.block.defaultBlockState()))){

            // Neat idea but creates lag
            if(this.block == Blocks.WATER){

              ItemStack spongeItem = this.person.removeItem(Items.SPONGE, 1);
              if(spongeItem.getCount() == 1){
                this.person.level.setBlock(getBlockPos(), Blocks.SPONGE.defaultBlockState(), 2);
              }

            }


            this.block = null;
            this.currentOffset = null;
            this.inwardOffset = 1;
            this.hasBrokenBlock = false;
            // TODO; make log of problem with the mine
            // if ran into spider webs, lava, water, etc = make note of that for quest


        }

    }

}
