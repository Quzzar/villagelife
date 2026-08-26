package com.quzzar.villagelife.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.quzzar.villagelife.networking.PersonChatMessagePacket;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Free-form conversation with a villager: a header (name + detail line) and a
 * text box — no dialogue options, by design (Villager Conversations map).
 */
public class PersonChatScreen extends Screen {

  private record ChatLine(String speaker, String text, boolean player) {
  }

  private final int entityId;
  private final String headerName;
  private final String headerDetail;
  private final List<ChatLine> lines = new ArrayList<>();
  private EditBox input;
  private boolean awaitingReply = false;

  private PersonChatScreen(int entityId, String headerName, String headerDetail,
      List<com.quzzar.villagelife.networking.OpenPersonChatPacket.ExchangeLine> scrollback) {
    super(Component.literal(headerName));
    this.entityId = entityId;
    this.headerName = headerName;
    this.headerDetail = headerDetail;
    for (var exchange : scrollback) {
      lines.add(new ChatLine("You", exchange.playerLine(), true));
      lines.add(new ChatLine(headerName, exchange.reply(), false));
    }
  }

  public static void open(int entityId, String headerName, String headerDetail,
      List<com.quzzar.villagelife.networking.OpenPersonChatPacket.ExchangeLine> scrollback) {
    Minecraft.getInstance().setScreen(new PersonChatScreen(entityId, headerName, headerDetail, scrollback));
  }

  public static void onReply(int entityId, String text) {
    if (Minecraft.getInstance().screen instanceof PersonChatScreen screen && screen.entityId == entityId) {
      screen.lines.add(new ChatLine(screen.headerName, text, false));
      screen.awaitingReply = false;
    }
  }

  private int panelLeft;
  private int panelRight;

  @Override
  protected void init() {
    int panelWidth = Math.min(360, this.width - 24);
    this.panelLeft = (this.width - panelWidth) / 2;
    this.panelRight = panelLeft + panelWidth;

    int inputWidth = panelWidth - 24 - 56;
    this.input = new EditBox(this.font, panelLeft + 12, this.height - 34, inputWidth, 20,
        Component.translatable("villagelife.chat.say"));
    this.input.setMaxLength(PersonChatMessagePacket.MAX_TEXT_LENGTH);
    addRenderableWidget(this.input);

    addRenderableWidget(net.minecraft.client.gui.components.Button
        .builder(Component.translatable("villagelife.chat.send"), button -> send())
        .bounds(panelLeft + 12 + inputWidth + 4, this.height - 34, 48, 20)
        .build());

    addRenderableWidget(net.minecraft.client.gui.components.Button
        .builder(Component.literal("✕"), button -> onClose())
        .bounds(panelRight - 26, 12, 16, 16)
        .build());

    setInitialFocus(this.input);
  }

  @Override
  public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.renderBackground(graphics, mouseX, mouseY, partialTick);
    // One quiet panel behind everything: header, conversation, input.
    graphics.fill(panelLeft, 8, panelRight, this.height - 8, 0xB0101014);
    graphics.fill(panelLeft, 42, panelRight, 43, 0x50FFFFFF); // header separator
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if ((keyCode == 257 || keyCode == 335) && this.input.isFocused()) { // Enter
      send();
      return true;
    }
    return super.keyPressed(keyCode, scanCode, modifiers);
  }

  private void send() {
    String text = this.input.getValue().strip();
    if (text.isEmpty() || awaitingReply) {
      return;
    }
    lines.add(new ChatLine("You", text, true));
    awaitingReply = true;
    this.input.setValue("");
    PacketDistributor.sendToServer(new PersonChatMessagePacket(entityId, text));
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);

    graphics.drawCenteredString(this.font, Component.literal(headerName).withStyle(ChatFormatting.BOLD),
        this.width / 2, 18, 0xFFFFFF);
    graphics.drawCenteredString(this.font, Component.literal(headerDetail).withStyle(ChatFormatting.GRAY),
        this.width / 2, 30, 0xAAAAAA);

    // Conversation, newest at the bottom, wrapped, showing what fits.
    int textWidth = panelRight - panelLeft - 24;
    int left = panelLeft + 12;
    int bottom = this.height - 44;
    int top = 50;

    List<FormattedCharSequence> rendered = new ArrayList<>();
    List<Boolean> renderedIsPlayer = new ArrayList<>();
    for (ChatLine line : lines) {
      for (FormattedCharSequence part : this.font.split(
          Component.literal(line.speaker() + ": " + line.text()), textWidth)) {
        rendered.add(part);
        renderedIsPlayer.add(line.player());
      }
    }
    if (awaitingReply) {
      rendered.add(Component.literal(headerName + " …").withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
      renderedIsPlayer.add(false);
    }

    int maxLines = (bottom - top) / (this.font.lineHeight + 2);
    int start = Math.max(0, rendered.size() - maxLines);
    int y = bottom - (rendered.size() - start) * (this.font.lineHeight + 2);
    for (int i = start; i < rendered.size(); i++) {
      graphics.drawString(this.font, rendered.get(i), left, y, renderedIsPlayer.get(i) ? 0xFFDD99 : 0xFFFFFF);
      y += this.font.lineHeight + 2;
    }
  }

  @Override
  public void removed() {
    // Tell the server the conversation ended so the villager goes back to life.
    PacketDistributor.sendToServer(new com.quzzar.villagelife.networking.PersonChatClosePacket(entityId));
    super.removed();
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

}
