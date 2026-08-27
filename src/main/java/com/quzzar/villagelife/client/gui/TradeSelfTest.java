package com.quzzar.villagelife.client.gui;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.menu.MarketMenu;
import com.quzzar.villagelife.networking.SelectTradePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drives real trades against a real server and checks what they cost.
 *
 * Every trading bug this session was found by Aaron playing, reporting, and
 * being handed the game back to try again. A trade is a sequence of container
 * clicks and they can be driven from here, so the loop stops. It exercises
 * three things the mouse does and the eye cannot verify at a glance: BUYING
 * (emeralds out, goods in), SELLING (goods out, emeralds in), and DRAGGING a
 * stack between inventory slots, which is the whole reason the screen became a
 * real container. Everything is asserted against the PACK and the container's
 * own slots, not against the drawing.
 *
 * <pre>
 * ./gradlew runClientJoinLocal -Puitest=trade
 * </pre>
 *
 * The runner joins as Dev, then over rcon puts Dev at a stall, gives emeralds
 * and a spread of the goods the stall wants, and seeds the market chest so it
 * has something to sell. Each check logs PASS or FAIL; a run that asserts
 * nothing is itself a FAIL.
 */
@EventBusSubscriber(modid = Villagelife.MODID, value = Dist.CLIENT, bus = Bus.GAME)
public final class TradeSelfTest {

    private static final String MODE = normalise(System.getProperty("villagelife.uitest"));

