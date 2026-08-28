package com.quzzar.villagelife.client.renderer;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.client.models.PersonModel;
import com.quzzar.villagelife.entities.Gender;
import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.events.PersonClientEvents;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;

public class PersonRenderer extends HumanoidMobRenderer<Person, HumanoidModel<Person>> {

    public PersonRenderer(EntityRendererProvider.Context context) {
        super(context, new PersonModel(context.bakeLayer(PersonClientEvents.PERSON)), 0.5F);
        this.model = new PersonModel(context.bakeLayer(PersonClientEvents.PERSON));

        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
    }

    @Override
    public void render(Person entityIn, float entityYaw, float partialTicks, PoseStack matrixStackIn, MultiBufferSource bufferIn, int packedLightIn) {
        this.setModelVisibilities(entityIn);
        super.render(entityIn, entityYaw, partialTicks, matrixStackIn, bufferIn, packedLightIn);
    }

    /**
     * Renders the name, then the person's role as a smaller gray second line
     * beneath it: their title if they have one, otherwise their occupation.
     * Only RealPersons carry a role, so plain Persons show the name alone.
     */
    @Override
    protected void renderNameTag(Person entity, net.minecraft.network.chat.Component displayName,
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, float partialTick) {
        super.renderNameTag(entity, displayName, poseStack, bufferSource, packedLight, partialTick);
        if (entity instanceof com.quzzar.villagelife.entities.RealPerson person
                && !person.getRoleLabel().isBlank()) {
            poseStack.pushPose();
            poseStack.translate(0.0D, -0.25D, 0.0D);
            super.renderNameTag(entity,
                    net.minecraft.network.chat.Component.literal(person.getRoleLabel())
                            .withStyle(net.minecraft.ChatFormatting.GRAY),
                    poseStack, bufferSource, packedLight, partialTick);
            poseStack.popPose();
        }
    }

    private void setModelVisibilities(Person entityIn) {
        HumanoidModel<Person> guardmodel = this.getModel();
        ItemStack itemstack = entityIn.getMainHandItem();
        ItemStack itemstack1 = entityIn.getOffhandItem();
        guardmodel.setAllVisible(true);
        HumanoidModel.ArmPose bipedmodel$armpose = this.getArmPose(entityIn, itemstack, itemstack1,
                InteractionHand.MAIN_HAND);
        HumanoidModel.ArmPose bipedmodel$armpose1 = this.getArmPose(entityIn, itemstack, itemstack1,
                InteractionHand.OFF_HAND);
        guardmodel.crouching = entityIn.isCrouching();
        if (entityIn.getMainArm() == HumanoidArm.RIGHT) {
            guardmodel.rightArmPose = bipedmodel$armpose;
            guardmodel.leftArmPose = bipedmodel$armpose1;
        } else {
            guardmodel.rightArmPose = bipedmodel$armpose1;
            guardmodel.leftArmPose = bipedmodel$armpose;
        }
    }

    private HumanoidModel.ArmPose getArmPose(Person entityIn, ItemStack itemStackMain, ItemStack itemStackOff,
            InteractionHand handIn) {
        HumanoidModel.ArmPose bipedmodel$armpose = HumanoidModel.ArmPose.EMPTY;
        ItemStack itemstack = handIn == InteractionHand.MAIN_HAND ? itemStackMain : itemStackOff;
        if (!itemstack.isEmpty()) {
            bipedmodel$armpose = HumanoidModel.ArmPose.ITEM;
            if (entityIn.getUseItemRemainingTicks() > 0) {
                UseAnim useaction = itemstack.getUseAnimation();
                switch (useaction) {
                case BLOCK:
                    bipedmodel$armpose = HumanoidModel.ArmPose.BLOCK;
                    break;
                case BOW:
                    bipedmodel$armpose = HumanoidModel.ArmPose.BOW_AND_ARROW;
                    break;
                case SPEAR:
                    bipedmodel$armpose = HumanoidModel.ArmPose.THROW_SPEAR;
                    break;
                case CROSSBOW:
                    if (handIn == entityIn.getUsedItemHand()) {
                        bipedmodel$armpose = HumanoidModel.ArmPose.CROSSBOW_CHARGE;
                    }
                    break;
                default:
                    bipedmodel$armpose = HumanoidModel.ArmPose.EMPTY;
                    break;
                }
            } else {
                boolean flag1 = itemStackMain.getItem() instanceof CrossbowItem;
                boolean flag2 = itemStackOff.getItem() instanceof CrossbowItem;
                if (flag1 && entityIn.isAggressive()) {
                    bipedmodel$armpose = HumanoidModel.ArmPose.CROSSBOW_HOLD;
                }

                if (flag2 && itemStackMain.getItem().getUseAnimation(itemStackMain) == UseAnim.NONE
                        && entityIn.isAggressive()) {
                    bipedmodel$armpose = HumanoidModel.ArmPose.CROSSBOW_HOLD;
                }
            }
        }
        return bipedmodel$armpose;
    }

    @Override
    protected void scale(Person entitylivingbaseIn, PoseStack matrixStackIn, float partialTickTime) {
        matrixStackIn.scale(0.9375F, 0.9375F, 0.9375F);
    }

    @Nullable
    @Override
    public ResourceLocation getTextureLocation(Person entity) {
        // Pick a skin from the villager's OWN-gender pool. The variant is a wide
        // gender-agnostic index (rolled before gender is known); map it into the pool
        // with index % poolSize so a man draws a man's skin, a woman a woman's.
        Gender gender = (entity instanceof RealPerson realPerson) ? realPerson.getGender() : Gender.NONBINARY;
        // A pool can be empty before its skins are added; fall back to any populated
        // pool so a villager never renders the missing-texture checkerboard.
        if (Person.skinCountFor(gender) == 0) {
            for (Gender alt : Gender.values()) {
                if (Person.skinCountFor(alt) > 0) { gender = alt; break; }
            }
        }
        int count = Math.max(1, Person.skinCountFor(gender));
        int index = Math.floorMod(entity.getSkinVariant(), count);
        return ResourceLocation.fromNamespaceAndPath(Villagelife.MODID,
                "textures/entity/person/person_" + gender.name().toLowerCase() + "_" + index + ".png");
    }
}
