package com.quzzar.villagelife.entities.ai.goals.old;

/*
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class TameWolfGoal extends Goal {

    protected double distance;

    protected RealPerson person;
    protected Wolf wolf;

    private Set<Item> bone;

    public TameWolfGoal(RealPerson person, double distance){
        this.person = person;
        this.distance = distance;
        bone = new HashSet<>(Arrays.asList(Items.BONE));
    }

    @Override
    public boolean canUse() {
        if(!person.personEquipInv.hasAnyOf(bone)){ return false; }

        List<Wolf> list = this.person.level.getEntitiesOfClass(Wolf.class, this.person.getBoundingBox().inflate(distance, distance/3, distance));
        if (!list.isEmpty()) {
            for (Wolf wolf : list) {
                if (wolf != null && wolf.isAlive() && wolf.getOwnerUUID() == null && !wolf.isTame()) {
                    this.wolf = wolf;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void start() {
        ItemStack itemstack = this.person.personEquipInv.removeItemType(Items.BONE, 1);
        if(itemstack.getCount() == 1){
            this.wolf.setOwnerUUID(this.person.getUUID());
            this.wolf.setPersistenceRequired();
        }
    }

    @Override
    public void stop() {
        this.wolf = null;
    }

}
*/