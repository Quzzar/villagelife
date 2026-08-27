package com.quzzar.villagelife.client.gui;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.menu.MarketMenu;
import com.quzzar.villagelife.networking.SelectTradePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drives a real trade against a real server and checks what it cost.
 *
 * Every trading bug so far has been found by Aaron playing, reported, guessed
 * at, fixed and handed back for him to try again. That loop is expensive and it
 * is avoidable: a trade is a sequence of container clicks, and container clicks
 * can be sent from here exactly as the mouse sends them, including the
 * shift-click that was handing goods over for nothing.
 *
 * Run with:
 *
 * <pre>
 * ./gradlew runClientJoinLocal -Puitest=trade
 * </pre>
 *
 * It joins, opens a merchant, stages a payment, takes the result both ways, and
 * logs PASS or FAIL with the actual counts. It asserts against the PACK, not
 * against the screen, because the screen was never the thing that was wrong.
 */
@EventBusSubscriber(modid = Villagelife.MODID, value = Dist.CLIENT)
public final class TradeSelfTest {

    private static final String MODE = normalise(System.getProperty("villagelife.uitest"));

    private static String normalise(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    private enum Step {
        WAIT_FOR_WORLD, OPEN, SELECT, TAKE_NORMAL, TAKE_SHIFT, DONE
    }

    private static Step step = Step.WAIT_FOR_WORLD;
    private static int ticks;
    private static int emeraldsBefore;
    private static int goodsBefore;
    private static ItemStack goods = ItemStack.EMPTY;
    private static boolean failed;
    private static int waited;
    private static int checksRun;

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
            return; // act once a second; the server needs time between steps
        }

        switch (step) {
            case WAIT_FOR_WORLD -> {
                // The runner tp's us to the stall and hands us emeralds first.
                if (count(Items.EMERALD) > 0) {
                    log("in world with " + count(Items.EMERALD) + " emeralds");
                    step = Step.OPEN;
                }
            }
            case OPEN -> {
                if (client.player.containerMenu instanceof MarketMenu menu) {
                    if (!menu.canTrade()) {
                        log("opened " + menu.headerName() + " but they have nothing to sell; closing");
                        client.player.closeContainer();
                        return;
                    }
                    log("merchant screen open: " + menu.headerName());
                    step = Step.SELECT;
                } else {
                    log("menu is " + client.player.containerMenu.getClass().getSimpleName()
                            + "; looking for a merchant");
                    interactWithNearestVillager(client);
                }
            }
            case SELECT -> {
                // A row where the player PAYS emeralds, so the thing being spent
                // and the thing being received are never the same item. Row 0
                // happened to be a SELL on the second run and the assertions
                // then counted emeralds as both payment and goods.
                // The stall arrives in its own packet after the screen opens,
                // so an immediate look finds nothing. Wait for it rather than
                // concluding the stall is empty.
                int row = PersonChatScreen.firstBuyIndex();
                if (row < 0) {
                    if (++waited < 15) {
                        log("waiting for the stall: " + PersonChatScreen.previewState());
                        return;
                    }
                    check("stall offered at least one buy row", false);
                    step = Step.DONE;
                    finish();
                    return;
                }
                emeraldsBefore = count(Items.EMERALD);
                PacketDistributor.sendToServer(new SelectTradePacket(row));
                log("selected buy row " + row + ", emeralds before = " + emeraldsBefore);
                step = Step.TAKE_NORMAL;
            }
            case TAKE_NORMAL -> {
                MarketMenu menu = (MarketMenu) client.player.containerMenu;
                ItemStack staged = menu.getSlot(MarketMenu.COST_A).getItem();
                ItemStack result = menu.getSlot(MarketMenu.RESULT).getItem();
                log("staged=" + describe(staged) + " result=" + describe(result));
                check("payment staged out of the pack",
                        !staged.isEmpty() && count(Items.EMERALD) < emeraldsBefore);
                check("result offered once payment covers it", !result.isEmpty());
                if (result.isEmpty()) {
                    step = Step.DONE;
                    finish();
                    return;
                }
                goods = result.copy();
                // Cost per unit = what the stall says, captured before trading.
                costPerUnit = stagedCostPerUnit(menu);
                goodsBefore = count(goods.getItem());
                emeraldsBefore = count(Items.EMERALD) + staged.getCount();
                // QUICK_MOVE is the shift-click that was giving goods away.
                click(client, MarketMenu.RESULT, ClickType.QUICK_MOVE);
                step = Step.TAKE_SHIFT;
            }
            case TAKE_SHIFT -> {
                int emeraldsNow = count(Items.EMERALD)
                        + ((MarketMenu) client.player.containerMenu).getSlot(MarketMenu.COST_A).getItem().getCount();
                int goodsNow = count(goods.getItem());
                log("after shift-take: emeralds " + emeraldsBefore + " -> " + emeraldsNow
                        + ", " + goods.getItem().getDescriptionId() + " " + goodsBefore + " -> " + goodsNow);
                int spent = emeraldsBefore - emeraldsNow;
                int gained = goodsNow - goodsBefore;
                check("shift-take delivered the goods", gained > 0);
                check("shift-take CHARGED for them", spent > 0);
                // The check that was missing: "it charged" and "it charged the
                // RIGHT amount" are different claims, and the first passed
                // while the stall was handing over twice the goods.
                int unit = costPerUnit;
                check("rate is " + unit + " per unit (spent " + spent + " for " + gained + ")",
                        unit > 0 && spent == gained * unit);
                step = Step.DONE;
                finish();
            }
            default -> {
            }
        }
    }

    private static int costPerUnit;

    private static int stagedCostPerUnit(MarketMenu menu) {
        return PersonChatScreen.selectedUnitPrice();
    }

    private static void interactWithNearestVillager(Minecraft client) {
        client.level.getEntities(client.player,
                client.player.getBoundingBox().inflate(8.0D),
                e -> e instanceof com.quzzar.villagelife.entities.RealPerson).stream()
                .findFirst()
                .ifPresentOrElse(person -> {
                    log("right-clicking " + person.getName().getString());
                    client.gameMode.interact(client.player, person,
                            net.minecraft.world.InteractionHand.MAIN_HAND);
                }, () -> log("no villager within 8 blocks yet"));
    }

    private static void click(Minecraft client, int slot, ClickType type) {
        client.gameMode.handleInventoryMouseClick(
                client.player.containerMenu.containerId, slot, 0, type, client.player);
    }

    private static int count(net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = Minecraft.getInstance().player.getInventory().getItem(i);
            if (stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
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
        // A run that asserted nothing is not a pass. The first version reported
        // "all checks passed" after doing no checks at all, which is the most
        // dangerous kind of green.
        if (checksRun == 0) {
            failed = true;
            Villagelife.LOGGER.info("TRADE TEST FAIL : no checks ran at all");
        }
        Villagelife.LOGGER.info("TRADE TEST DONE : {}",
                failed ? "FAILURES ABOVE" : checksRun + " checks passed");
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
    }
}
