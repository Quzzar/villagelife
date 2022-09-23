package com.quzzar.villagelife.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;

import java.util.Map;
import java.util.UUID;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.TextComponent;

public abstract class ChoicePersonScreen extends Screen {

    private static final ResourceLocation RELATIONSHIP_HEART = new ResourceLocation(Villagelife.MODID, "textures/container/relationship_heart.png");
    private static final ResourceLocation RELATIONSHIP_HEART_EMPTY = new ResourceLocation(Villagelife.MODID, "textures/container/relationship_heart_empty.png");
    private static final ResourceLocation RELATIONSHIP_HEART_GOLDEN = new ResourceLocation(Villagelife.MODID, "textures/container/relationship_heart_golden.png");

    protected static final ResourceLocation DIALOGUE_CHOICE = new ResourceLocation(Villagelife.MODID, "textures/container/dialogue_choice.png");
    
    private final RealPerson person;
    private ResourceLocation GUI_TEXTURE;

    private float mousePosX;
    private float mousePosY;

    protected int imageWidth = 176;
    protected int imageHeight = 166;
    protected int leftPos;
    protected int topPos;

    private Map<UUID, Integer> relationships;

    public ChoicePersonScreen(RealPerson person, ResourceLocation GUI_TEXTURE) {
        super(new TextComponent(person.getFullName()));
        this.person = person;
        this.GUI_TEXTURE = GUI_TEXTURE;
        this.passEvents = false;

        relationships = person.getRelationships();

    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - this.imageWidth) / 2;
        this.topPos = (this.height - this.imageHeight) / 2;

        person.refreshDisplayName();

    }

    protected void renderBg(PoseStack matrixStack, float partialTicks) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, GUI_TEXTURE);
        this.blit(matrixStack, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

        InventoryScreen.renderEntityInInventory(
                this.leftPos + 51 - 10,
                this.topPos + 75 - 2,
                33,
                (float) (this.leftPos + 51) - this.mousePosX,
                (float) (this.topPos + 75) - this.mousePosY,
                this.person);

        // Add relationship hearts
        int filledHearts = relationships.containsKey(this.minecraft.player.getUUID()) ? relationships.get(this.minecraft.player.getUUID()) : 4;
        for(int i = 0; i < 10; i++){

            if(filledHearts == 10){
                RenderSystem.setShaderTexture(0, RELATIONSHIP_HEART_GOLDEN);
                this.blit(matrixStack, this.leftPos, this.topPos, 172-(i*8), 240, this.imageWidth, this.imageHeight);
                continue;
            }

            if(filledHearts > i) {
                RenderSystem.setShaderTexture(0, RELATIONSHIP_HEART);
                this.blit(matrixStack, this.leftPos, this.topPos, 172-(i*8), 240, this.imageWidth, this.imageHeight);
            } else {
                RenderSystem.setShaderTexture(0, RELATIONSHIP_HEART_EMPTY);
                this.blit(matrixStack, this.leftPos, this.topPos, 172-(i*8), 240, this.imageWidth, this.imageHeight);
            }

        }

    }

    protected void renderLabels(PoseStack matrixStack, int x, int y) {

        /*
        int health = Mth.ceil(person.getHealth());
        int armor = person.getArmorValue();
        Component guardHealthText = new TranslatableComponent("guardinventory.health", health);
        Component guardArmorText = new TranslatableComponent("guardinventory.armor", armor);
        this.font.draw(matrixStack, guardHealthText, 80.0F, 20.0F, 4210752);
        this.font.draw(matrixStack, guardArmorText, 80.0F, 30.0F, 4210752);
        */

        //
        
        this.font.draw(matrixStack, person.getFullName(), 84.0F, 6.0F, 4210752);
        this.font.draw(matrixStack, person.getGender().getSymbol(), 167.0F, 5.0F, 6579300);

        this.font.draw(matrixStack, "• "+Utils.capitalize(person.getOccupation().name()), 80.0F, 30.0F, 6579300);
        this.font.draw(matrixStack, "• "+Utils.capitalize(person.getMarriageStatus().name()), 80.0F, 40.0F, 6579300);

    }

    @Override
    public void render(PoseStack matrixStack, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(matrixStack);
        this.mousePosX = (float) mouseX;
        this.mousePosY = (float) mouseY;

        this.renderBg(matrixStack, partialTicks);
        

        RenderSystem.disableDepthTest();
        super.render(matrixStack, mouseX, mouseY, partialTicks);
        PoseStack posestack = RenderSystem.getModelViewStack();
        posestack.pushPose();
        posestack.translate((double)this.leftPos, (double)this.topPos, 0.0D);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        this.renderLabels(matrixStack, mouseX, mouseY);

        posestack.popPose();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableDepthTest();
    }

    public boolean isPauseScreen() {
        return false;
    }

    public final void tick() {
        super.tick();
        if (this.minecraft.player.isAlive() && !this.minecraft.player.isRemoved()) {
            this.containerTick();
        } else {
            this.minecraft.player.closeContainer();
        }

    }

    protected void containerTick() {
    }

    public int getGuiLeft() { return leftPos; }
    public int getGuiTop() { return topPos; }
    public int getXSize() { return imageWidth; }
    public int getYSize() { return imageHeight; }

    public void onClose() {
        this.minecraft.player.closeContainer();
        super.onClose();
    }
}