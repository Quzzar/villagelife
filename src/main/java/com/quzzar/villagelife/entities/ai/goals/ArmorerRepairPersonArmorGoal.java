package com.quzzar.villagelife.entities.ai.goals;

import java.util.List;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Occupation;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

public class ArmorerRepairPersonArmorGoal extends Goal {
    private final RealPerson person;
    private RealPerson blacksmith;

    public ArmorerRepairPersonArmorGoal(RealPerson person) {
        this.person = person;
    }

    @Override
    public boolean canUse() {
        List<RealPerson> list = this.person.level.getEntitiesOfClass(RealPerson.class, this.person.getBoundingBox().inflate(10.0D, 3.0D, 10.0D));
        if (!list.isEmpty()) {
            for (RealPerson mob : list) {
                if (mob != null) {
                    boolean isBlackSmith = mob.getOccupation() == Occupation.BLACKSMITH;
                    if (isBlackSmith && person.getTarget() == null) {
                        for (int i = 0; i < person.personEquipInv.getContainerSize(); i++) {
                            ItemStack itemstack = person.personEquipInv.getItem(i);
                            if (itemstack.isDamaged() && itemstack.getDamageValue() >= itemstack.getMaxDamage() / 2) {
                                this.blacksmith = mob;
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void tick() {
        person.getLookControl().setLookAt(blacksmith, 30.0F, 30.0F);
        if (person.distanceTo(blacksmith) >= 2.0D) {
            person.getNavigation().moveTo(blacksmith, 0.5D);
            blacksmith.getNavigation().moveTo(person, 0.5D);
        } else {

            if(blacksmith.tickCount % 20 == 0) {// Every 1 second

                boolean hasRepaired = false;
                for (int i = 0; i < person.personEquipInv.getContainerSize(); i++) {
                    ItemStack itemstack = person.personEquipInv.getItem(i);
                    if (itemstack.isDamaged() && itemstack.getDamageValue() >= itemstack.getMaxDamage() / 2) {
                        itemstack.setDamageValue(itemstack.getDamageValue() - person.getRandom().nextInt(5));
                        hasRepaired = true;
                    }
                }
                if(hasRepaired){
                    float f1 = 1.0F + (blacksmith.getRandom().nextFloat() - blacksmith.getRandom().nextFloat()) * 0.1F;
                    blacksmith.playSound(SoundEvents.ANVIL_USE, 0.5F, f1);
                }

            }
        }
    }
}
