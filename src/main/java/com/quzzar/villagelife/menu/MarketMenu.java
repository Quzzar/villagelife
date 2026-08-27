package com.quzzar.villagelife.menu;

import com.quzzar.villagelife.economy.MarketOffers;
import com.quzzar.villagelife.economy.Trade;
import com.quzzar.villagelife.economy.Treasury;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The market as a real container, so its slots behave like slots.
 *
 * The screen used to DRAW a picture of the player's pack and could not move a
 * single item in it, because nothing was ever a {@link Slot}. Vanilla's trade
 * screen is an AbstractContainerScreen over MerchantMenu, and everything a
 * player expects of it (dragging, shift-clicking, the server refusing a move it
 * does not like) comes from that and not from the rendering. Matching the look
 * without matching the structure produced something that looked like a trade
 * screen and behaved like a poster of one.
 *
 * Layout mirrors MerchantMenu deliberately: two cost slots, a result, then the
 * pack, so the vanilla geometry and the vanilla quick-move ranges both apply.
 */
public class MarketMenu extends AbstractContainerMenu {

  /** The two cost slots and the result, exactly as MerchantMenu numbers them. */
  public static final int COST_A = 0;
  public static final int COST_B = 1;
  public static final int RESULT = 2;
  /** Height of the strip above the container: the villager's name and tabs. */
  public static final int HEAD = 50;
  /** Left edge of the pack, and of the right-hand column generally. */
  public static final int PACK_X = 116;
  private static final int TRADE_SLOTS = 3;
  private static final int PACK_START = TRADE_SLOTS;
  private static final int PACK_END = PACK_START + 36;

  private final Container trade = new SimpleContainer(TRADE_SLOTS);
  private final Player player;
  private final int merchantId;
  private final String headerName;
  private final String headerDetail;
  private final boolean canTrade;

  /** Which trade is staged, as an index into the stall's current offers. */
  private int selected = -1;

