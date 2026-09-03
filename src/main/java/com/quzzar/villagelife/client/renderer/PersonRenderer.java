package com.quzzar.villagelife.client.renderer;

import java.util.List;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.client.models.PersonModel;
import com.quzzar.villagelife.entities.Gender;
import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.entities.PersonSkins;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.events.PersonClientEvents;

import net.minecraft.client.resources.DefaultPlayerSkin;

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

    /** The two body geometries, chosen per person by gender in {@link #render}. */
    private final PersonModel wideModel;
    private final PersonModel slimModel;

    public PersonRenderer(EntityRendererProvider.Context context) {
        super(context, new PersonModel(context.bakeLayer(PersonClientEvents.PERSON), false), 0.5F);
        this.wideModel = new PersonModel(context.bakeLayer(PersonClientEvents.PERSON), false);
        this.slimModel = new PersonModel(context.bakeLayer(PersonClientEvents.PERSON_SLIM), true);
        this.model = this.wideModel;

        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
    }

    @Override
    public void render(Person entityIn, float entityYaw, float partialTicks, PoseStack matrixStackIn, MultiBufferSource bufferIn, int packedLightIn) {
        this.model = bodyModelFor(entityIn);
        this.setModelVisibilities(entityIn);
        super.render(entityIn, entityYaw, partialTicks, matrixStackIn, bufferIn, packedLightIn);
    }

    /**
     * The body geometry this person renders on, keyed off the same gender the skin pool
     * uses so body and skin agree: women get the slim (Alex) model, men the wide (Steve)
     * model. The nonbinary, and plain Persons that carry no gender, get a stable seeded
     * pick off the skin variant so a given villager always renders the same body.
     */
    private PersonModel bodyModelFor(Person entity) {
        Gender gender = (entity instanceof RealPerson realPerson) ? realPerson.getGender() : Gender.NONBINARY;
        boolean slim = switch (gender) {
            case MALE -> false;
            case FEMALE -> true;
            case NONBINARY -> (entity.getSkinVariant() & 1) == 1;
        };
        return slim ? this.slimModel : this.wideModel;
    }

    /**
     * A villager's name tag normally shows only when the player looks straight at
     * them, and the speech bubble rides on the name tag. Force it on whenever a
     * bubble is live, so overheard conversation is visible without aiming at the
     * speaker; the rest of the time it defers to the usual look-at behaviour.
     */
    @Override
    protected boolean shouldShowName(Person entity) {
        // A wandering merchant that has drunk itself invisible at night gives
        // nothing away: no name, no role line, no bubble while it is hidden.
        if (entity instanceof RealPerson merchant && merchant.isWanderingMerchant()
                && entity.isInvisible()) {
            return false;
        }
        if (VillagerSpeechBubbles.visibleText(entity.getId()) != null) {
            return true;
        }
        return super.shouldShowName(entity);
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
        String speech = VillagerSpeechBubbles.visibleText(entity.getId());
        if (speech != null) {
            renderSpeechBubble(entity, speech, poseStack, bufferSource, packedLight);
        }
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

    /** World units per bubble text pixel: a touch smaller than the name tag's 0.025. */
    private static final float BUBBLE_SCALE = 0.021F;

    /** Bubble body: warm near-white, so speech reads inverted from the dark name tag. */
    private static final int BUBBLE_BODY_R = 252, BUBBLE_BODY_G = 250, BUBBLE_BODY_B = 244, BUBBLE_BODY_A = 244;

    /** One-pixel outline in the text's dark slate, so the bubble pops off the sky. */
    private static final int BUBBLE_BORDER_R = 47, BUBBLE_BORDER_G = 47, BUBBLE_BORDER_B = 56, BUBBLE_BORDER_A = 255;

    /** Bubble text: dark slate on the light body. */
    private static final int BUBBLE_TEXT = 0xFF2F2F38;

    /** Faint pass drawn through walls, matching how name tags stay legible. */
    private static final int BUBBLE_TEXT_SEE_THROUGH = 0x502F2F38;

    /** The through-wall ghost of the body, kept faint so it reads as a hint. */
    private static final int BUBBLE_BODY_SEE_THROUGH_A = 70;

    private static final int BUBBLE_PAD_X = 4;
    private static final int BUBBLE_PAD_Y = 3;
    private static final int BUBBLE_TAIL_HEIGHT = 4;

    /** World-space gap between the name tag and the tail tip. */
    private static final float BUBBLE_CLEARANCE = 0.10F;

    /**
     * A comic-style speech bubble above the name tag: a light rounded body, dark
     * text, and a small tail pointing at the speaker. Drawn as our own billboard
     * rather than stacked name tags, so the shape actually reads as a bubble.
     */
    private void renderSpeechBubble(Person entity, String speech, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight) {
        java.util.List<String> lines = wrapSpeech(speech);
        if (lines.isEmpty()) {
            return;
        }
        net.minecraft.client.gui.Font font = this.getFont();
        int lineHeight = font.lineHeight + 1;
        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, font.width(line));
        }
        int halfWidth = textWidth / 2 + BUBBLE_PAD_X;
        int bodyHeight = lines.size() * lineHeight + BUBBLE_PAD_Y * 2 - 1;

        poseStack.pushPose();
        // Anchor the bubble's top so its tail ends just above the floating name.
        float clearance = BUBBLE_CLEARANCE + (bodyHeight + BUBBLE_TAIL_HEIGHT + 2) * BUBBLE_SCALE;
        poseStack.translate(0.0D, entity.getBbHeight() + 0.5D + clearance, 0.0D);
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poseStack.scale(BUBBLE_SCALE, -BUBBLE_SCALE, BUBBLE_SCALE);
        org.joml.Matrix4f matrix = poseStack.last().pose();

        // The solid pass writes depth, so later translucents (clouds, rain) cannot
        // paint over the bubble; the see-through pass keeps a faint ghost visible
        // through walls, like a name tag. The buffer source is immediate-mode:
        // requesting a second render type ENDS the previous builder, so each
        // buffer must be fully written before the next is fetched (fetching both
        // up front crashed the render thread with "Not building!").
        //
        // The solid text-background shader multiplies by the world lightmap
        // (the see-through one does not); a bubble is UI, not a lit surface,
        // so its geometry passes fullbright.
        //
        // Depth in this billboard space: LARGER z is CLOSER to the camera
        // (verified live: border at the larger z occluded the whole body and
        // the bubble read gray-and-black). Border is the deepest layer, the
        // body sits proud of it, and the text gets its own closer plane.
        int fullBright = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
        com.mojang.blaze3d.vertex.VertexConsumer solid = bufferSource
                .getBuffer(net.minecraft.client.renderer.RenderType.textBackground());
        // Border: the body's outline, one pixel proud on every side, then the
        // body over it, then the tail pair.
        bubbleBody(solid, matrix, halfWidth + 1, -1, bodyHeight + 1, 0.0F,
                BUBBLE_BORDER_R, BUBBLE_BORDER_G, BUBBLE_BORDER_B, BUBBLE_BORDER_A, fullBright);
        bubbleTail(solid, matrix, 4.0F, bodyHeight, BUBBLE_TAIL_HEIGHT + 2, 0.0F,
                BUBBLE_BORDER_R, BUBBLE_BORDER_G, BUBBLE_BORDER_B, BUBBLE_BORDER_A, fullBright);
        bubbleBody(solid, matrix, halfWidth, 0, bodyHeight, 0.015F,
                BUBBLE_BODY_R, BUBBLE_BODY_G, BUBBLE_BODY_B, BUBBLE_BODY_A, fullBright);
        bubbleTail(solid, matrix, 3.0F, bodyHeight, BUBBLE_TAIL_HEIGHT, 0.015F,
                BUBBLE_BODY_R, BUBBLE_BODY_G, BUBBLE_BODY_B, BUBBLE_BODY_A, fullBright);
        // Solid writes done; now the faint through-wall ghost of the body alone.
        com.mojang.blaze3d.vertex.VertexConsumer ghost = bufferSource
                .getBuffer(net.minecraft.client.renderer.RenderType.textBackgroundSeeThrough());
        bubbleBody(ghost, matrix, halfWidth, 0, bodyHeight, 0.015F,
                BUBBLE_BODY_R, BUBBLE_BODY_G, BUBBLE_BODY_B, BUBBLE_BODY_SEE_THROUGH_A, fullBright);
        // Text plane, nudged toward the camera so it sits on the body.
        poseStack.translate(0.0D, 0.0D, 0.03D);
        org.joml.Matrix4f textMatrix = poseStack.last().pose();

        float textY = BUBBLE_PAD_Y;
        for (String line : lines) {
            float textX = -font.width(line) / 2.0F;
            font.drawInBatch(line, textX, textY, BUBBLE_TEXT_SEE_THROUGH, false, textMatrix, bufferSource,
                    net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, 0, packedLight);
            font.drawInBatch(line, textX, textY, BUBBLE_TEXT, false, textMatrix, bufferSource,
                    net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, packedLight);
            textY += lineHeight;
        }
        poseStack.popPose();
    }

    /** The rounded rectangle: a wide middle quad plus inset side strips for cut corners. */
    private void bubbleBody(com.mojang.blaze3d.vertex.VertexConsumer buffer, org.joml.Matrix4f matrix,
            float halfWidth, float top, float bottom, float z, int r, int g, int b, int a, int packedLight) {
        bubbleQuad(buffer, matrix, -halfWidth + 1, top, halfWidth - 1, bottom, z, r, g, b, a, packedLight);
        bubbleQuad(buffer, matrix, -halfWidth, top + 1, -halfWidth + 1, bottom - 1, z, r, g, b, a, packedLight);
        bubbleQuad(buffer, matrix, halfWidth - 1, top + 1, halfWidth, bottom - 1, z, r, g, b, a, packedLight);
    }

    private void bubbleQuad(com.mojang.blaze3d.vertex.VertexConsumer buffer, org.joml.Matrix4f matrix,
            float x0, float y0, float x1, float y1, float z, int r, int g, int b, int a, int packedLight) {
        buffer.addVertex(matrix, x0, y0, z).setColor(r, g, b, a).setLight(packedLight);
        buffer.addVertex(matrix, x0, y1, z).setColor(r, g, b, a).setLight(packedLight);
        buffer.addVertex(matrix, x1, y1, z).setColor(r, g, b, a).setLight(packedLight);
        buffer.addVertex(matrix, x1, y0, z).setColor(r, g, b, a).setLight(packedLight);
    }

    /** The pointer under the body's centre: a quad collapsed into a triangle. */
    private void bubbleTail(com.mojang.blaze3d.vertex.VertexConsumer buffer, org.joml.Matrix4f matrix,
            float halfWidth, float from, float length, float z, int r, int g, int b, int a, int packedLight) {
        float tip = from + length;
        buffer.addVertex(matrix, -halfWidth, from, z).setColor(r, g, b, a).setLight(packedLight);
        buffer.addVertex(matrix, 0.0F, tip, z).setColor(r, g, b, a).setLight(packedLight);
        buffer.addVertex(matrix, 0.0F, tip, z).setColor(r, g, b, a).setLight(packedLight);
        buffer.addVertex(matrix, halfWidth, from, z).setColor(r, g, b, a).setLight(packedLight);
    }

    /** Wraps bubble copy without depending on formatted-text internals. */
    private java.util.List<String> wrapSpeech(String speech) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : speech.strip().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && this.getFont().width(candidate) > 150) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        if (lines.size() > 3) {
            lines = new java.util.ArrayList<>(lines.subList(0, 3));
            int last = lines.size() - 1;
            lines.set(last, this.getFont().plainSubstrByWidth(lines.get(last), 144) + "...");
        }
        return lines;
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
        // Draw from the villager's OWN-gender pool. The variant is a wide gender-agnostic
        // index (rolled before gender is known); map it in with index % poolSize so a man
        // draws a man's skin, a woman a woman's.
        Gender gender = (entity instanceof RealPerson realPerson) ? realPerson.getGender() : Gender.NONBINARY;
        // A wandering merchant draws from the curated trader pool, by gender, so it
        // wears the merchant's robe rather than an ordinary villager's skin.
        boolean merchant = entity instanceof RealPerson rp && rp.isWanderingMerchant();
        List<String> pool = merchant ? PersonSkins.merchantForGender(gender) : PersonSkins.forGender(gender);
        // A pool can be empty before its skins are added; fall back to any populated pool
        // so a villager never renders the missing-texture checkerboard.
        if (pool.isEmpty()) {
            for (Gender alt : Gender.values()) {
                List<String> altPool = PersonSkins.forGender(alt);
                if (!altPool.isEmpty()) { pool = altPool; break; }
            }
        }
        if (pool.isEmpty()) {
            return DefaultPlayerSkin.getDefaultTexture();
        }
        String hash = pool.get(Math.floorMod(entity.getSkinVariant(), pool.size()));
        return ResourceLocation.fromNamespaceAndPath(Villagelife.MODID,
                "textures/entity/person/" + hash + ".png");
    }
}
