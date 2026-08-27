package com.quzzar.villagelife.client.gui;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.entities.PersonContainer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

public class GuardInventoryScreen extends AbstractContainerScreen<PersonContainer> {
    private static final ResourceLocation GUARD_GUI_TEXTURES = ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, "textures/container/inventory.png");
    private final Person person;
    private float mousePosX;
    private float mousePosY;

    public GuardInventoryScreen(PersonContainer container, Inventory playerInventory, Component title) {
        super(container, playerInventory, title);
        this.person = container.getPerson();
        this.titleLabelX = 80;
        this.inventoryLabelX = 100;
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

}
