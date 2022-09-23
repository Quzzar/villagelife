package com.quzzar.villagelife.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.entities.PersonContainer;
import com.quzzar.villagelife.networking.GuardFollowPacket;
import com.quzzar.villagelife.networking.GuardSetPatrolPosPacket;
import com.quzzar.villagelife.networking.VillagelifePacketHandler;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;

public class GuardInventoryScreen extends AbstractContainerScreen<PersonContainer> {
    private static final ResourceLocation GUARD_GUI_TEXTURES = new ResourceLocation(Villagelife.MODID, "textures/container/inventory.png");
    private static final ResourceLocation GUARD_FOLLOWING_ICON = new ResourceLocation(Villagelife.MODID, "textures/container/following_icons.png");
    private static final ResourceLocation GUARD_NOT_FOLLOWING_ICON = new ResourceLocation(Villagelife.MODID, "textures/container/not_following_icons.png");
    private static final ResourceLocation PATROL_ICON = new ResourceLocation(Villagelife.MODID, "textures/container/patrollingui.png");
    private static final ResourceLocation NOT_PATROLLING_ICON = new ResourceLocation(Villagelife.MODID, "textures/container/notpatrollingui.png");
    private final Person person;
    private Player player;
    private float mousePosX;
    private float mousePosY;
    private boolean buttonPressed;

    public GuardInventoryScreen(PersonContainer container, Inventory playerInventory, Person person) {
        super(container, playerInventory, person.getDisplayName());
        this.person = person;
        this.titleLabelX = 80;
        this.inventoryLabelX = 100;
        this.passEvents = false;
        this.player = playerInventory.player;
    }

    @Override
    public void init() {
        super.init();
        if (player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE)) {
            this.addRenderableWidget(new GuardGuiButton(this.leftPos + 100, this.height / 2 - 40, 20, 18, 0, 0, 19, GUARD_FOLLOWING_ICON, GUARD_NOT_FOLLOWING_ICON, true, (p_214086_1_) -> {
                VillagelifePacketHandler.INSTANCE.sendToServer(new GuardFollowPacket(person.getId()));
            }));
        }
        this.addRenderableWidget(new GuardGuiButton(this.leftPos + 120, this.height / 2 - 40, 20, 18, 0, 0, 19, PATROL_ICON, NOT_PATROLLING_ICON, false, (p_214086_1_) -> {
            buttonPressed = !buttonPressed;
            VillagelifePacketHandler.INSTANCE.sendToServer(new GuardSetPatrolPosPacket(person.getId(), buttonPressed));
        }));
    }

    @Override
    protected void renderBg(PoseStack matrixStack, float partialTicks, int x, int y) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, GUARD_GUI_TEXTURES);
        int i = (this.width - this.imageWidth) / 2;
        int j = (this.height - this.imageHeight) / 2;
        this.blit(matrixStack, i, j, 0, 0, this.imageWidth, this.imageHeight);
        InventoryScreen.renderEntityInInventory(i + 51, j + 75, 30, (float) (i + 51) - this.mousePosX, (float) (j + 75 - 50) - this.mousePosY, this.person);
    }

    @Override
    protected void renderLabels(PoseStack matrixStack, int x, int y) {
        super.renderLabels(matrixStack, x, y);
        int health = Mth.ceil(person.getHealth());
        int armor = person.getArmorValue();
        Component guardHealthText = new TranslatableComponent("guardinventory.health", health);
        Component guardArmorText = new TranslatableComponent("guardinventory.armor", armor);
        this.font.draw(matrixStack, guardHealthText, 80.0F, 20.0F, 4210752);
        this.font.draw(matrixStack, guardArmorText, 80.0F, 30.0F, 4210752);
    }

    @Override
    public void render(PoseStack matrixStack, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(matrixStack);
        this.mousePosX = (float) mouseX;
        this.mousePosY = (float) mouseY;
        super.render(matrixStack, mouseX, mouseY, partialTicks);
        this.renderTooltip(matrixStack, mouseX, mouseY);
    }


    // To remove
    class GuardGuiButton extends ImageButton {

        public GuardGuiButton(int xIn, int yIn, int widthIn, int heightIn, int xTexStartIn, int yTexStartIn, int yDiffTextIn, ResourceLocation resourceLocationIn, ResourceLocation newTexture, boolean isFollowButton, OnPress onPressIn) {
            super(xIn, yIn, widthIn, heightIn, xTexStartIn, yTexStartIn, yDiffTextIn, resourceLocationIn, onPressIn);
        }
    }

}