  public MarketMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
    this(containerId, inventory, buffer.readVarInt(), buffer.readUtf(), buffer.readUtf(), buffer.readBoolean());
  }

  public MarketMenu(int containerId, Inventory inventory, int merchantId, String headerName,
      String headerDetail, boolean canTrade) {
    super(com.quzzar.villagelife.other.VillagelifeMenus.MARKET.get(), containerId);
    this.player = inventory.player;
    this.merchantId = merchantId;
    this.headerName = headerName;
    this.headerDetail = headerDetail;
    this.canTrade = canTrade;

    // Vanilla's own coordinates, pushed down by the strip that carries the
    // villager's name and the tabs. A merchant screen has no such strip, so the
    // offset is ours; everything below it keeps MerchantMenu's spacing exactly.
    addSlot(new Slot(trade, COST_A, PACK_X + 28, HEAD + 30));
    addSlot(new Slot(trade, COST_B, PACK_X + 54, HEAD + 30));
    addSlot(new MarketResultSlot(this, trade, RESULT, PACK_X + 112, HEAD + 30));

    for (int row = 0; row < 3; row++) {
      for (int column = 0; column < 9; column++) {
        addSlot(new Slot(inventory, column + row * 9 + 9, PACK_X + column * 18, HEAD + 70 + row * 18));
      }
    }
    for (int column = 0; column < 9; column++) {
      addSlot(new Slot(inventory, column, PACK_X + column * 18, HEAD + 128));
    }

    addSlotListener(new ContainerListener() {
      @Override
      public void slotChanged(AbstractContainerMenu menu, int slot, ItemStack stack) {
        if (slot == COST_A || slot == COST_B) {
          updateResult();
        }
      }

      @Override
      public void dataChanged(AbstractContainerMenu menu, int index, int value) {
      }
    });
  }

  public int merchantId() {
    return merchantId;
  }

  public String headerName() {
    return headerName;
  }

  public String headerDetail() {
    return headerDetail;
  }

  public boolean canTrade() {
    return canTrade;
  }

  public int selected() {
    return selected;
  }

  /**
   * Picks a trade and stages its payment out of the pack, the way
   * MerchantMenu.tryMoveItems does: as much of the cost item as can be found,
   * up to a stack, rather than only what this one trade needs.
   */
  public void select(int index) {
    this.selected = index;
    Deal offer = offerAt(index);
    if (offer == null) {
      return;
    }
    // Send the staged payment home first, so picking a second trade does not
    // strand the first one's stake in a slot nobody is looking at.
    returnStaged();
    ItemStack cost = costOf(offer);
    if (!cost.isEmpty()) {
      moveFromPackToCost(cost);
    }
    updateResult();
  }

  /**
   * One trade with its direction. MarketOffers.Offer does not carry direction,
   * because which list it came from IS the direction: the selling list is the
   * village parting with goods, the wanted list is the village paying for them.
   */
  private record Deal(MarketOffers.Offer offer, boolean playerBuys) {
  }

  /** What the player hands over: emeralds to buy, goods to sell. */
  private ItemStack costOf(Deal deal) {
    return deal.playerBuys()
        ? new ItemStack(Treasury.CURRENCY, deal.offer().emeralds())
        : new ItemStack(deal.offer().item(), deal.offer().itemCount());
  }

  /** What the player receives. */
  private ItemStack resultOf(Deal deal) {
    return deal.playerBuys()
        ? new ItemStack(deal.offer().item(), deal.offer().itemCount())
        : new ItemStack(Treasury.CURRENCY, deal.offer().emeralds());
  }

  private void moveFromPackToCost(ItemStack cost) {
    for (int i = PACK_START; i < PACK_END; i++) {
      ItemStack inPack = slots.get(i).getItem();
      if (inPack.isEmpty() || inPack.getItem() != cost.getItem()) {
        continue;
      }
      ItemStack staged = trade.getItem(COST_A);
      int max = inPack.getMaxStackSize();
      int room = max - staged.getCount();
      if (room <= 0) {
        break;
      }
      int moved = Math.min(room, inPack.getCount());
      trade.setItem(COST_A, inPack.copyWithCount(staged.getCount() + moved));
      inPack.shrink(moved);
      slots.get(i).setChanged();
      if (trade.getItem(COST_A).getCount() >= max) {
        break;
      }
    }
  }

  /**
   * The result appears only when the staged payment covers the cost, which is
   * the whole of a trade screen's feedback. Nothing is ever said in words.
   */
  public void updateResult() {
    Deal offer = offerAt(selected);
    if (offer == null) {
      trade.setItem(RESULT, ItemStack.EMPTY);
      return;
    }
    ItemStack cost = costOf(offer);
    ItemStack staged = trade.getItem(COST_A);
    boolean paid = !staged.isEmpty() && staged.getItem() == cost.getItem()
        && staged.getCount() >= cost.getCount();
    trade.setItem(RESULT, paid ? resultOf(offer) : ItemStack.EMPTY);
  }

  /** Runs the village side of the trade and spends the staged payment. */
  void completeTrade() {
    Deal offer = offerAt(selected);
    if (offer == null || !(player instanceof ServerPlayer serverPlayer)) {
      return;
    }
    Village village = villageOf(serverPlayer);
    if (village == null || !(serverPlayer.level() instanceof ServerLevel level)) {
      return;
    }
    ItemStack cost = costOf(offer);
    ItemStack staged = trade.getItem(COST_A);
    if (staged.getCount() < cost.getCount()) {
      return;
    }
    // The staged stake is already out of the pack, so put it back before the
    // trade runs: Trade takes what it needs from the pack itself, and paying
    // twice out of two places is how a container duplicates or eats items.
    staged.shrink(cost.getCount());
    trade.setItem(COST_A, staged.isEmpty() ? ItemStack.EMPTY : staged);
    giveBack(cost);

    if (offer.playerBuys()) {
      Trade.villageSells(village, level, serverPlayer, offer.offer().item(), offer.offer().itemCount());
    } else {
      Trade.villageBuys(village, level, serverPlayer, offer.offer().item(), offer.offer().itemCount());
    }
    updateResult();
  }

  private void giveBack(ItemStack stack) {
    ItemStack copy = stack.copy();
    if (!player.getInventory().add(copy) && !copy.isEmpty()) {
      player.drop(copy, false);
    }
  }

  private Deal offerAt(int index) {
    if (index < 0 || !(player instanceof ServerPlayer serverPlayer)
        || !(serverPlayer.level() instanceof ServerLevel level)) {
      return null;
    }
    Village village = villageOf(serverPlayer);
    if (village == null) {
      return null;
    }
    java.util.List<MarketOffers.Offer> selling = MarketOffers.selling(village, level);
    if (index < selling.size()) {
      return new Deal(selling.get(index), true);
    }
    java.util.List<MarketOffers.Offer> wanted = MarketOffers.wanted(village, level);
    int into = index - selling.size();
    return into < wanted.size() ? new Deal(wanted.get(into), false) : null;
  }

  private Village villageOf(ServerPlayer serverPlayer) {
    if (!(serverPlayer.level() instanceof ServerLevel level)) {
      return null;
    }
    return level.getEntity(merchantId) instanceof RealPerson person ? person.getVillage() : null;
  }

  /** Anything left staged goes home, never into the void. */
  private void returnStaged() {
    for (int i = 0; i < TRADE_SLOTS - 1; i++) {
      ItemStack staged = trade.getItem(i);
      if (!staged.isEmpty()) {
        giveBack(staged);
        trade.setItem(i, ItemStack.EMPTY);
      }
    }
  }

  @Override
  public void removed(Player closing) {
    super.removed(closing);
    // Closing the screen must not swallow a staged payment.
    if (!closing.level().isClientSide()) {
      returnStaged();
    }
  }

  @Override
  public boolean stillValid(Player who) {
    return who.level().getEntity(merchantId) instanceof RealPerson person
        && person.isAlive() && who.distanceToSqr(person) < 64.0D;
  }

  @Override
  public ItemStack quickMoveStack(Player who, int index) {
    ItemStack moved = ItemStack.EMPTY;
    Slot slot = slots.get(index);
    if (slot == null || !slot.hasItem()) {
      return moved;
    }
    ItemStack stack = slot.getItem();
    moved = stack.copy();
    if (index == RESULT) {
      if (!moveItemStackTo(stack, PACK_START, PACK_END, true)) {
        return ItemStack.EMPTY;
      }
      slot.onQuickCraft(stack, moved);
    } else if (index == COST_A || index == COST_B) {
      if (!moveItemStackTo(stack, PACK_START, PACK_END, false)) {
        return ItemStack.EMPTY;
      }
    } else if (!moveItemStackTo(stack, COST_A, COST_B + 1, false)) {
      return ItemStack.EMPTY;
    }
    if (stack.isEmpty()) {
      slot.setByPlayer(ItemStack.EMPTY);
    } else {
      slot.setChanged();
    }
    return moved;
  }
}