    private static String normalise(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    private enum Step {
        WAIT_FOR_WORLD, OPEN,
        BUY_SELECT, BUY_STAGE, BUY_TAKE,
        SELL_SELECT, SELL_STAGE, SELL_TAKE,
        DRAG_PICKUP, DRAG_PLACE,
        DONE
    }

    private static Step step = Step.WAIT_FOR_WORLD;
    private static int ticks;
    private static int waited;
    private static int checksRun;
    private static boolean failed;

    // The trade currently under assertion.
    private static int perTradeGoods;
    private static int perTradeEmeralds;
    private static Item goodsItem = Items.AIR;
    private static int emeraldsBefore;
    private static int goodsBefore;

    // The drag currently under assertion.
    private static int dragFrom;
    private static int dragTo;
    private static int buyRow;
    private static int sellRow;
    private static int lastEmeralds = -1;
    private static ItemStack dragStack = ItemStack.EMPTY;

    private TradeSelfTest() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (MODE == null || step == Step.DONE) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        if (++ticks % 20 != 0) {
            return; // once a second: the server needs time between steps
        }

        switch (step) {
            case WAIT_FOR_WORLD -> {
                int now = count(Items.EMERALD);
                if (now > 0 && now == lastEmeralds) {
                    log("in world, settled at " + now + " emeralds");
                    step = Step.OPEN;
                }
                lastEmeralds = now;
            }
            case OPEN -> {
                if (client.player.containerMenu instanceof MarketMenu menu) {
                    if (!menu.canTrade()) {
                        log("opened " + menu.headerName() + " but they cannot trade; closing");
                        client.player.closeContainer();
                        return;
                    }
                    log("merchant screen open: " + menu.headerName());
                    step = Step.BUY_SELECT;
                } else {
                    interactWithNearestMerchant(client);
                }
            }

            // ---- buy: emeralds out, goods in --------------------------------
            case BUY_SELECT -> {
                int row = PersonChatScreen.firstBuyIndex();
                if (row < 0) {
                    if (++waited < 15) {
                        log("waiting for the stall: " + PersonChatScreen.previewState());
                        return;
                    }
                    check("stall offered at least one buy row", false);
                    return;
                }
                buyRow = row;
                emeraldsBefore = count(Items.EMERALD);
                PacketDistributor.sendToServer(new SelectTradePacket(row));
                log("selected buy row " + row + ", emeralds before = " + emeraldsBefore);
                step = Step.BUY_STAGE;
            }
            case BUY_STAGE -> {
                MarketMenu menu = menu();
                ItemStack staged = menu.getSlot(MarketMenu.COST_A).getItem();
                ItemStack result = menu.getSlot(MarketMenu.RESULT).getItem();
                log("buy staged=" + describe(staged) + " result=" + describe(result));
                check("buy: payment staged out of the pack",
                        !staged.isEmpty() && count(Items.EMERALD) < emeraldsBefore);
                check("buy: result offered once payment covers it", !result.isEmpty());
                if (result.isEmpty()) {
                    step = Step.SELL_SELECT;
                    return;
                }
                goodsItem = result.getItem();
                perTradeGoods = PersonChatScreen.dealGoods(buyRow);
                perTradeEmeralds = PersonChatScreen.dealEmeralds(buyRow);
                goodsBefore = count(goodsItem);
                // Measured HERE (total owned, pack + staged) rather than before
                // staging, so an rcon give that lands late cannot make a buy
                // look like it charged nothing.
                emeraldsBefore = held(Items.EMERALD);
                click(MarketMenu.RESULT, ClickType.QUICK_MOVE); // shift-take
                step = Step.BUY_TAKE;
            }
            case BUY_TAKE -> {
                int emeraldsAfter = held(Items.EMERALD);
                int goodsAfter = count(goodsItem);
                int spent = emeraldsBefore - emeraldsAfter;
                int gained = goodsAfter - goodsBefore;
                log("after buy: emeralds " + emeraldsBefore + "->" + emeraldsAfter
                        + ", goods " + goodsBefore + "->" + goodsAfter);
                check("buy: goods delivered", gained > 0);
                check("buy: charged for them", spent > 0);
                check("buy: rate " + perTradeEmeralds + " for " + perTradeGoods
                        + " (spent " + spent + " got " + gained + ")",
                        rateHolds(spent, gained));
                step = Step.SELL_SELECT;
            }

            // ---- sell: goods out, emeralds in -------------------------------
            case SELL_SELECT -> {
                int row = -1;
                for (int i = 0; i < PersonChatScreen.dealCount(); i++) {
                    if (PersonChatScreen.dealIsSell(i)) {
                        Item held = itemOf(PersonChatScreen.dealItemId(i));
                        if (held != null && count(held) > 0) {
                            row = i;
                            sellRow = i;
                            goodsItem = held;
                            break;
                        }
                    }
                }
                if (row < 0) {
                    check("stall wanted something the player is holding", false);
                    step = Step.DRAG_PICKUP;
                    return;
                }
                goodsBefore = count(goodsItem);
                PacketDistributor.sendToServer(new SelectTradePacket(row));
                log("selected sell row " + row + " (" + goodsItem.getDescriptionId()
                        + "), holding " + goodsBefore);
                step = Step.SELL_STAGE;
            }
            case SELL_STAGE -> {
                MarketMenu menu = menu();
                ItemStack staged = menu.getSlot(MarketMenu.COST_A).getItem();
                ItemStack result = menu.getSlot(MarketMenu.RESULT).getItem();
                log("sell staged=" + describe(staged) + " result=" + describe(result));
                check("sell: goods staged out of the pack",
                        !staged.isEmpty() && count(goodsItem) < goodsBefore);
                check("sell: emeralds offered once goods cover it", !result.isEmpty());
                if (result.isEmpty()) {
                    step = Step.DRAG_PICKUP;
                    return;
                }
                perTradeGoods = PersonChatScreen.dealGoods(sellRow);
                perTradeEmeralds = PersonChatScreen.dealEmeralds(sellRow);
                // Baseline captured HERE, not at select: selecting a new trade
                // returns any payment staged for the previous one to the pack,
                // and counting emeralds before that settles credited the sell
                // with a leftover it did not earn.
                emeraldsBefore = count(Items.EMERALD);
                click(MarketMenu.RESULT, ClickType.QUICK_MOVE); // shift-take
                step = Step.SELL_TAKE;
            }
            case SELL_TAKE -> {
                int goodsAfter = held(goodsItem);
                int emeraldsAfter = count(Items.EMERALD);
                int spent = goodsBefore - goodsAfter;
                int gained = emeraldsAfter - emeraldsBefore;
                log("after sell: goods " + goodsBefore + "->" + goodsAfter
                        + ", emeralds " + emeraldsBefore + "->" + emeraldsAfter);
                check("sell: emeralds received", gained > 0);
                check("sell: goods taken", spent > 0);
                check("sell: rate " + perTradeGoods + " for " + perTradeEmeralds
                        + " (gave " + spent + " got " + gained + ")",
                        rateHolds(gained, spent));
                step = Step.DRAG_PICKUP;
            }

            // ---- drag a stack between inventory slots ------------------------
            case DRAG_PICKUP -> {
                MarketMenu menu = menu();
                dragFrom = -1;
                dragTo = -1;
                for (int i = MarketMenu.COST_A + 3; i < MarketMenu.COST_A + 3 + 36; i++) {
                    if (dragFrom < 0 && menu.getSlot(i).hasItem()) {
                        dragFrom = i;
                    } else if (dragTo < 0 && !menu.getSlot(i).hasItem()) {
                        dragTo = i;
                    }
                }
                if (dragFrom < 0 || dragTo < 0) {
                    check("inventory had a filled slot and an empty slot to drag between", false);
                    step = Step.DONE;
                    finish();
                    return;
                }
                dragStack = menu.getSlot(dragFrom).getItem().copy();
                log("dragging " + describe(dragStack) + " from slot " + dragFrom + " to " + dragTo);
                click(dragFrom, ClickType.PICKUP); // pick the stack up onto the cursor
                step = Step.DRAG_PLACE;
            }
            case DRAG_PLACE -> {
                MarketMenu menu = menu();
                click(dragTo, ClickType.PICKUP); // drop it into the empty slot
                ItemStack landed = menu.getSlot(dragTo).getItem();
                check("drag: item landed in the destination slot",
                        landed.getItem() == dragStack.getItem()
                                && landed.getCount() == dragStack.getCount());
                check("drag: source slot is now empty", !menu.getSlot(dragFrom).hasItem());
                step = Step.DONE;
                finish();
            }
            default -> {
            }
        }
    }

