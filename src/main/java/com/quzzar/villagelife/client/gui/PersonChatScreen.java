package com.quzzar.villagelife.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.quzzar.villagelife.networking.PersonChatMessagePacket;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Free-form conversation with a villager: a bordered panel headed by
 * "Name, Title", the exchange itself, and one styled input row with a send
 * icon. No dialogue options, by design (Villager Conversations map). The
 * villager speaks first when there is no history, so the panel is never empty.
 */
public class PersonChatScreen extends Screen {

  // Panel and text colours, kept together so the surface reads as one piece.
  private static final int PANEL_FILL = 0xE6101017;
  private static final int PANEL_BORDER = 0xFF4B4B5A;
  private static final int PANEL_INNER_EDGE = 0x22FFFFFF;
  private static final int DIVIDER = 0x33FFFFFF;
  private static final int INPUT_FILL = 0x66000000;
  private static final int INPUT_BORDER = 0xFF5A5A6B;
  private static final int INPUT_BORDER_FOCUSED = 0xFF8D8DA6;
  private static final int TEXT_VILLAGER = 0xFFF0F0F0;
  private static final int TEXT_PLAYER = 0xFFE8B87A;
  private static final int TEXT_PENDING = 0xFF80808C;
  private static final int ICON_IDLE = 0xFFB8B8C4;
  private static final int ICON_HOVER = 0xFFFFFFFF;

  private static final int LINE_GAP = 2;

  private record ChatLine(String speaker, String text, boolean player) {
  }

  private final int entityId;
  private final String headerName;
  private final String headerDetail;
  private final List<ChatLine> lines = new ArrayList<>();
  /** Which face of the villager is showing. Occupations contribute tabs. */
  private enum Tab { CHAT, TRADE }

  private final boolean canTrade;
  private Tab tab;
  private com.quzzar.villagelife.networking.MarketOffersPacket offers;
  private String marketNote = "";

  private EditBox input;
  private boolean awaitingReply;
  /** Never let a lost or unanswered reply lock the input for good. */
  private long awaitingSinceMs;
  private static final long AWAIT_RELEASE_MS = 30_000;

  private int panelLeft;
  private int panelRight;
  private int panelTop;
  private int panelBottom;
  private int inputRowTop;

  private PersonChatScreen(int entityId, String headerName, String headerDetail, boolean canTrade,
      List<com.quzzar.villagelife.networking.OpenPersonChatPacket.ExchangeLine> scrollback) {
    super(Component.literal(headerName));
    this.entityId = entityId;
    this.canTrade = canTrade;
    // Open on what this person is FOR, if their job offers anything; talking
    // is the fallback, and the only option for everyone else.
    this.tab = canTrade ? Tab.TRADE : Tab.CHAT;
    this.headerName = headerName;
    this.headerDetail = headerDetail;
    for (var exchange : scrollback) {
      // A blank player line is the villager's own opener, not an exchange.
      if (!exchange.playerLine().isBlank()) {
        lines.add(new ChatLine("You", exchange.playerLine(), true));
      }
      lines.add(new ChatLine(headerName, exchange.reply(), false));
    }
    // With nothing to show, the server is generating the villager's greeting.
    this.awaitingReply = lines.isEmpty();
    this.awaitingSinceMs = System.currentTimeMillis();
  }

  public static void open(int entityId, String headerName, String headerDetail, boolean canTrade,
      List<com.quzzar.villagelife.networking.OpenPersonChatPacket.ExchangeLine> scrollback) {
    Minecraft.getInstance().setScreen(
        new PersonChatScreen(entityId, headerName, headerDetail, canTrade, scrollback));
  }

  /** The stall's current state arrived; repaint the trade tab. */
  public static void onMarketOffers(com.quzzar.villagelife.networking.MarketOffersPacket packet) {
    if (Minecraft.getInstance().screen instanceof PersonChatScreen screen
        && screen.entityId == packet.entityId()) {
      screen.offers = packet;
      screen.marketNote = packet.message();
    }
  }

