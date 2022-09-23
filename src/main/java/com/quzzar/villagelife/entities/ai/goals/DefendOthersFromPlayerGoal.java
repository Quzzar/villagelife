package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import java.util.List;

import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

public class DefendOthersFromPlayerGoal extends TargetGoal {
    
    private final RealPerson guard;
    private LivingEntity villageAggressorTarget;

    public DefendOthersFromPlayerGoal(RealPerson guardIn) {
        super(guardIn, false, true);
        this.guard = guardIn;
        this.setFlags(EnumSet.of(Goal.Flag.TARGET, Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        AABB axisalignedbb = this.guard.getBoundingBox().inflate(10.0D, 8.0D, 10.0D);
        List<Person> list = guard.level.getEntitiesOfClass(Person.class, axisalignedbb);
        List<Player> list1 = guard.level.getEntitiesOfClass(Player.class, axisalignedbb);
        for (LivingEntity livingentity : list) {
            //Person personentity = (Person) livingentity;
            for (Player playerentity : list1) {

                // TODO
                // If village reputation of player is low enough,
                //this.villageAggressorTarget = playerentity;
            }
        }
        return villageAggressorTarget != null
                && !this.villageAggressorTarget.isSpectator()
                && !((Player) this.villageAggressorTarget).isCreative();
    }

    @Override
    public void start() {
        this.guard.setTarget(this.villageAggressorTarget);
        super.start();
    }
}