    /** Cross-multiplied so the rate check never depends on integer division. */
    private static boolean rateHolds(int emeraldsMoved, int goodsMoved) {
        return perTradeGoods > 0 && perTradeEmeralds > 0
                && emeraldsMoved * perTradeGoods == goodsMoved * perTradeEmeralds;
    }

    private static MarketMenu menu() {
        return (MarketMenu) Minecraft.getInstance().player.containerMenu;
    }

    private static void interactWithNearestMerchant(Minecraft client) {
        client.level.getEntities(client.player,
                client.player.getBoundingBox().inflate(8.0D),
                e -> e instanceof com.quzzar.villagelife.entities.RealPerson person
                        && person.getOccupation() == com.quzzar.villagelife.village.Occupation.MERCHANT)
                .stream().findFirst()
                .ifPresentOrElse(person -> {
                    log("right-clicking merchant " + person.getName().getString());
                    client.gameMode.interact(client.player, person,
                            net.minecraft.world.InteractionHand.MAIN_HAND);
                }, () -> log("no merchant within 8 blocks yet"));
    }

    private static void click(int slot, ClickType type) {
        Minecraft client = Minecraft.getInstance();
        client.gameMode.handleInventoryMouseClick(
                client.player.containerMenu.containerId, slot, 0, type, client.player);
    }

    /** In the pack only. */
    private static int count(Item item) {
        int total = 0;
        var inventory = Minecraft.getInstance().player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inventory.getItem(i).getItem() == item) {
                total += inventory.getItem(i).getCount();
            }
        }
        return total;
    }

    /** In the pack plus anything of that item staged in the cost slot. */
    private static int held(Item item) {
        int total = count(item);
        ItemStack staged = menu().getSlot(MarketMenu.COST_A).getItem();
        if (staged.getItem() == item) {
            total += staged.getCount();
        }
        return total;
    }

    private static Item itemOf(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? null : BuiltInRegistries.ITEM.get(location);
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty() ? "empty" : stack.getCount() + "x" + stack.getItem().getDescriptionId();
    }

    private static void check(String what, boolean ok) {
        checksRun++;
        if (!ok) {
            failed = true;
        }
        Villagelife.LOGGER.info("TRADE TEST {} : {}", ok ? "PASS" : "FAIL", what);
    }

    private static void log(String message) {
        Villagelife.LOGGER.info("TRADE TEST .... : {}", message);
    }

    private static void finish() {
        // A run that asserted nothing is not a pass: the first version reported
        // "all checks passed" after running no checks, which is the worst green.
        if (checksRun == 0) {
            failed = true;
            Villagelife.LOGGER.info("TRADE TEST FAIL : no checks ran at all");
        }
        Villagelife.LOGGER.info("TRADE TEST DONE : {}",
                failed ? "FAILURES ABOVE" : checksRun + " checks passed");
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
    }
}
