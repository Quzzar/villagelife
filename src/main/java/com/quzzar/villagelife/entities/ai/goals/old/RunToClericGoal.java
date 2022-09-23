package com.quzzar.villagelife.entities.ai.goals.old;

import java.util.List;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Occupation;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.effect.MobEffects;

public class RunToClericGoal extends Goal {
    public final RealPerson person;
    public RealPerson cleric;

    public RunToClericGoal(RealPerson person) {
        this.person = person;
    }

    @Override
    public boolean canUse() {

        if(person.getHealth() >= person.getMaxHealth()
                || person.getTarget() != null
                || person.hasEffect(MobEffects.REGENERATION)) {
            return false;
        }

        List<RealPerson> list = this.person.level.getEntitiesOfClass(RealPerson.class, this.person.getBoundingBox().inflate(10.0D, 3.0D, 10.0D));
        if (!list.isEmpty()) {
            for (RealPerson mob : list) {
                if (mob != null) {
                    if (mob.getOccupation() == Occupation.CLERIC) {
                        this.cleric = mob;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void tick() {
        person.lookAt(cleric, 30.0F, 30.0F);
        person.getLookControl().setLookAt(cleric, 30.0F, 30.0F);
        if (person.distanceTo(cleric) >= 3.0D) {
            person.getNavigation().moveTo(cleric, 0.5D);
        } else {
            person.getMoveControl().strafe(-1.0F, 0.0F);
            person.getNavigation().stop();
        }
    }
}