  public static void onReply(int entityId, String text) {
    if (Minecraft.getInstance().screen instanceof PersonChatScreen screen && screen.entityId == entityId) {
      screen.lines.add(new ChatLine(screen.headerName, text, false));
      screen.awaitingReply = false;
    }
  }

  @Override
  protected void init() {
    int panelWidth = Math.min(380, this.width - 24);
    this.panelLeft = (this.width - panelWidth) / 2;
    this.panelRight = panelLeft + panelWidth;
    this.panelTop = Math.max(8, this.height / 2 - 110);
    this.panelBottom = Math.min(this.height - 8, this.height / 2 + 110);
    this.inputRowTop = panelBottom - 30;

    if (canTrade) {
      int tabWidth = 58;
      addRenderableWidget(new TabButton(panelLeft + 12, panelTop + 30, tabWidth, "Chat", Tab.CHAT));
      addRenderableWidget(new TabButton(panelLeft + 14 + tabWidth, panelTop + 30, tabWidth, "Trade", Tab.TRADE));
      PacketDistributor.sendToServer(
          com.quzzar.villagelife.networking.MarketActionPacket.refresh(entityId));
    }

    int iconSize = 20;
    int inputWidth = panelWidth - 24 - iconSize - 6;
    this.input = new EditBox(this.font, panelLeft + 12 + 5, inputRowTop + 6, inputWidth - 10, 12,
        Component.translatable("villagelife.chat.say"));
    this.input.setMaxLength(PersonChatMessagePacket.MAX_TEXT_LENGTH);
    this.input.setBordered(false);
    this.input.setHint(Component.translatable("villagelife.chat.say").withStyle(ChatFormatting.DARK_GRAY));
    addRenderableWidget(this.input);

    addRenderableWidget(new IconButton(panelLeft + 12 + inputWidth + 6, inputRowTop + 2, iconSize,
        IconButton.SEND, button -> send()));
    this.input.visible = tab == Tab.CHAT;
    addRenderableWidget(new IconButton(panelRight - 24, panelTop + 6, 16,
        IconButton.CLOSE, button -> onClose()));

    setInitialFocus(this.input);
  }

  @Override
  public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.renderBackground(graphics, mouseX, mouseY, partialTick);

    // Bordered panel: outer border, fill, and a faint inner edge for depth.
    graphics.fill(panelLeft - 1, panelTop - 1, panelRight + 1, panelBottom + 1, PANEL_BORDER);
    graphics.fill(panelLeft, panelTop, panelRight, panelBottom, PANEL_FILL);
    graphics.fill(panelLeft, panelTop, panelRight, panelTop + 1, PANEL_INNER_EDGE);

    // Header divider.
    graphics.fill(panelLeft + 8, panelTop + 26, panelRight - 8, panelTop + 27, DIVIDER);

