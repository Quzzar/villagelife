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
  private static final int PANEL_FACE = 0xFFD0D0D0;
  private static final int PANEL_LIGHT = 0xFFFFFFFF;
  private static final int PANEL_DARK = 0xFF555555;
  private static final int PANEL_EDGE = 0xFF000000;
  private static final int SLOT_FACE = 0xFF8B8B8B;
  private static final int SLOT_DARK = 0xFF373737;
  private static final int DIVIDER = 0xFF8B8B8B;
  private static final int TEXT_LABEL = 0xFF1C1C1C;
  private static final int TEXT_VILLAGER = 0xFF1C1C1C;
  private static final int TEXT_PLAYER = 0xFF16305C;
  private static final int TEXT_PENDING = 0xFF6A6A6A;
  private static final int ICON_IDLE = 0xFF404040;
  private static final int ICON_HOVER = 0xFF000000;
  private static final int ROW_HOVER = 0x40FFFFFF;
  /** A trade row reads as a button: dark face, light edge, brighter on hover. */
  private static final int DEAL_FACE = 0xFF6E6E6E;
  private static final int DEAL_FACE_HOVER = 0xFF8B8B8B;
  private static final int DEAL_EDGE = 0xFFAFAFAF;
  private static final int DEAL_EDGE_HOVER = 0xFFFFFFFF;

  /**
   * Vanilla's own villager container art, and its exact dimensions, because
   * approximating a Minecraft screen by hand never looks like a Minecraft
   * screen. Everything on the trade tab sits at the coordinates MerchantScreen
   * uses, so the recessed trade list, the slots and the inventory are the real
   * ones rather than rectangles that resemble them.
   */
  private static final ResourceLocation VILLAGER_GUI =
      ResourceLocation.withDefaultNamespace("textures/gui/container/villager.png");
  private static final ResourceLocation SCROLLER =
      ResourceLocation.withDefaultNamespace("container/villager/scroller");
  private static final ResourceLocation SCROLLER_DISABLED =
      ResourceLocation.withDefaultNamespace("container/villager/scroller_disabled");
  private static final ResourceLocation TRADE_ARROW =
      ResourceLocation.withDefaultNamespace("container/villager/trade_arrow");
  private static final int PANEL_WIDTH = 276;
  private static final int PANEL_HEIGHT = 192;
  /** Height of the strip above the art: the villager's name and the tabs. */
  private static final int HEAD_STRIP = 26;
  /** MerchantScreen's trade buttons: 89x20 at (5, 16 + n*20). */
  private static final int LIST_LEFT = 5;
  private static final int LIST_TOP = 16;
  private static final int LIST_ROW = 20;
  private static final int LIST_WIDTH = 89;
  /** Rows the vanilla list shows before it must scroll. */
  private static final int LIST_ROWS = 7;
  /** Its player inventory: 3x9 from (108, 84), hotbar at y 142. */
  private static final int INV_LEFT = 108;
  private static final int INV_TOP = 84;
  private static final int HOTBAR_TOP = 142;
  /** Width of the strip the two tabs sit on, above the window. */
  private static final int TAB_STRIP = 122;
  /** MerchantScreen's own trade preview: cost at 136, result at 220, y 37. */
  private static final int PREVIEW_COST = 136;
  private static final int PREVIEW_RESULT = 220;
  private static final int PREVIEW_TOP = 37;
  /** slot, arrow, slot: the width the two slots of one trade occupy. */
  private static final int TRADE_WIDTH = 54;
  /** Left edge to left edge of the two trade columns. */
  private static final int TRADE_COLUMN = 82;
  /** Most rows one column shows, so panel height is not hostage to the stall. */
  private static final int TRADE_ROWS = 3;
  /** Nine slots of eighteen: the width every other thing aligns to. */
  private static final int GRID_WIDTH = 162;
  /** Three rows of pack, a gap, then the hotbar. */
  private static final int GRID_HEIGHT = 3 * 18 + 4 + 18;
  private static final int SLOT_SIZE = 20;
  /** A trade plus the name of the goods, which is what fills a column. */
  private static final int COLUMN_WIDTH = 118;
  private static final int ROW_HEIGHT = 21;

  private static final int LINE_GAP = 2;

  private record ChatLine(String speaker, String text, boolean player) {
  }

  private final int entityId;
  private final String headerName;
  private final String headerDetail;
  private final List<ChatLine> lines = new ArrayList<>();
  /** Which face of the villager is showing. Occupations contribute tabs. */
  private enum Tab { CHAT, TRADE }

  /** Set only by {@link UiPreview}, so either face can be photographed. */
  static Tab previewTab;

  /** Stand-in pack for the preview harness, which renders with no player. */
  static final java.util.Map<Integer, ItemStack> previewInventory = new java.util.HashMap<>();

  static void previewChatTab() {
    previewTab = Tab.CHAT;
  }

  private final boolean canTrade;
  private Tab tab;
  private com.quzzar.villagelife.networking.MarketOffersPacket offers;
  /** Whatever the cursor is over this frame; the tooltip is drawn last, on top. */
  private ItemStack hoveredStack = ItemStack.EMPTY;
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
    this.tab = previewTab != null ? previewTab : (canTrade ? Tab.TRADE : Tab.CHAT);
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
      addRenderableWidget(new TabButton(panelLeft + 8, panelTop + 12, tabWidth, "Chat", Tab.CHAT));
      addRenderableWidget(new TabButton(panelLeft + 10 + tabWidth, panelTop + 12, tabWidth, "Trade", Tab.TRADE));
      toServer(
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
    addRenderableWidget(this.input);

    this.sendButton = addRenderableWidget(new IconButton(panelRight - 30, inputRowTop, 20,
        IconButton.SEND, button -> send()));
    applyTabVisibility();

    setInitialFocus(this.input);
  }

  @Override
  public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    // super gives the blurred, darkened world behind the panel.
    super.renderBackground(graphics, mouseX, mouseY, partialTick);
    // One window: a title strip holding the villager and the tabs, with the
    // trade tab hanging the real container art directly beneath it.
    panel(graphics, panelLeft, panelTop, panelRight, panelTop + HEAD_STRIP + 2);
    if (tab == Tab.TRADE) {
      graphics.blit(VILLAGER_GUI, panelLeft, panelTop + HEAD_STRIP, 0, 0.0F, 0.0F,
          PANEL_WIDTH, PANEL_HEIGHT - HEAD_STRIP, 512, 256);
      return;
    }
    panel(graphics, panelLeft, panelTop, panelRight, panelBottom);


    if (tab != Tab.CHAT) {
      return;
    }
    slot(graphics, panelLeft + 8, inputRowTop, panelRight - 34, inputRowTop + 20);
    if (input != null && input.getValue().isEmpty()) {
      graphics.drawString(this.font, Component.translatable("villagelife.chat.say"),
          panelLeft + 20, inputRowTop + 6, TEXT_PENDING, false);
    }
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

  /**
   * Sends only when there is a server to send to. A screen can outlive its
   * connection (a disconnect mid-conversation, or the UI preview harness,
   * which renders this screen with no session at all), and sendToServer
   * dereferences the connection without checking.
   */
  private static void toServer(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
    if (Minecraft.getInstance().getConnection() != null) {
      PacketDistributor.sendToServer(payload);
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
    toServer(new PersonChatMessagePacket(entityId, text));
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);

    if (awaitingReply && System.currentTimeMillis() - awaitingSinceMs > AWAIT_RELEASE_MS) {
      awaitingReply = false; // let the player speak again rather than wait forever
    }

    // The stall is titled like a shop, not like a person: vanilla puts the
    // trader's name here, and the equivalent for a village market is the
    // market itself. The villager's own name belongs on the tab where you
    // are actually talking to them.
    String header = headerName + ", " + headerDetail;
    int headerLeft = panelLeft + 8;
    int room = (panelRight - 8) - headerLeft;
    if (this.font.width(header) > room) {
      header = this.font.plainSubstrByWidth(header, room - this.font.width("..")) + "..";
    }
    graphics.drawString(this.font, header,
        headerLeft + Math.max(0, (room - this.font.width(header)) / 2), panelTop + 5,
        TEXT_LABEL, false);

    if (tab == Tab.TRADE) {
      hoveredStack = ItemStack.EMPTY;
      renderTrade(graphics, mouseX, mouseY);
      for (Row row : rowHits) {
        if (mouseY >= row.y() && mouseY <= row.y() + SLOT_SIZE
            && mouseX >= row.left() && mouseX <= row.right()) {
          hoveredStack = stackOf(row.itemId(), 1);
          break;
        }
      }
      if (!hoveredStack.isEmpty()) {
        graphics.renderTooltip(this.font, hoveredStack, mouseX, mouseY);
      }
      return;
    }

    int textWidth = panelRight - panelLeft - 24;
    int left = panelLeft + 12;
    int top = panelTop + (canTrade ? 30 : 20);
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
    int y = top;
    for (int i = start; i < rendered.size(); i++) {
      graphics.drawString(this.font, rendered.get(i), left, y, colours.get(i), false);
      y += this.font.lineHeight + LINE_GAP;
    }
  }

  @Override
  public void removed() {
    // Tell the server the conversation ended so the villager goes back to life.
    toServer(new com.quzzar.villagelife.networking.PersonChatClosePacket(entityId));
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
   * The stall as one list of trades, plus the player's own inventory.
   *
   * For-sale and looking-for were two columns describing one operation:
   * converting a stack of one thing into a stack of another. Emeralds on the
   * left is buying, emeralds on the right is selling, and the arrow already
   * says which. Splitting them implied a distinction that does not exist, and
   * cost the width of a second column to do it.
   *
   * The inventory is shown because a trade is only worth clicking if you have
   * the goods, and having to close the screen to check defeats the point. It
   * is a read-only view: trading happens by clicking a row, never by dragging.
   */
  /** Top of the container art, which the trade tab's coordinates are relative to. */
  private int artTop() {
    return panelTop + HEAD_STRIP;
  }

  private void renderTrade(GuiGraphics graphics, int mouseX, int mouseY) {
    rowHits.clear();
    if (offers == null) {
      graphics.drawString(this.font, "Opening the stall...",
          panelLeft + LIST_LEFT, artTop() + LIST_TOP + 4, TEXT_PENDING, false);
      return;
    }
    if (!marketNote.isBlank()) {
      for (FormattedCharSequence line : this.font.split(
          Component.literal(marketNote), LIST_WIDTH + 40)) {
        graphics.drawString(this.font, line, panelLeft + LIST_LEFT, artTop() + LIST_TOP + 4,
            TEXT_LABEL, false);
      }
      return;
    }

    graphics.drawString(this.font, "Trades", panelLeft + LIST_LEFT, artTop() + 6,
        TEXT_LABEL, false);
    if (!offers.villageName().isBlank()) {
      graphics.drawString(this.font, offers.villageName() + " Market",
          panelLeft + INV_LEFT, artTop() + 6, TEXT_LABEL, false);
    }

    List<Deal> deals = new ArrayList<>();
    for (var row : offers.selling()) {
      deals.add(new Deal(new ItemStack(Items.EMERALD, row.emeralds()),
          stackOf(row.itemId(), row.itemCount()), row.itemId(), true));
    }
    for (var row : offers.wanted()) {
      deals.add(new Deal(stackOf(row.itemId(), row.itemCount()),
          new ItemStack(Items.EMERALD, row.emeralds()), row.itemId(), false));
    }

    if (deals.isEmpty()) {
      graphics.drawString(this.font, "Nothing to trade.",
          panelLeft + LIST_LEFT, artTop() + LIST_TOP + 4, TEXT_PENDING, false);
    }
    int visible = Math.min(deals.size(), LIST_ROWS);
    for (int i = 0; i < visible; i++) {
      drawDeal(graphics, deals.get(i), panelLeft + LIST_LEFT, artTop() + LIST_TOP + i * LIST_ROW,
          mouseX, mouseY);
    }

    graphics.blitSprite(deals.size() > LIST_ROWS ? SCROLLER : SCROLLER_DISABLED,
        panelLeft + 94, artTop() + LIST_TOP, 6, 27);

    drawInventory(graphics, panelLeft + INV_LEFT, artTop() + INV_TOP, mouseX, mouseY);

    Deal shown = hoveredDeal(deals, visible, mouseX, mouseY);
    if (shown != null) {
      graphics.renderItem(shown.from(), panelLeft + PREVIEW_COST, artTop() + PREVIEW_TOP);
      graphics.renderItemDecorations(this.font, shown.from(),
          panelLeft + PREVIEW_COST, artTop() + PREVIEW_TOP);
      graphics.renderItem(shown.into(), panelLeft + PREVIEW_RESULT, artTop() + PREVIEW_TOP);
      graphics.renderItemDecorations(this.font, shown.into(),
          panelLeft + PREVIEW_RESULT, artTop() + PREVIEW_TOP);
    }
  }

  /** The trade under the cursor, which is what the preview slots show. */
  private Deal hoveredDeal(List<Deal> deals, int visible, int mouseX, int mouseY) {
    for (int i = 0; i < visible; i++) {
      int top = artTop() + LIST_TOP + i * LIST_ROW;
      if (mouseX >= panelLeft + LIST_LEFT && mouseX < panelLeft + LIST_LEFT + LIST_WIDTH
          && mouseY >= top && mouseY < top + LIST_ROW) {
        return deals.get(i);
      }
    }
    return null;
  }

  /** One trade: what you hand over, what you get back. */
  private record Deal(ItemStack from, ItemStack into, String itemId, boolean playerBuys) {
  }

  private void drawDeal(GuiGraphics graphics, Deal deal, int left, int top, int mouseX, int mouseY) {
    boolean hovered = mouseX >= left && mouseX < left + LIST_WIDTH
        && mouseY >= top && mouseY < top + LIST_ROW;
    graphics.fill(left, top, left + LIST_WIDTH, top + LIST_ROW,
        hovered ? DEAL_FACE_HOVER : DEAL_FACE);
    int border = hovered ? DEAL_EDGE_HOVER : DEAL_EDGE;
    graphics.fill(left, top, left + LIST_WIDTH, top + 1, border);
    graphics.fill(left, top + LIST_ROW - 1, left + LIST_WIDTH, top + LIST_ROW, border);
    graphics.fill(left, top, left + 1, top + LIST_ROW, border);
    graphics.fill(left + LIST_WIDTH - 1, top, left + LIST_WIDTH, top + LIST_ROW, border);
    graphics.renderItem(deal.from(), left + 2, top + 2);
    graphics.renderItemDecorations(this.font, deal.from(), left + 2, top + 2);
    graphics.blitSprite(TRADE_ARROW, left + 34, top + 5, 10, 9);
    graphics.renderItem(deal.into(), left + 60, top + 2);
    graphics.renderItemDecorations(this.font, deal.into(), left + 60, top + 2);
    rowHits.add(new Row(top, left, left + LIST_WIDTH, deal.itemId(), deal.playerBuys()));
  }

  /** The player's own pack, so a trade can be judged without closing the screen. */
  private void drawInventory(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
    // No player under the preview harness, and none after a disconnect either.
    var player = Minecraft.getInstance().player;
    for (int index = 0; index < 36; index++) {
      int column = index % 9;
      int x = left + column * 18;
      // The hotbar is its own row on the texture, below a gap.
      int y = index < 9 ? artTop() + HOTBAR_TOP : top + ((index / 9) - 1) * 18;
      ItemStack stack = player != null ? player.getInventory().getItem(index)
          : previewInventory.getOrDefault(index, ItemStack.EMPTY);
      if (stack.isEmpty()) {
        continue;
      }
      graphics.renderItem(stack, x, y);
      graphics.renderItemDecorations(this.font, stack, x, y);
      if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
        hoveredStack = stack;
      }
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
          toServer(new com.quzzar.villagelife.networking.MarketActionPacket(
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
        toServer(
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
        // Flat and quiet with nothing to send; a raised button once there is a
        // message, so the screen tells you it is ready rather than waiting to
        // be guessed at.
        boolean ready = input != null && !input.getValue().isBlank();
        if (ready) {
          panel(graphics, getX(), getY(), getX() + width, getY() + height);
        }
        int glyph = ready ? ICON_HOVER : (hovered ? ICON_HOVER : ICON_IDLE);
        int size = 5;
        int x0 = getX() + (width - size) / 2;
        int y0 = getY() + (height - (size * 2 - 1)) / 2;
        for (int i = 0; i < size; i++) {
          graphics.fill(x0 + i, y0 + i, x0 + i + 1, y0 + (size * 2 - 1) - i, glyph);
        }
      }
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
      defaultButtonNarrationText(output);
    }
  }
}
