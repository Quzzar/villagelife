package com.quzzar.villagelife.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.quzzar.villagelife.networking.PersonChatMessagePacket;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
  // Minecraft's own GUI palette: the grey panel, its bevel, and the inset
  // slot. Matching these is what makes a modded screen read as part of the
  // game rather than as something bolted on.
  private static final int PANEL_FACE = 0xFFC6C6C6;
  private static final int PANEL_LIGHT = 0xFFFFFFFF;
  private static final int PANEL_DARK = 0xFF555555;
  private static final int PANEL_EDGE = 0xFF000000;
  private static final int SLOT_FACE = 0xFF8B8B8B;
  private static final int SLOT_DARK = 0xFF373737;
  private static final int DIVIDER = 0xFF8B8B8B;
  private static final int TEXT_LABEL = 0xFF404040;
  private static final int TEXT_VILLAGER = 0xFF404040;
  private static final int TEXT_PLAYER = 0xFF29416B;
  private static final int TEXT_PENDING = 0xFF7A7A7A;
  private static final int ICON_IDLE = 0xFF404040;
  private static final int ICON_HOVER = 0xFF000000;
  private static final int ROW_HOVER = 0x40FFFFFF;

  /** Vanilla's villager screen is 276x166; this is that, with room for chat. */
  private static final int PANEL_WIDTH = 276;
  private static final int PANEL_HEIGHT = 196;
  /** slot, arrow, slot: the width the two slots of one trade occupy. */
  private static final int TRADE_WIDTH = 56;
  /** A trade plus the name of the goods, which is what fills a column. */
  private static final int COLUMN_WIDTH = 130;
  private static final int ROW_HEIGHT = 20;

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
  private Button sendButton;
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
    // A fixed, compact window, the way every vanilla screen is sized. Scaling
    // to the viewport stretched this into a near-fullscreen grey rectangle
    // with its content marooned at the edges.
    int panelWidth = Math.min(PANEL_WIDTH, this.width - 16);
    int panelHeight = Math.min(PANEL_HEIGHT, this.height - 16);
    this.panelLeft = (this.width - panelWidth) / 2;
    this.panelRight = panelLeft + panelWidth;
    this.panelTop = (this.height - panelHeight) / 2;
    this.panelBottom = panelTop + panelHeight;
    this.inputRowTop = panelBottom - 30;

    if (canTrade) {
      int tabWidth = 58;
      addRenderableWidget(new TabButton(panelLeft + 12, panelTop + 30, tabWidth, "Chat", Tab.CHAT));
      addRenderableWidget(new TabButton(panelLeft + 14 + tabWidth, panelTop + 30, tabWidth, "Trade", Tab.TRADE));
      PacketDistributor.sendToServer(
          com.quzzar.villagelife.networking.MarketActionPacket.refresh(entityId));
    }

    // The field sits inside the sunken box drawn in renderBackground, and its
    // text is centred on that box: an 8px line in a 20px box starts 6px down.
    int fieldLeft = panelLeft + 8;
    int fieldRight = panelRight - 34;
    this.input = new EditBox(this.font, fieldLeft + 4, inputRowTop + 4, fieldRight - fieldLeft - 8, 12,
        Component.translatable("villagelife.chat.say"));
    this.input.setMaxLength(PersonChatMessagePacket.MAX_TEXT_LENGTH);
    this.input.setBordered(false);
    this.input.setTextColor(TEXT_VILLAGER);
    this.input.setHint(Component.translatable("villagelife.chat.say").withStyle(ChatFormatting.DARK_GRAY));
    addRenderableWidget(this.input);

    this.sendButton = addRenderableWidget(new IconButton(panelRight - 30, inputRowTop, 20,
        IconButton.SEND, button -> send()));
    addRenderableWidget(new IconButton(panelRight - 22, panelTop + 6, 16,
        IconButton.CLOSE, button -> onClose()));
    applyTabVisibility();

    setInitialFocus(this.input);
  }

  @Override
  public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    // super gives the blurred, darkened world behind the panel.
    super.renderBackground(graphics, mouseX, mouseY, partialTick);
    panel(graphics, panelLeft, panelTop, panelRight, panelBottom);
    graphics.fill(panelLeft + 6, panelTop + 24, panelRight - 6, panelTop + 25, DIVIDER);

    if (tab != Tab.CHAT) {
      return;
    }
    slot(graphics, panelLeft + 8, inputRowTop, panelRight - 34, inputRowTop + 20);
  }

  /** The raised grey panel Minecraft draws every one of its own windows with. */
  private static void panel(GuiGraphics graphics, int left, int top, int right, int bottom) {
    graphics.fill(left - 1, top - 1, right + 1, bottom + 1, PANEL_EDGE);
    graphics.fill(left, top, right, bottom, PANEL_FACE);
    graphics.fill(left, top, right - 1, top + 1, PANEL_LIGHT);
    graphics.fill(left, top, left + 1, bottom - 1, PANEL_LIGHT);
    graphics.fill(left + 1, bottom - 1, right, bottom, PANEL_DARK);
    graphics.fill(right - 1, top + 1, right, bottom, PANEL_DARK);
  }

  /** The sunken box Minecraft uses for slots and text fields: the panel inverted. */
  private static void slot(GuiGraphics graphics, int left, int top, int right, int bottom) {
    graphics.fill(left, top, right, bottom, SLOT_FACE);
    graphics.fill(left, top, right - 1, top + 1, SLOT_DARK);
    graphics.fill(left, top, left + 1, bottom - 1, SLOT_DARK);
    graphics.fill(left + 1, bottom - 1, right, bottom, PANEL_LIGHT);
    graphics.fill(right - 1, top + 1, right, bottom, PANEL_LIGHT);
  }

  @Override
  public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if ((keyCode == 257 || keyCode == 335) && this.input.isFocused()) { // Enter
      send();
      return true;
    }
    return super.keyPressed(keyCode, scanCode, modifiers);
  }

  /** Chat widgets belong to the chat tab only; the stall has no text entry. */
  private void applyTabVisibility() {
    boolean chatting = tab == Tab.CHAT;
    if (input != null) {
      input.visible = chatting;
    }
    if (sendButton != null) {
      sendButton.visible = chatting;
      sendButton.active = chatting;
    }
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
      for (Row row : rowHits) {
        if (mouseY >= row.y() && mouseY <= row.y() + 18
            && mouseX >= row.left() && mouseX <= row.right()) {
          graphics.renderTooltip(this.font, stackOf(row.itemId(), 1), mouseX, mouseY);
          break;
        }
      }
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
   * The stall, written the way a Minecraft trade is written: a slot, an arrow,
   * a slot. What you hand over on the left of the arrow, what you get on the
   * right. No prices, because an item count IS the price here, and "3.80" was
   * never something a player could act on.
   */
  private void renderTrade(GuiGraphics graphics, int mouseX, int mouseY) {
    rowHits.clear();
    int y = panelTop + 44;

    if (offers == null) {
      centred(graphics, "Opening the stall...", (panelLeft + panelRight) / 2, y, TEXT_PENDING);
      return;
    }
    if (!marketNote.isBlank()) {
      for (FormattedCharSequence line : this.font.split(
          Component.literal(marketNote), panelRight - panelLeft - 24)) {
        graphics.drawString(this.font, line, panelLeft + 12, y, TEXT_LABEL, false);
        y += this.font.lineHeight + 2;
      }
      return;
    }

    // Each column is centred in its own half, so a two-slot trade does not
    // sit marooned against the panel edge with a canyon between the columns.
    int gap = Math.max(8, (panelRight - panelLeft) - COLUMN_WIDTH * 2 - 12);
    int leftColumn = panelLeft + 6;
    int rightColumn = leftColumn + COLUMN_WIDTH + gap;

    graphics.drawString(this.font, "For sale", leftColumn, y, TEXT_LABEL, false);
    graphics.drawString(this.font, "Looking for", rightColumn, y, TEXT_LABEL, false);
    y += 14;

    drawColumn(graphics, offers.selling(), leftColumn, y, mouseX, mouseY, true);
    drawColumn(graphics, offers.wanted(), rightColumn, y, mouseX, mouseY, false);
  }

  private void centred(GuiGraphics graphics, String text, int centreX, int y, int colour) {
    graphics.drawString(this.font, text, centreX - this.font.width(text) / 2, y, colour, false);
  }

  /**
   * One column of trades. {@code playerBuys} decides which way round the arrow
   * reads: buying costs emeralds and yields goods, selling is the reverse.
   */
  private void drawColumn(GuiGraphics graphics, List<com.quzzar.villagelife.networking.MarketOffersPacket.Row> rows,
      int left, int top, int mouseX, int mouseY, boolean playerBuys) {
    int y = top;
    if (rows.isEmpty()) {
      graphics.drawString(this.font, playerBuys ? "nothing spare" : "nothing needed",
          left, y + 5, TEXT_PENDING, false);
      return;
    }
    int right = left + TRADE_WIDTH;
    for (var row : rows) {
      ItemStack goods = stackOf(row.itemId(), row.itemCount());
      ItemStack coins = new ItemStack(Items.EMERALD, row.emeralds());
      ItemStack from = playerBuys ? coins : goods;
      ItemStack into = playerBuys ? goods : coins;

      if (mouseX >= left && mouseX <= left + COLUMN_WIDTH && mouseY >= y && mouseY <= y + 18) {
        graphics.fill(left - 2, y - 1, left + COLUMN_WIDTH, y + 19, ROW_HOVER);
      }
      slot(graphics, left, y, left + 18, y + 18);
      slot(graphics, right - 18, y, right, y + 18);
      graphics.renderItem(from, left + 1, y + 1);
      graphics.renderItemDecorations(this.font, from, left + 1, y + 1);
      graphics.renderItem(into, right - 17, y + 1);
      graphics.renderItemDecorations(this.font, into, right - 17, y + 1);
      arrow(graphics, left + 23, y + 7);

      // Icons alone do not distinguish an oak log from a spruce one.
      int nameLeft = right + 5;
      int nameRoom = left + COLUMN_WIDTH - nameLeft;
      String name = goods.getHoverName().getString();
      if (this.font.width(name) > nameRoom) {
        name = this.font.plainSubstrByWidth(name, nameRoom - this.font.width("..")) + "..";
      }
      graphics.drawString(this.font, name, nameLeft, y + 5, TEXT_LABEL, false);

      rowHits.add(new Row(y, left, left + COLUMN_WIDTH, row.itemId(), playerBuys));
      y += ROW_HEIGHT;
    }
  }

  /** The trade arrow, drawn rather than blitted so no atlas lookup can fail. */
  private static void arrow(GuiGraphics graphics, int x, int y) {
    graphics.fill(x, y + 1, x + 11, y + 3, TEXT_LABEL);
    for (int i = 0; i < 3; i++) {
      graphics.fill(x + 8 + i, y - 1 + i, x + 9 + i, y + 5 - i, TEXT_LABEL);
    }
  }

  private static ItemStack stackOf(String itemId, int count) {
    ResourceLocation id = ResourceLocation.tryParse(itemId);
    Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
    return item == null ? ItemStack.EMPTY : new ItemStack(item, count);
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
        if (mouseY >= row.y() && mouseY <= row.y() + 18
            && mouseX >= row.left() && mouseX <= row.right()) {
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
