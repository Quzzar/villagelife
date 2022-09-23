package com.quzzar.villagelife.entities.ai.goals;

import java.util.List;

import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class WorkOnWoodcuttingGoal extends Goal {

    protected final float STRIP_PERCENT = 0.3F;

    protected RealPerson person;
    protected BlockPos workLocation;

    protected int breakTime;
    protected int lastBreakProgress = -1;
    protected boolean hasAcquiredWood;

    public WorkOnWoodcuttingGoal(RealPerson person) {
        this.person = person;
        this.workLocation = LocationManager.getJobLocation(person);
    }

    @Override
    public boolean canUse() {

        BlockState blockstate = person.level.getBlockState(workLocation);
        if(blockstate.isAir() && person.level.getBlockState(workLocation.below()).is(BlockTags.DIRT)){
            
            if(person.getRandom().nextFloat() < 0.01F){
                person.level.setBlock(workLocation, Blocks.OAK_SAPLING.defaultBlockState(), 2);
            }

            return false;
        }

        return blockstate.is(BlockTags.LOGS_THAT_BURN) && !shouldInterrupt();
    }

    @Override
    public boolean canContinueToUse() {
        return this.breakTime <= getBlockChopTime() && !shouldInterrupt();
    }

    @Override
    public void start() {
        this.breakTime = 0;
    }

    @Override
    public void stop() {
        this.person.level.destroyBlockProgress(this.person.getId(), this.workLocation, -1);
        this.hasAcquiredWood = false;
    }

    @Override
    public void tick() {


        if(this.workLocation.distSqr(person.blockPosition()) > 6.0D){
            this.person.getNavigation().moveTo(this.workLocation.getX(), this.workLocation.getY(), this.workLocation.getZ(), 0.5D);
            return;
        } else {
            this.person.getNavigation().stop();
            this.person.getLookControl().setLookAt(this.workLocation.getX(), this.workLocation.getY(), this.workLocation.getZ(), 30.0F, 30.0F);
        }
        
        if (this.person.getRandom().nextInt(2) == 0) {
            if (!this.person.swinging) {
                this.person.swing(this.person.getUsedItemHand());
            }
        }

        ++this.breakTime;
        int i = (int) ((float) this.breakTime / (float) getBlockChopTime() * 10.0F);
        if (i != this.lastBreakProgress) {
            this.person.level.destroyBlockProgress(this.person.getId(), this.workLocation, i);
            this.lastBreakProgress = i;
        }

        if (this.breakTime >= getBlockChopTime() && !this.hasAcquiredWood) {

            if(!this.person.level.isClientSide){
                BlockState blockstate = person.level.getBlockState(workLocation);

                List<ItemStack> items = Block.getDrops(blockstate, (ServerLevel) this.person.level, this.workLocation, this.person.level.getBlockEntity(this.workLocation), this.person, this.person.getMainHandItem());

                // If block was strippable, chance to replace resulting item with resulting stripped item.
                BlockState strippedBlockState = AxeItem.getAxeStrippingState(blockstate);
                if(strippedBlockState != null){
                  if(this.person.getRandom().nextFloat() < STRIP_PERCENT){

                    for(int j = 0; j < items.size(); j++){
                      if(items.get(j).getItem() == blockstate.getBlock().asItem()){
                        items.set(j, new ItemStack(strippedBlockState.getBlock().asItem(), items.get(j).getCount()));
                      }
                    }

                  }
                }
                
                //Utils.insertItems(LocationManager.getNearestContainer(this.person), items, this.person);
                this.person.addItems(items);

                this.hasAcquiredWood = true;
                
                /*
                this.person.getMainHandItem().hurtAndBreak(1, this.person, (p_40992_) -> {
                    p_40992_.broadcastBreakEvent(EquipmentSlot.MAINHAND);
                });*/
                this.person.level.playSound((Player) null, this.workLocation.getX(), this.workLocation.getY(), this.workLocation.getZ(), blockstate.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F, this.person.getRandom().nextFloat() * 0.4F + 0.8F);
            }

        }

    }

    protected int getBlockChopTime(){
        return 100;
    }

    protected boolean shouldInterrupt(){
        return this.person.getLastHurtByMob() != null
                || this.person.isFreezing()
                || this.person.isOnFire()
                || this.person.getLevel().isNight()
                || this.person.isInterrupted();
    }

}