    if (tab != Tab.CHAT) {
      return;
    }
    // Input field, framed and brightened while focused.
    boolean focused = this.input != null && this.input.isFocused();
    int fieldRight = panelRight - 12 - 26;
    graphics.fill(panelLeft + 12, inputRowTop + 2, fieldRight, inputRowTop + 22,
        focused ? INPUT_BORDER_FOCUSED : INPUT_BORDER);
    graphics.fill(panelLeft + 13, inputRowTop + 3, fieldRight - 1, inputRowTop + 21, INPUT_FILL);
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
    awaitingSinceMs = System.currentTimeMillis();
    this.input.setValue("");
    PacketDistributor.sendToServer(new PersonChatMessagePacket(entityId, text));
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);

    if (awaitingReply && System.currentTimeMillis() - awaitingSinceMs > AWAIT_RELEASE_MS) {
      awaitingReply = false; // let the player speak again rather than wait forever
    }

    // "Name, Title" on one line: the name carries, the title trails in grey.
    Component header = Component.literal(headerName).withStyle(ChatFormatting.WHITE)
        .append(Component.literal(", " + headerDetail).withStyle(ChatFormatting.GRAY));
    graphics.drawCenteredString(this.font, header, (panelLeft + panelRight) / 2, panelTop + 13, 0xFFFFFF);

    if (tab == Tab.TRADE) {
      renderTrade(graphics, mouseX, mouseY);
      return;
    }

    int textWidth = panelRight - panelLeft - 24;
    int left = panelLeft + 12;
    int top = panelTop + (canTrade ? 52 : 34);
    int bottom = inputRowTop - 6;

    List<FormattedCharSequence> rendered = new ArrayList<>();
    List<Integer> colours = new ArrayList<>();
    for (ChatLine line : lines) {
      int colour = line.player() ? TEXT_PLAYER : TEXT_VILLAGER;
      for (FormattedCharSequence part : this.font.split(
          Component.literal(line.speaker() + ": " + line.text()), textWidth)) {
        rendered.add(part);
        colours.add(colour);
      }
    }
    if (awaitingReply) {
      rendered.add(Component.literal(headerName + " …").getVisualOrderText());
      colours.add(TEXT_PENDING);
    }

    int maxLines = Math.max(1, (bottom - top) / (this.font.lineHeight + LINE_GAP));
    int start = Math.max(0, rendered.size() - maxLines);
    int y = bottom - (rendered.size() - start) * (this.font.lineHeight + LINE_GAP);
    for (int i = start; i < rendered.size(); i++) {
      graphics.drawString(this.font, rendered.get(i), left, y, colours.get(i));
      y += this.font.lineHeight + LINE_GAP;
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

  /** Rows the player can click, recomputed each frame so hit-testing matches what is drawn. */
  private final List<Row> rowHits = new ArrayList<>();

  private record Row(int y, int left, int right, String itemId, boolean playerBuys) {
  }

  /**
   * The stall: what they sell on the left, what they want on the right. Their
   * wants are the point - a village's shortages are a job board - so the two
   * columns are given equal weight rather than burying the wanted list.
   */
  private void renderTrade(GuiGraphics graphics, int mouseX, int mouseY) {
    rowHits.clear();
    int left = panelLeft + 12;
    int right = panelRight - 12;
    int mid = (left + right) / 2;
    int y = panelTop + 56;

    if (offers == null) {
      graphics.drawString(this.font, Component.literal("Opening the stall...")
          .withStyle(ChatFormatting.DARK_GRAY), left, y, TEXT_PENDING);
      return;
    }

    graphics.drawString(this.font, Component.literal(offers.villageName())
        .withStyle(ChatFormatting.WHITE), left, y, TEXT_VILLAGER);
    String purse = offers.treasury() + (offers.treasury() == 1 ? " emerald" : " emeralds");
    graphics.drawString(this.font, Component.literal(purse).withStyle(ChatFormatting.GRAY),
        right - this.font.width(purse), y, 0xFFAAAAAA);
    y += 14;

    graphics.drawString(this.font, Component.literal("For sale").withStyle(ChatFormatting.GRAY),
        left, y, 0xFF9FD0FF);
    graphics.drawString(this.font, Component.literal("Wanted").withStyle(ChatFormatting.GRAY),
        mid + 6, y, TEXT_PLAYER);
    y += 12;

    int rowsTop = y;
    drawColumn(graphics, offers.selling(), left, mid - 6, rowsTop, mouseX, mouseY, true);
    drawColumn(graphics, offers.wanted(), mid + 6, right, rowsTop, mouseX, mouseY, false);

    int bottom = inputRowTop + 8;
    if (!marketNote.isBlank()) {
      for (FormattedCharSequence line : this.font.split(
          Component.literal(marketNote), panelRight - panelLeft - 24)) {
        graphics.drawString(this.font, line, left, bottom, TEXT_VILLAGER);
        bottom += this.font.lineHeight + 1;
      }
    } else {
      graphics.drawString(this.font, Component.literal("Click to trade one, shift-click for eight.")
          .withStyle(ChatFormatting.DARK_GRAY), left, bottom, TEXT_PENDING);
    }
  }

  private void drawColumn(GuiGraphics graphics, List<com.quzzar.villagelife.networking.MarketOffersPacket.Row> rows,
      int left, int right, int top, int mouseX, int mouseY, boolean playerBuys) {
    int y = top;
    if (rows.isEmpty()) {
      graphics.drawString(this.font, Component.literal(playerBuys ? "nothing spare" : "nothing needed")
          .withStyle(ChatFormatting.DARK_GRAY), left, y, TEXT_PENDING);
      return;
    }
    for (var row : rows) {
      boolean hovered = mouseX >= left && mouseX <= right && mouseY >= y - 1 && mouseY <= y + 9;
      if (hovered) {
        graphics.fill(left - 2, y - 2, right, y + 10, 0x22FFFFFF);
      }
      String name = shortName(row.itemId());
      String price = String.format("%.2f", row.unitPrice());
      graphics.drawString(this.font, name, left, y, playerBuys ? 0xFF9FD0FF : TEXT_PLAYER);
      graphics.drawString(this.font, price, right - this.font.width(price), y, 0xFFAAAAAA);
      rowHits.add(new Row(y, left, right, row.itemId(), playerBuys));
      y += this.font.lineHeight + 3;
    }
  }

  /** "minecraft:oak_log" reads as "oak log" on a market stall. */
  private static String shortName(String itemId) {
    String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
    return path.replace('_', ' ');
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (tab == Tab.TRADE && offers != null) {
      for (Row row : rowHits) {
        if (mouseY >= row.y() - 1 && mouseY <= row.y() + 9
            && mouseX >= row.left() - 2 && mouseX <= row.right()) {
          int count = hasShiftDown() ? 8 : 1;
          PacketDistributor.sendToServer(new com.quzzar.villagelife.networking.MarketActionPacket(
              entityId, row.playerBuys(), row.itemId(), count));
          return true;
        }
      }
    }
    return super.mouseClicked(mouseX, mouseY, button);
  }

  /** A quiet tab: underlined when active, no vanilla button chrome. */
  private class TabButton extends Button {

    private final Tab target;
    private final String label;

    TabButton(int x, int y, int width, String label, Tab target) {
      super(x, y, width, 14, Component.literal(label), b -> {
      }, DEFAULT_NARRATION);
      this.target = target;
      this.label = label;
    }

    @Override
    public void onPress() {
      tab = target;
      if (input != null) {
        input.visible = tab == Tab.CHAT;
      }
      if (target == Tab.TRADE) {
        PacketDistributor.sendToServer(
            com.quzzar.villagelife.networking.MarketActionPacket.refresh(entityId));
      }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      boolean active = tab == target;
      int colour = active ? ICON_HOVER : (isHovered() ? ICON_HOVER : ICON_IDLE);
      graphics.drawCenteredString(PersonChatScreen.this.font, label,
          getX() + width / 2, getY() + 3, colour);
      if (active) {
        graphics.fill(getX(), getY() + 13, getX() + width, getY() + 14, 0xFF8FBB84);
      }
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
      defaultButtonNarrationText(output);
    }
  }

  /**
   * A borderless glyph button: no vanilla button chrome, just the icon and a
   * soft highlight on hover, so the panel stays quiet.
   */
  private class IconButton extends Button {

    private static final int CLOSE = 0;
    private static final int SEND = 1;

    private final int kind;

    IconButton(int x, int y, int size, int kind, OnPress onPress) {
      super(x, y, size, size, Component.empty(), onPress, DEFAULT_NARRATION);
      this.kind = kind;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      boolean hovered = isHovered();
      int colour = hovered ? ICON_HOVER : ICON_IDLE;
      if (hovered) {
        graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x28FFFFFF);
      }
      if (kind == CLOSE) {
        graphics.drawCenteredString(PersonChatScreen.this.font, "✕",
            getX() + width / 2, getY() + (height - 8) / 2, colour);
      } else {
        // A send arrow drawn as a triangle: no reliance on font coverage.
        int cx = getX() + width / 2 - 3;
        int cy = getY() + height / 2 - 5;
        for (int row = 0; row < 5; row++) {
          graphics.fill(cx, cy + row, cx + 6 - row, cy + row + 1, colour);
          graphics.fill(cx, cy + 9 - row, cx + 6 - row, cy + 10 - row, colour);
        }
      }
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
      defaultButtonNarrationText(output);
    }
  }
}
