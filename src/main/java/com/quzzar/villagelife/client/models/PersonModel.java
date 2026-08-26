package com.quzzar.villagelife.client.models;

import com.quzzar.villagelife.entities.Person;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;

public class PersonModel extends PlayerModel<Person> {
    public PersonModel(ModelPart part) {
        super(part, false);
    }
    
    @Override
    public void setupAnim(Person entityIn, float limbSwing, float limbSwingAmount, float ageInTicks,
            float netbipedHeadYaw, float bipedHeadPitch) {
        super.setupAnim(entityIn, limbSwing, limbSwingAmount, ageInTicks, netbipedHeadYaw, bipedHeadPitch);
        if (entityIn.getMainArm() == HumanoidArm.RIGHT) {
            this.eatingAnimationRightHand(InteractionHand.MAIN_HAND, entityIn, ageInTicks);
            this.eatingAnimationLeftHand(InteractionHand.OFF_HAND, entityIn, ageInTicks);
        } else {
            this.eatingAnimationRightHand(InteractionHand.OFF_HAND, entityIn, ageInTicks);
            this.eatingAnimationLeftHand(InteractionHand.MAIN_HAND, entityIn, ageInTicks);
        }
    }
    
    public static LayerDefinition createMesh() {
        MeshDefinition meshdefinition = PlayerModel.createMesh(CubeDeformation.NONE, false);
        return LayerDefinition.create(meshdefinition, 64, 64);
     }

    public void eatingAnimationRightHand(InteractionHand hand, Person entity, float ageInTicks) {
        ItemStack itemstack = entity.getItemInHand(hand);
        boolean drinkingoreating = itemstack.getUseAnimation() == UseAnim.EAT
                || itemstack.getUseAnimation() == UseAnim.DRINK;
        if (entity.isEating() && drinkingoreating
                || entity.getUseItemRemainingTicks() > 0 && drinkingoreating && entity.getUsedItemHand() == hand) {
            this.rightArm.yRot = -0.5F;
            this.rightArm.xRot = -1.3F;
            this.rightArm.zRot = Mth.cos(ageInTicks) * 0.1F;
            this.head.xRot = Mth.cos(ageInTicks) * 0.2F;
            this.head.yRot = 0.0F;
            this.hat.copyFrom(head);
        }
    }

    public void eatingAnimationLeftHand(InteractionHand hand, Person entity, float ageInTicks) {
        ItemStack itemstack = entity.getItemInHand(hand);
        boolean drinkingoreating = itemstack.getUseAnimation() == UseAnim.EAT
                || itemstack.getUseAnimation() == UseAnim.DRINK;
        if (entity.isEating() && drinkingoreating
                || entity.getUseItemRemainingTicks() > 0 && drinkingoreating && entity.getUsedItemHand() == hand) {
            this.leftArm.yRot = 0.5F;
            this.leftArm.xRot = -1.3F;
            this.leftArm.zRot = Mth.cos(ageInTicks) * 0.1F;
            this.head.xRot = Mth.cos(ageInTicks) * 0.2F;
            this.head.yRot = 0.0F;
            this.hat.copyFrom(head);
        }
    }
}
