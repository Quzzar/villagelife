package com.quzzar.villagelife.client.gui;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.entities.PersonContainer;
import com.quzzar.villagelife.networking.GuardFollowPacket;
import com.quzzar.villagelife.networking.GuardSetPatrolPosPacket;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

public class GuardInventoryScreen extends AbstractContainerScreen<PersonContainer> {
    private static final ResourceLocation GUARD_GUI_TEXTURES = ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "textures/container/inventory.png");
    private static final ResourceLocation GUARD_FOLLOWING_ICON = ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "textures/container/following_icons.png");
    private static final ResourceLocation GUARD_NOT_FOLLOWING_ICON = ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "textures/container/not_following_icons.png");
    private static final ResourceLocation PATROL_ICON = ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "textures/container/patrollingui.png");
    private static final ResourceLocation NOT_PATROLLING_ICON = ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "textures/container/notpatrollingui.png");
    private final Person person;
    private Player player;
    private float mousePosX;
    private float mousePosY;
    private boolean buttonPressed;

    public GuardInventoryScreen(PersonContainer container, Inventory playerInventory, Component title) {
        super(container, playerInventory, title);
        this.person = container.getPerson();
        this.titleLabelX = 80;
        this.inventoryLabelX = 100;
        this.player = playerInventory.player;
    }

    @Override
    public void init() {
        super.init();
        if (person == null) {
            return;
        }
        if (player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE)) {
            this.addRenderableWidget(new GuardGuiButton(this.leftPos + 100, this.height / 2 - 40, 20, 18, 19, GUARD_FOLLOWING_ICON, (button) -> {
                PacketDistributor.sendToServer(new GuardFollowPacket(person.getId()));
            }));
        }
        this.addRenderableWidget(new GuardGuiButton(this.leftPos + 120, this.height / 2 - 40, 20, 18, 19, PATROL_ICON, (button) -> {
            buttonPressed = !buttonPressed;
            PacketDistributor.sendToServer(new GuardSetPatrolPosPacket(person.getId(), buttonPressed));
        }));
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTicks, int x, int y) {
        int i = (this.width - this.imageWidth) / 2;
        int j = (this.height - this.imageHeight) / 2;
        guiGraphics.blit(GUARD_GUI_TEXTURES, i, j, 0, 0, this.imageWidth, this.imageHeight);
        if (this.person != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(guiGraphics,
                    i + 26, j + 18, i + 75, j + 95, 30, 0.0F,
                    this.mousePosX, this.mousePosY, this.person);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int x, int y) {
        super.renderLabels(guiGraphics, x, y);
        if (this.person == null) {
            return;
        }
        int health = Mth.ceil(person.getHealth());
        int armor = person.getArmorValue();
        Component guardHealthText = Component.translatable("guardinventory.health", health);
        Component guardArmorText = Component.translatable("guardinventory.armor", armor);
        guiGraphics.drawString(this.font, guardHealthText, 80, 20, 4210752, false);
        guiGraphics.drawString(this.font, guardArmorText, 80, 30, 4210752, false);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.mousePosX = (float) mouseX;
        this.mousePosY = (float) mouseY;
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    // Simple textured toggle button rendered from the legacy container textures.
    class GuardGuiButton extends Button {
        private final ResourceLocation texture;
        private final int hoverOffset;

        public GuardGuiButton(int x, int y, int width, int height, int hoverOffset, ResourceLocation texture, OnPress onPress) {
            super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
            this.texture = texture;
            this.hoverOffset = hoverOffset;
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            guiGraphics.blit(this.texture, this.getX(), this.getY(), 0,
                    this.isHoveredOrFocused() ? this.hoverOffset : 0, this.width, this.height);
        }
    }

}
