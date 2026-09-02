package com.quzzar.villagelife.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.quzzar.villagelife.networking.PersonChatMessagePacket;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
public class PersonChatScreen
    extends net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<
        com.quzzar.villagelife.menu.MarketMenu> {

  // Minecraft's own GUI palette: the grey panel, its bevel, and the inset
  // slot. Matching these is what makes a modded screen read as part of the
  // game rather than as something bolted on.
  private static final int PANEL_FACE = 0xFFC6C6C6;
  private static final int PANEL_LIGHT = 0xFFFFFFFF;
  private static final int PANEL_DARK = 0xFF555555;
  private static final int PANEL_EDGE = 0xFF000000;
  private static final int SLOT_FACE = 0xFF8B8B8B;
  private static final int SLOT_DARK = 0xFF373737;
  /** The grey villager.png paints its long arrow in, sampled from the file. */
  private static final int ARROW_W = 22;
  private static final int ARROW_H = 15;
  /** The stroke of a closed-for-trade cross. Red on the cross reads fine; it
   * was recolouring the ARROW that looked like an error rather than a shut shop. */
  private static final int CROSS = 0xFFAA3333;
  private static final int DIVIDER = 0xFF8B8B8B;
  private static final int TEXT_LABEL = 0xFF1C1C1C;
  private static final int TEXT_VILLAGER = 0xFF1C1C1C;
  private static final int TEXT_PLAYER = 0xFF16305C;
  private static final int TEXT_PENDING = 0xFF6A6A6A;
  /** Typed text is white on the sunken field, with the hint a step down from it. */
  private static final int INPUT_TEXT = 0xFFFFFFFF;
  private static final int INPUT_HINT = 0xFFBCBCBC;
  /**
   * Where text sits inside the 20px field. An unbordered EditBox draws at
   * getY() with no centring of its own, so this is the one number both the
   * typed text and the hint use; they were two pixels apart before.
   */
  private static final int INPUT_TEXT_Y = 7;
  /** Conversation colours, chosen for the sunken well rather than the panel. */
  private static final int CHAT_VILLAGER = 0xFFF0F0F0;
  private static final int CHAT_PLAYER = 0xFF8FD3FF;
  private static final int CHAT_PENDING = 0xFFB4B4B4;
  /** The send glyph, dark on a live button and washed out on a dead one. */
  /** Six by twelve, so it divides exactly into the twenty pixel button. */
  private static final int ARROW_GLYPH_W = 6;
  private static final int ARROW_GLYPH_H = 12;
  /** White in both states; the button face is what changes. */
  private static final int SEND_ON = 0xFF1E1E1E;
  private static final int SEND_OFF = 0xFF9A9A9A;
  private static final int ICON_IDLE = 0xFF404040;
  private static final int ICON_HOVER = 0xFF000000;
  private static final int ROW_HOVER = 0x40FFFFFF;
  /** A wash over the chosen tab, so it is not the window's own grey. */
  private static final int TAB_ACTIVE = 0x33566E8C;
  /** A trade row reads as a button: dark face, light edge, brighter on hover. */
  private static final int DEAL_FACE = 0xFF5B5B5B;

  private static final int DEAL_EDGE = 0xFF1A1A1A;
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
  /**
   * A vanilla trade row is a plain Button: MerchantScreen.TradeOfferButton
   * extends Button and never overrides its rendering, which is why the rows in
   * a villager screen are the game's own stone-grey button art. Painting a
   * lookalike by hand is what made mine read as not-quite-Minecraft.
   */
  private static final ResourceLocation BUTTON =
      ResourceLocation.withDefaultNamespace("widget/button");
  private static final ResourceLocation BUTTON_HOVER =
      ResourceLocation.withDefaultNamespace("widget/button_highlighted");
  private static final ResourceLocation SCROLLER =
      ResourceLocation.withDefaultNamespace("container/villager/scroller");
  private static final ResourceLocation SCROLLER_DISABLED =
      ResourceLocation.withDefaultNamespace("container/villager/scroller_disabled");
  private static final ResourceLocation TRADE_ARROW =
      ResourceLocation.withDefaultNamespace("container/villager/trade_arrow");
  /** Vanilla's struck-out arrow, used when the whole stall cannot trade. */
  private static final ResourceLocation TRADE_ARROW_BLOCKED =
      ResourceLocation.withDefaultNamespace("container/villager/trade_arrow_out_of_stock");
  private static final int PANEL_WIDTH = 286;
  private static final int PANEL_HEIGHT = 192;
  /** Height of the strip above the art: the villager's name and the tabs. */
  private static final int HEAD_STRIP = com.quzzar.villagelife.menu.MarketMenu.HEAD;
  /** Where the rule under the tabs sits, measured from the panel top. */
  private static final int TAB_RULE = 40;
  /** Width of the trades well: the buttons plus their scrollbar. */
  private static final int WELL_WIDTH = 100;
  /** Where the right-hand column (market, preview, inventory) begins. */
  private static final int RIGHT_COLUMN = 124;
  /** The hotbar hangs below the pack, the way every Minecraft screen shows it. */
  private static final int HOTBAR_GAP = 4;
  /** MerchantScreen's trade buttons: 89x20 at (5, 16 + n*20). */
  private static final int LIST_LEFT = 5;
  private static final int LIST_TOP = 16;
  private static final int LIST_ROW = 20;
  private static final int LIST_WIDTH = 88;
  /** Rows the vanilla list shows before it must scroll. */
  private static final int LIST_ROWS = 6;
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
  /** Air between one message and the next, so the log is not a wall. */
  private static final int MESSAGE_GAP = 5;
  /** Breathing room between the tab rule and the first line of talk. */
  private static final int CHAT_TOP = 52;

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

  /** Preview only: opens with text already typed, so the arrow is shown. */
  static void previewTyping(String text) {
    previewTyped = text;
  }

  private static String previewTyped;

  /**
   * The emerald price of one unit of the selected trade, for the self-test.
   * Read off the row the screen is showing, so an assertion is against the
   * ADVERTISED rate rather than against what the trade happened to do.
   */
  public static int selectedUnitPrice() {
    if (Minecraft.getInstance().screen instanceof PersonChatScreen screen
        && screen.selected >= 0 && screen.selected < screen.allDeals.size()) {
      Deal deal = screen.allDeals.get(screen.selected);
      return deal.playerBuys() ? deal.from().getCount() : deal.into().getCount();
    }
    return 0;
  }

  /** Diagnostic for the self-test: what the screen thinks it has. */
  public static String previewState() {
    if (!(Minecraft.getInstance().screen instanceof PersonChatScreen screen)) {
      return "screen=" + (Minecraft.getInstance().screen == null ? "null"
          : Minecraft.getInstance().screen.getClass().getSimpleName());
    }
    return "screen=PersonChatScreen tab=" + screen.tab + " canTrade=" + screen.canTrade
        + " offers=" + (screen.offers == null ? "null"
            : screen.offers.selling().size() + "sell/" + screen.offers.wanted().size() + "want")
        + " deals=" + screen.allDeals.size();
  }

  /** First row where the player PAYS emeralds, so a test picks a known shape. */
  public static int firstBuyIndex() {
    if (Minecraft.getInstance().screen instanceof PersonChatScreen screen) {
      for (int i = 0; i < screen.allDeals.size(); i++) {
        if (screen.allDeals.get(i).playerBuys()) {
          return i;
        }
      }
    }
    return -1;
  }

  /** Goods traded per unit of the selected deal (bread, oak log, etc.). */
  public static int selectedGoodsCount() {
    if (Minecraft.getInstance().screen instanceof PersonChatScreen screen
        && screen.selected >= 0 && screen.selected < screen.allDeals.size()) {
      Deal deal = screen.allDeals.get(screen.selected);
      return deal.playerBuys() ? deal.into().getCount() : deal.from().getCount();
    }
    return 0;
  }

  /** Emeralds traded per unit of the selected deal. */
  public static int selectedEmeraldCount() {
    return selectedUnitPrice();
  }

  /** Goods per unit of deal {@code i}, read by explicit index (selection is server-side). */
  public static int dealGoods(int i) {
    if (Minecraft.getInstance().screen instanceof PersonChatScreen s
        && i >= 0 && i < s.allDeals.size()) {
      Deal d = s.allDeals.get(i);
      return d.playerBuys() ? d.into().getCount() : d.from().getCount();
    }
    return 0;
  }

  /** Emeralds per unit of deal {@code i}. */
  public static int dealEmeralds(int i) {
    if (Minecraft.getInstance().screen instanceof PersonChatScreen s
        && i >= 0 && i < s.allDeals.size()) {
      Deal d = s.allDeals.get(i);
      return d.playerBuys() ? d.from().getCount() : d.into().getCount();
    }
    return 0;
  }

  /** How many deals the open stall has, for a test that walks them. */
  public static int dealCount() {
    return Minecraft.getInstance().screen instanceof PersonChatScreen screen
        ? screen.allDeals.size() : 0;
  }

  /** True when deal {@code i} is a SELL: the player hands goods over for emeralds. */
  public static boolean dealIsSell(int i) {
    return Minecraft.getInstance().screen instanceof PersonChatScreen screen
        && i >= 0 && i < screen.allDeals.size() && !screen.allDeals.get(i).playerBuys();
  }

  /** The goods item id in deal {@code i}, so a test can hold and count it. */
  public static String dealItemId(int i) {
    return Minecraft.getInstance().screen instanceof PersonChatScreen screen
        && i >= 0 && i < screen.allDeals.size() ? screen.allDeals.get(i).itemId() : "";
  }

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

  /**
   * How long the villager's farewell stays on screen before the screen closes
   * itself: long enough to read, short enough that the talk is plainly over.
   */
  private static final long LEAVE_CLOSE_MS = 10_000;

  /** When the screen closes itself after the villager took their leave; 0 while they have not. */
  private long closeAtMs;

  // The newest reply is revealed a character at a time, like the villager is
  // saying it, rather than appearing whole. Only a FRESH reply reveals; history
  // reloaded from the server shows at once. -1 when nothing is revealing.
  private int revealingLine = -1;
  private long revealStartMs;
  /** Reveal rate: about 45 characters a second, near an easy reading pace. */
  private static final double REVEAL_CHARS_PER_MS = 0.045D;

  private int panelLeft;
  private int panelRight;
  private int panelTop;
  private int panelBottom;
  private int inputRowTop;

  public PersonChatScreen(com.quzzar.villagelife.menu.MarketMenu menu,
      net.minecraft.world.entity.player.Inventory inventory, Component title) {
    super(menu, inventory, title);
    this.entityId = menu.merchantId();
    this.headerName = menu.headerName();
    this.headerDetail = menu.headerDetail();
    this.canTrade = menu.canTrade();
    this.tab = previewTab != null ? previewTab : (canTrade ? Tab.TRADE : Tab.CHAT);
    this.imageWidth = PANEL_WIDTH;
    this.imageHeight = PANEL_HEIGHT;
    this.awaitingReply = false;
  }

  /**
   * The conversation so far, delivered separately from the menu: a container's
   * extra data is opened once and cannot carry a growing log.
   */
  public static void openChat(int entityId, List<
      com.quzzar.villagelife.networking.OpenPersonChatPacket.ExchangeLine> scrollback) {
    if (!(Minecraft.getInstance().screen instanceof PersonChatScreen screen)
        || screen.entityId != entityId) {
      return;
    }
    screen.lines.clear();
    for (var exchange : scrollback) {
      if (!exchange.playerLine().isBlank()) {
        screen.lines.add(new ChatLine("You", exchange.playerLine(), true));
      }
      screen.lines.add(new ChatLine(screen.headerName, exchange.reply(), false));
    }
    screen.awaitingReply = screen.lines.isEmpty();
    screen.awaitingSinceMs = System.currentTimeMillis();
    screen.chatScrollUp = 0;
    screen.revealingLine = -1; // reloaded history is not new; show it at once
  }

  /** The stall's current state arrived; repaint the trade tab. */
  public static void onMarketOffers(com.quzzar.villagelife.networking.MarketOffersPacket packet) {
    if (Minecraft.getInstance().screen instanceof PersonChatScreen screen
        && screen.entityId == packet.entityId()) {
      screen.offers = packet;
      screen.marketNote = packet.message();
    }
  }

  public static void onReply(int entityId, String text, boolean done) {
    if (Minecraft.getInstance().screen instanceof PersonChatScreen screen && screen.entityId == entityId) {
      // Start revealing this line as it is added: its index is the current size.
      screen.revealingLine = screen.lines.size();
      screen.revealStartMs = System.currentTimeMillis();
      screen.lines.add(new ChatLine(screen.headerName, text, false));
      screen.chatScrollUp = 0;
      screen.awaitingReply = false;
      // The villager took their leave: the farewell shows, then the screen closes
      // itself, which closes the session the ordinary way (removed()). A later
      // reply that is not a farewell cancels it: they had more to say after all.
      screen.closeAtMs = done ? System.currentTimeMillis() + LEAVE_CLOSE_MS : 0;
    }
  }

  @Override
  protected void init() {
    super.init();
    // The container's own labels are suppressed: this window titles itself.
    this.titleLabelX = Integer.MIN_VALUE / 2;
    this.inventoryLabelX = Integer.MIN_VALUE / 2;
    scrollOff = 0; // a stall always opens at the top of its list
    // leftPos and topPos come from AbstractContainerScreen, computed from
    // imageWidth and imageHeight. Everything drawn here must hang off them, or
    // the drawing and the SLOTS would be positioned by two different rules and
    // drift apart the moment the window moves.
    this.panelLeft = this.leftPos;
    this.panelRight = leftPos + imageWidth;
    this.panelTop = this.topPos;
    this.panelBottom = topPos + imageHeight;
    this.inputRowTop = panelBottom - 30;

    if (canTrade) {
      int tabWidth = 58;
      addRenderableWidget(new TabButton(panelLeft + 8, panelTop + 24, tabWidth, "Chat", Tab.CHAT));
      addRenderableWidget(new TabButton(panelLeft + 10 + tabWidth, panelTop + 24, tabWidth, "Trade", Tab.TRADE));
      toServer(
          com.quzzar.villagelife.networking.MarketActionPacket.refresh(entityId));
    }

    // The field sits inside the sunken box drawn in renderBackground, and its
    // text is centred on that box: an 8px line in a 20px box starts 6px down.
    int fieldLeft = panelLeft + 8;
    int fieldRight = panelRight - 34;
    this.input = new EditBox(this.font, fieldLeft + 4, inputRowTop + INPUT_TEXT_Y, fieldRight - fieldLeft - 8, 12,
        Component.translatable("villagelife.chat.say"));
    this.input.setMaxLength(PersonChatMessagePacket.MAX_TEXT_LENGTH);
    this.input.setBordered(false);
    this.input.setTextColor(INPUT_TEXT);
    addRenderableWidget(this.input);

    this.sendButton = addRenderableWidget(new IconButton(panelRight - 30, inputRowTop, 20,
        IconButton.SEND, button -> send()));
    applyTabVisibility();

    if (previewTab == Tab.TRADE || previewTab == null) {
      staged = 0; // a freshly opened stall has nothing staged until you pick one
    }
    if (previewTyped != null) {
      this.input.setValue(previewTyped);
      sendReveal = 1.0F;
    }
    setInitialFocus(this.input);
  }

  @Override
  protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
    // ONE window for both tabs. Blitting the whole villager container put a
    // second frame inside this one and made the two tabs look like different
    // screens; what belongs here is its CONTENTS, drawn in the same frame.
    panel(graphics, panelLeft, panelTop, panelRight, panelBottom);
    if (canTrade) {
      // The rule the tabs stand on. The active tab is drawn over it as a
      // raised stub, so it breaks the line and reads as joined to the page.
      int rule = panelTop + TAB_RULE;
      graphics.fill(panelLeft + 8, rule, panelRight - 8, rule + 1, PANEL_DARK);
      graphics.fill(panelLeft + 8, rule + 1, panelRight - 8, rule + 2, PANEL_LIGHT);
    }

    if (tab != Tab.CHAT) {
      return;
    }
    slot(graphics, panelLeft + 8, inputRowTop, panelRight - 34, inputRowTop + 20);
    if (input != null && input.getValue().isEmpty()) {
      graphics.drawString(this.font, Component.translatable("villagelife.chat.say"),
          panelLeft + 20, inputRowTop + INPUT_TEXT_Y, INPUT_HINT, true);
    }
  }

  /**
   * The raised grey panel Minecraft draws every one of its own windows with,
   * matched to vanilla's container art (villager.png): the same grey face, a 2px
   * light bevel on the top and left, a 2px dark bevel on the bottom and right, a
   * 1px black outline, and the four corners rounded off with a chamfer. The
   * square black corner a hand-drawn panel leaves is the tell that it is not the
   * game's own; the chamfer is the "border radius" vanilla actually has.
   */
  private static void panel(GuiGraphics graphics, int left, int top, int right, int bottom) {
    graphics.fill(left, top, right, bottom, PANEL_FACE);
    graphics.fill(left, top, right, top + 2, PANEL_LIGHT);
    graphics.fill(left, top, left + 2, bottom, PANEL_LIGHT);
    graphics.fill(left, bottom - 2, right, bottom, PANEL_DARK);
    graphics.fill(right - 2, top, right, bottom, PANEL_DARK);
    // The outline: four edges each held a pixel off the corners, then a single
    // diagonal pixel closing each corner, so the corner is chamfered rather than
    // a hard square.
    graphics.fill(left + 1, top - 1, right - 1, top, PANEL_EDGE);
    graphics.fill(left + 1, bottom, right - 1, bottom + 1, PANEL_EDGE);
    graphics.fill(left - 1, top + 1, left, bottom - 1, PANEL_EDGE);
    graphics.fill(right, top + 1, right + 1, bottom - 1, PANEL_EDGE);
    graphics.fill(left, top, left + 1, top + 1, PANEL_EDGE);
    graphics.fill(right - 1, top, right, top + 1, PANEL_EDGE);
    graphics.fill(left, bottom - 1, left + 1, bottom, PANEL_EDGE);
    graphics.fill(right - 1, bottom - 1, right, bottom, PANEL_EDGE);
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
    // A container screen closes itself on the inventory key, which on a screen
    // you TYPE into means the letter E shuts the window instead of appearing.
    // Vanilla's own text-field screens (AnvilScreen and friends) solve it the
    // same way: let the field consume the key first, and only fall through when
    // it cannot.
    if (tab == Tab.CHAT && input != null && input.isFocused()) {
      if (keyCode == 257 || keyCode == 335) { // Enter
        send();
        return true;
      }
      if (keyCode != 256 && (input.keyPressed(keyCode, scanCode, modifiers) || input.canConsumeInput())) {
        return true;
      }
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
    chatScrollUp = 0;
    awaitingReply = true;
    awaitingSinceMs = System.currentTimeMillis();
    this.input.setValue("");
    toServer(new PersonChatMessagePacket(entityId, text));
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    super.render(graphics, mouseX, mouseY, partialTick);

    // The send arrow arrives when there is something to send rather than
    // sitting there greyed out, which is how a chat box tells you it is ready.
    boolean ready = tab == Tab.CHAT && input != null && !input.getValue().isBlank();
    sendReveal = Math.max(0.0F, Math.min(1.0F, sendReveal + (ready ? 0.2F : -0.25F)));
    if (sendButton != null) {
      sendButton.active = ready;
    }

    if (awaitingReply && System.currentTimeMillis() - awaitingSinceMs > AWAIT_RELEASE_MS) {
      awaitingReply = false; // let the player speak again rather than wait forever
    }

    // The stall is titled like a shop, not like a person: vanilla puts the
    // trader's name here, and the equivalent for a village market is the
    // market itself. The villager's own name belongs on the tab where you
    // are actually talking to them.
    Component header = Component.empty()
        .append(Component.literal(headerName).withStyle(ChatFormatting.BOLD))
        .append(Component.literal(", " + headerDetail));
    int headerLeft = panelLeft + 8;
    int room = (panelRight - 8) - headerLeft;
    graphics.drawString(this.font, header,
        headerLeft + Math.max(0, (room - this.font.width(header)) / 2), panelTop + 7,
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

    // The conversation sits in a sunken well, like the trades list, so the two
    // tabs are furnished the same way.
    int wellLeft = panelLeft + 8;
    int wellRight = panelRight - 8;
    int wellTop = panelTop + (canTrade ? CHAT_TOP : 26);
    int wellBottom = inputRowTop - 6;
    slot(graphics, wellLeft, wellTop, wellRight, wellBottom);

    int textWidth = wellRight - wellLeft - 20;
    int left = wellLeft + 6;
    int top = wellTop + 5;
    int bottom = wellBottom - 5;

    List<Line> rendered = new ArrayList<>();
    for (int idx = 0; idx < lines.size(); idx++) {
      ChatLine line = lines.get(idx);
      int colour = line.player() ? CHAT_PLAYER : CHAT_VILLAGER;
      String shown = line.text();
      boolean revealing = false;
      if (idx == revealingLine) {
        int chars = (int) ((System.currentTimeMillis() - revealStartMs) * REVEAL_CHARS_PER_MS);
        if (chars < shown.length()) {
          shown = shown.substring(0, Math.max(0, chars));
          revealing = true;
        } else {
          revealingLine = -1; // fully shown; stop tracking it
        }
      }
      net.minecraft.network.chat.MutableComponent full = Component.empty()
          .append(Component.literal(line.speaker() + ": ").withStyle(ChatFormatting.BOLD))
          .append(com.quzzar.villagelife.chat.ChatMarkdown.render(shown));
      // A cursor blinking at the end while the words are still arriving, so a
      // mid-reveal pause reads as 'still speaking' rather than as a short reply
      // that has finished. Underscore is the game's own text-cursor glyph and
      // is always in the font; a block character risks a missing-glyph box.
      if (revealing && (System.currentTimeMillis() / 450L) % 2L == 0L) {
        full.append(Component.literal("_").withStyle(ChatFormatting.GRAY));
      }
      boolean first = true;
      for (FormattedCharSequence part : this.font.split(full, textWidth)) {
        rendered.add(new Line(part, colour, first));
        first = false;
      }
    }
    if (awaitingReply) {
      // Cycles ., .., ... roughly three times a second, so a slow reply reads
      // as working rather than as hung.
      int dots = 1 + (int) ((System.currentTimeMillis() / 350L) % 3L);
      rendered.add(new Line(Component.literal(headerName + " " + ".".repeat(dots))
          .getVisualOrderText(), CHAT_PENDING, true));
    }

    // Height per line INCLUDING the gap that precedes it. Measuring capacity in
    // bare lines ignored those gaps, so the newest lines fell off the bottom of
    // the well: a reply could arrive and simply not be visible until something
    // else pushed the log around.
    int step = this.font.lineHeight + LINE_GAP;
    int[] heights = new int[rendered.size()];
    for (int i = 0; i < rendered.size(); i++) {
      heights[i] = step + (rendered.get(i).first() && i > 0 ? MESSAGE_GAP : 0);
    }
    int wellRoom = bottom - top;
    // Walk back from the newest line until the next one would not fit.
    int firstVisible = rendered.size();
    int used = 0;
    while (firstVisible > 0 && used + heights[firstVisible - 1] <= wellRoom) {
      used += heights[firstVisible - 1];
      firstVisible--;
    }
    chatOverflow = firstVisible;
    chatScrollUp = Math.min(chatScrollUp, chatOverflow);
    int startLine = Math.max(0, firstVisible - chatScrollUp);

    int y = top;
    for (int i = startLine; i < rendered.size(); i++) {
      if (i > startLine && rendered.get(i).first()) {
        y += MESSAGE_GAP;
      }
      if (y + this.font.lineHeight > bottom) {
        break;
      }
      graphics.drawString(this.font, rendered.get(i).seq(), left, y, rendered.get(i).colour(), false);
      y += step;
    }

    // The same scrollbar the trades list uses.
    int trackLeft = wellRight - 10;
    slot(graphics, trackLeft, wellTop + 2, trackLeft + 8, wellBottom - 2);
    int trackTop = wellTop + 3;
    int trackRun = (wellBottom - 3) - trackTop - 27;
    int handle = chatOverflow == 0 ? 0
        : Math.round((float) (chatOverflow - chatScrollUp) / chatOverflow * trackRun);
    graphics.blitSprite(chatOverflow == 0 ? SCROLLER_DISABLED : SCROLLER,
        trackLeft + 1, trackTop + handle, 6, 27);
  }

  /** One laid-out line of conversation. {@code first} starts a new message. */
  private record Line(FormattedCharSequence seq, int colour, boolean first) {
  }

  /**
   * A conversation ends when either party is hurt. Standing in a chat window
   * while something is hitting you is not a conversation, and the screen has
   * no business holding the cursor through it.
   *
   * Watched on the client from the health both sides already sync, rather than
   * from a damage event and a new packet: the screen only has to notice, and
   * a drop it can see is a drop worth closing on.
   */
  @Override
  protected void renderSlot(GuiGraphics graphics, net.minecraft.world.inventory.Slot slot) {
    if (tab == Tab.TRADE) {
      super.renderSlot(graphics, slot);
    }
  }

  @Override
  protected boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY) {
    return tab == Tab.TRADE && super.isHovering(x, y, width, height, mouseX, mouseY);
  }

  @Override
  protected void containerTick() {
    Minecraft client = Minecraft.getInstance();
    if (client.player == null || client.level == null) {
      return;
    }
    float playerHealth = client.player.getHealth();
    Entity person = client.level.getEntity(entityId);
    float personHealth = person instanceof LivingEntity living ? living.getHealth() : Float.NaN;

    boolean playerHurt = !Float.isNaN(lastPlayerHealth) && playerHealth < lastPlayerHealth;
    boolean personHurt = !Float.isNaN(lastPersonHealth) && !Float.isNaN(personHealth)
        && personHealth < lastPersonHealth;
    // Only once we have actually SEEN them. The client may not be tracking the
    // entity yet when the screen opens, and treating "not found" as "gone"
    // slammed the screen shut the instant it appeared.
    if (person instanceof LivingEntity living && living.isAlive()) {
      sawPerson = true;
    }
    boolean personGone = sawPerson
        && (person == null || (person instanceof LivingEntity living && !living.isAlive()));

    lastPlayerHealth = playerHealth;
    lastPersonHealth = personHealth;
    boolean leaveTaken = closeAtMs != 0 && System.currentTimeMillis() >= closeAtMs;
    if (playerHurt || personHurt || personGone || leaveTaken) {
      onClose();
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
  /** The deals actually drawn this frame, parallel to {@link #rowHits}. */
  private List<Deal> shown = new ArrayList<>();
  /** First trade shown, once the stall offers more than the well holds. */
  private int scrollOff;
  /** Which trade is picked. The preview follows this, not the cursor. */
  private int selected;
  /** Where the result slot is this frame, since that is what completes a trade. */
  private int resultLeft;
  private int resultTop;
  /** Whether the picked trade can actually be paid for out of the pack. */
  private boolean affordable;
  /** Cost items staged into the trade, as vanilla stages a payment. */
  private int staged;
  /** Every trade on the stall this frame, so a click can name the one picked. */
  private List<Deal> allDeals = new ArrayList<>();
  /** Cost items shown as moved into the trade, so the pack reads as vanilla's does. */
  private java.util.Map<Integer, Integer> reserved = new java.util.HashMap<>();
  /** Lines the conversation is scrolled back from its newest. */
  private int chatScrollUp;
  private int chatOverflow;
  /** 0 while there is nothing to send, easing to 1 as the arrow arrives. */
  private float sendReveal;
  /** Health both sides had last tick; NaN until the first tick has seen them. */
  private float lastPlayerHealth = Float.NaN;
  private float lastPersonHealth = Float.NaN;
  /** Set once the villager has been seen alive, so absence before that is not death. */
  private boolean sawPerson;
  private int overflowRows;

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

  private void centred(GuiGraphics graphics, String text, int centreX, int y) {
    graphics.drawString(this.font, text, centreX - this.font.width(text) / 2, y, TEXT_LABEL, false);
  }

  private void renderTrade(GuiGraphics graphics, int mouseX, int mouseY) {
    rowHits.clear();
    // Every coordinate here is the menu's own, so art and slots cannot drift:
    // the container draws a slot's CONTENTS at its position, and art placed by
    // any other rule ends up beside the items rather than under them.
    int head = com.quzzar.villagelife.menu.MarketMenu.HEAD;
    int listLeft = panelLeft + 8;
    int listTop = panelTop + head;
    int listBottom = listTop + LIST_ROWS * LIST_ROW;
    int rightLeft = panelLeft + com.quzzar.villagelife.menu.MarketMenu.PACK_X;
    int rightWidth = 9 * 18;

    if (offers == null) {
      graphics.drawString(this.font, "Opening the stall...", listLeft, listTop, TEXT_PENDING, false);
      return;
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
    allDeals = deals;

    centred(graphics, "Trades", listLeft + WELL_WIDTH / 2, panelTop + head - 12);
    if (!offers.villageName().isBlank()) {
      centred(graphics, offers.villageName() + " Market", rightLeft + rightWidth / 2,
          panelTop + head - 12);
    }

    slot(graphics, listLeft, listTop - 2, listLeft + WELL_WIDTH, listBottom + 2);
    int trackLeft = listLeft + 2 + LIST_WIDTH + 1;
    slot(graphics, trackLeft, listTop, trackLeft + 8, listBottom);

    int overflow = Math.max(0, deals.size() - LIST_ROWS);
    overflowRows = overflow;
    scrollOff = Math.min(scrollOff, overflow);
    int visible = Math.min(deals.size(), LIST_ROWS);
    shown = new ArrayList<>();
    for (int i = 0; i < visible; i++) {
      Deal deal = deals.get(i + scrollOff);
      shown.add(deal);
      drawDeal(graphics, deal, listLeft + 2, listTop + i * LIST_ROW, mouseX, mouseY,
          i + scrollOff == selected);
    }
    int trackTop = listTop;
    int trackRun = listBottom - trackTop - 27;
    int handle = overflow == 0 ? 0 : Math.round((float) scrollOff / overflow * trackRun);
    graphics.blitSprite(overflow == 0 ? SCROLLER_DISABLED : SCROLLER,
        trackLeft + 1, trackTop + handle, 6, 27);

    slotArt(graphics, rightLeft + 28, panelTop + head + 8);
    slotArt(graphics, rightLeft + 54, panelTop + head + 8);
    bigArrow(graphics, rightLeft + 80, panelTop + head + 10, offers.blocked());
    slotArt(graphics, rightLeft + 112, panelTop + head + 8);

    graphics.drawString(this.font, "Inventory", rightLeft, panelTop + head + 34, TEXT_LABEL, false);
    drawInventory(graphics, rightLeft, panelTop + head + 46, mouseX, mouseY);
  }

  /** One trade: what you hand over, what you get back. */
  private record Deal(ItemStack from, ItemStack into, String itemId, boolean playerBuys) {
  }

  private void drawDeal(GuiGraphics graphics, Deal deal, int left, int top, int mouseX, int mouseY,
      boolean chosen) {
    boolean hovered = mouseX >= left && mouseX < left + LIST_WIDTH
        && mouseY >= top && mouseY < top + LIST_ROW;
    graphics.blitSprite(hovered ? BUTTON_HOVER : BUTTON, left, top, LIST_WIDTH, LIST_ROW);

    int itemY = top + 2;
    graphics.renderItem(deal.from(), left + 5, itemY);
    graphics.renderItemDecorations(this.font, deal.from(), left + 5, itemY);
    // Struck out while the market cannot trade at all. Individual trades are
    // never blocked: a village that runs low just prices the item up until it
    // stops listing it, which the numbers say on their own.
    graphics.blitSprite(TRADE_ARROW, left + 55, itemY + 3, 10, 9);
    if (offers != null && offers.blocked()) {
      cross(graphics, left + 56, itemY + 3, 7);
    }
    if (chosen) {
      // The picked row stays picked, the way a selected trade does in vanilla.
      graphics.renderOutline(left, top, LIST_WIDTH, LIST_ROW, DEAL_EDGE_HOVER);
    }
    graphics.renderItem(deal.into(), left + 68, itemY);
    graphics.renderItemDecorations(this.font, deal.into(), left + 68, itemY);
    rowHits.add(new Row(top, left, left + LIST_WIDTH, deal.itemId(), deal.playerBuys()));
  }

  /** Marks pack slots as spent on the held trade, nearest slot first. */
  private void reserve(Item item, int count) {
    var player = Minecraft.getInstance().player;
    int left = count;
    for (int i = 0; i < 36 && left > 0; i++) {
      ItemStack stack = player != null ? player.getInventory().getItem(i)
          : previewInventory.getOrDefault(i, ItemStack.EMPTY);
      if (stack.isEmpty() || stack.getItem() != item) {
        continue;
      }
      int take = Math.min(left, stack.getCount());
      reserved.put(i, take);
      left -= take;
    }
  }

  /** The UI click every vanilla button makes; ours are drawn, so we play it. */
  private static void clickSound() {
    Minecraft.getInstance().getSoundManager().play(
        net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
  }

  /** How many of an item the pack holds, which is what a trade is judged against. */
  private int countHeld(Item item) {
    var player = Minecraft.getInstance().player;
    if (player == null) {
      int total = 0;
      for (ItemStack stack : previewInventory.values()) {
        if (stack.getItem() == item) {
          total += stack.getCount();
        }
      }
      return total;
    }
    int total = 0;
    for (int i = 0; i < 36; i++) {
      ItemStack stack = player.getInventory().getItem(i);
      if (stack.getItem() == item) {
        total += stack.getCount();
      }
    }
    return total;
  }

  /** The player's own pack, so a trade can be judged without closing the screen. */
  private void drawInventory(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
    // Slot backgrounds only. The container renders what is IN them, and drawing
    // the contents here as well would paint every stack twice, from two sources
    // that disagree the moment anything moves.
    for (int row = 0; row < 3; row++) {
      for (int column = 0; column < 9; column++) {
        slotArt(graphics, left + column * 18, top + row * 18);
      }
    }
    // The hotbar sits where the menu put it, not at an offset of our own.
    int hotbarY = panelTop + com.quzzar.villagelife.menu.MarketMenu.HEAD + 104;
    for (int column = 0; column < 9; column++) {
      slotArt(graphics, left + column * 18, hotbarY);
    }
  }


  /** The sunken box behind one 16x16 slot, drawn where the menu put it. */
  private static void slotArt(GuiGraphics graphics, int itemX, int itemY) {
    slot(graphics, itemX - 1, itemY - 1, itemX + 17, itemY + 17);
  }

  /**
   * The long arrow between cost and result. It is baked into villager.png
   * rather than published as a sprite, so it is drawn: 22 wide, a three pixel
   * shaft, in the mid grey the texture uses.
   */
  private static void bigArrow(GuiGraphics graphics, int x, int y, boolean blocked) {
    graphics.blit(VILLAGER_GUI, x, y, 0, 186.0F, 38.0F, ARROW_W, ARROW_H, 512, 256);
    if (blocked) {
      cross(graphics, x + 4, y + 1, ARROW_H - 2);
    }
  }

  /**
   * A cross drawn over an arrow that cannot be taken. The arrow keeps its own
   * colour: recolouring it red read as an error state rather than as a closed
   * shop, and the X alone says the same thing more quietly.
   */
  private static void cross(GuiGraphics graphics, int x, int y, int size) {
    for (int i = 0; i < size; i++) {
      graphics.fill(x + i, y + i, x + i + 2, y + i + 2, CROSS);
      graphics.fill(x + i, y + size - i, x + i + 2, y + size - i + 2, CROSS);
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
  public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
    if (tab == Tab.TRADE && overflowRows > 0) {
      scrollOff = Math.max(0, Math.min(overflowRows, scrollOff - (int) Math.signum(deltaY)));
      return true;
    }
    if (tab == Tab.CHAT && chatOverflow > 0) {
      chatScrollUp = Math.max(0, Math.min(chatOverflow, chatScrollUp + (int) Math.signum(deltaY)));
      return true;
    }
    return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
  }

  @Override
  public boolean mouseClicked(double mouseX, double mouseY, int button) {
    if (tab == Tab.TRADE && offers != null) {
      // Picking a trade stages the payment out of your pack, the way vanilla's
      // tryMoveItems does: as much of the cost item as it can find, up to a
      // stack, not merely the amount this one trade needs.
      for (int i = 0; i < rowHits.size(); i++) {
        Row row = rowHits.get(i);
        if (mouseY >= row.y() && mouseY < row.y() + LIST_ROW
            && mouseX >= row.left() && mouseX < row.right()) {
          clickSound();
          selected = i + scrollOff;
          toServer(new com.quzzar.villagelife.networking.SelectTradePacket(selected));
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
      // Through applyTabVisibility, never by poking one widget: setting
      // input.visible here left the send button hidden from the moment the
      // screen opened on Trade, and no amount of typing brought it back.
      applyTabVisibility();
      if (target == Tab.TRADE) {
        toServer(
            com.quzzar.villagelife.networking.MarketActionPacket.refresh(entityId));
      }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      boolean active = tab == target;
      if (active) {
        // A raised stub that breaks the rule beneath it, tinted so the chosen
        // tab is not the same grey as everything else on the window.
        panel(graphics, getX() - 2, getY() - 3, getX() + width + 2, getY() + height + 2);
      }
      int colour = active ? ICON_HOVER : (isHovered() ? ICON_HOVER : ICON_IDLE);
      // drawCenteredString always shadows; on a light panel that reads as
      // grime around every glyph.
      Font font = PersonChatScreen.this.font;
      graphics.drawString(font, label, getX() + (width - font.width(label)) / 2,
          getY() + 3, colour, false);

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
        // Always a button, so the control is where you expect it; disabled art
        // until there is something to send, then it lifts into a live one.
        boolean ready = sendReveal > 0.5F;
        if (ready) {
          // Raised out of the panel once there is something to send.
          panel(graphics, getX(), getY(), getX() + width, getY() + height);
        } else {
          // Sunken, like the text field beside it: dormant reads as part of the
          // input rather than as a button someone forgot to switch on.
          slot(graphics, getX(), getY(), getX() + width, getY() + height);
        }
        // No lift. It was meant to make the arrow rise as it woke up, but the
        // offset was 2 at rest when dormant and 0 when live, so the two resting
        // states sat at different heights: an animation whose endpoints do not
        // line up reads as a bug, because it is one. The arrow is centred in
        // both states and the waking is carried by the glyph and the face.
        int glyph = SEND_ON;
        // 14 wide by 8 tall, which divides exactly into a 20x20 button. The
        // previous form let its height fall out of the stroke loop, so it sat
        // a pixel low and no amount of eyeballing the loop would have said why.
        int reach = 7;
        int thick = 2;
        int cx = getX() + width / 2;
        int y0 = getY() + (height - (reach + 1)) / 2;
        for (int i = 0; i < reach; i++) {
          graphics.fill(cx - i - 1, y0 + i, cx - i - 1 + thick, y0 + i + thick, glyph);
          graphics.fill(cx + i - 1, y0 + i, cx + i - 1 + thick, y0 + i + thick, glyph);
        }
      }
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
      defaultButtonNarrationText(output);
    }
  }
}
