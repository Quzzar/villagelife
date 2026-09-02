package com.quzzar.villagelife.client.gui;

import java.util.List;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.networking.MarketOffersPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Renders the villager screen and photographs it, so whoever built it can
 * actually look at it.
 *
 * Every version of this screen before it existed was written blind and handed
 * over unseen, and every one of them was wrong in ways one glance would have
 * caught: a panel stretched to near-fullscreen, columns marooned at opposite
 * edges, goods identified only by an icon. A UI you cannot see is a UI you are
 * guessing at.
 *
 * Run it with:
 *
 * <pre>
 * ./gradlew runClient -Dvillagelife.uipreview=trade
 * </pre>
 *
 * The client boots to the title screen, opens the named tab over sample data,
 * saves a PNG under {@code run/screenshots/}, and quits. No server, no world,
 * no merchant, and nobody asked to press a key. Sample data rather than live
 * data on purpose: it always contains the awkward cases (a long item name, a
 * two-digit stack, an empty column) instead of whatever a village happens to
 * hold today.
 */
@EventBusSubscriber(modid = Villagelife.MODID, value = Dist.CLIENT)
public final class UiPreview {

    /** Which tab to photograph, or null when not previewing. */
    private static final String MODE = normalise(System.getProperty("villagelife.uipreview"));

    private static String normalise(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    /** Long enough for fonts, item models and the panel to be ready. */
    private static final int SETTLE_TICKS = 40;

    private static int ticks = -1;
    private static boolean shot;
    private static boolean revealFired;
    /** Fire the reveal 26 ticks before the shot, so it is caught mid-type. */
    private static final int REVEAL_FIRE_TICK = 14;

    private UiPreview() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (MODE == null || shot) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        // A container screen needs an Inventory, which needs a Player, which
        // needs a world, so this harness cannot run at the title screen any
        // more. Worse for isolation, better for truth: it now exercises the
        // path the screen actually opens through.
        if (client.player == null || client.level == null || client.getOverlay() != null) {
            return;
        }
        if (ticks < 0) {
            Villagelife.LOGGER.info("UI preview: opening {} in world", MODE);
            openSample(client);
            ticks = 0;
            return;
        }
        // Joining a server can drop the pause menu over us the moment the
        // window loses focus, and the first run photographed exactly that.
        if (!(client.screen instanceof PersonChatScreen)) {
            openSample(client);
        }
        // A fresh reply reveals character by character, and the history path
        // shows it whole, so the only way to photograph it mid-reveal is to
        // fire one and shoot before it finishes. onReply at tick 14, shot at
        // 40: about 1.3s of reveal, well inside a long line.
        if ("reveal".equalsIgnoreCase(MODE) && ticks == REVEAL_FIRE_TICK && !revealFired) {
            revealFired = true;
            PersonChatScreen.onReply(0, "Aye, and the north road's been thick with bandits "
                    + "since the frost broke, so mind yourself past the old mill.", false);
        }
        if (++ticks < SETTLE_TICKS) {
            return;
        }
        shot = true;
        Screenshot.grab(client.gameDirectory, "ui-" + MODE + ".png",
                client.getMainRenderTarget(),
                message -> Villagelife.LOGGER.info("UI preview saved: {}", message.getString()));
        client.execute(client::stop);
    }

    private static void openSample(Minecraft client) {
        boolean trade = !"chat".equalsIgnoreCase(MODE) && !"typing".equalsIgnoreCase(MODE)
                && !"plain".equalsIgnoreCase(MODE) && !"reveal".equalsIgnoreCase(MODE);
        // "plain" is somebody with no job to offer: no tabs at all, chat only,
        // which is what most villagers are and what had never been rendered.
        boolean merchant = !"plain".equalsIgnoreCase(MODE);
        // "blocked" renders the trade tab with the market unable to deal.
        // Always a merchant: the tabs and the chat input only ever share a
        // screen on someone who has both, so that is the case worth seeing.
        if (!trade) {
            PersonChatScreen.previewChatTab();
            if ("typing".equalsIgnoreCase(MODE)) {
                PersonChatScreen.previewTyping("How much for the bread?");
            }
        }
        var inventory = client.player.getInventory();
        var menu = new com.quzzar.villagelife.menu.MarketMenu(0, inventory, 0,
                merchant ? "Fallon Moody" : "Bartleby Sanford",
                merchant ? "Merchant of Larkspur Creek" : "Wanderer of Larkspur Creek", merchant);
        client.setScreen(new PersonChatScreen(menu, inventory,
                net.minecraft.network.chat.Component.literal("Fallon Moody")));
        PersonChatScreen.openChat(0, List.of(
                new com.quzzar.villagelife.networking.OpenPersonChatPacket.ExchangeLine(
                        "", "Morning. You look like you have been walking a while."),
                new com.quzzar.villagelife.networking.OpenPersonChatPacket.ExchangeLine(
                        "Do you sell bread?",
                        "Aye, though the harvest was thin and I cannot let it go cheap.")));
        // A believable pack: a couple of stacks, a hotbar, and gaps.
        PersonChatScreen.previewInventory.put(0, new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.EMERALD, 41));
        PersonChatScreen.previewInventory.put(1, new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.OAK_LOG, 64));
        PersonChatScreen.previewInventory.put(2, new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.BREAD, 7));
        PersonChatScreen.previewInventory.put(9, new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.IRON_INGOT, 12));
        PersonChatScreen.previewInventory.put(13, new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.COAL, 33));
        PersonChatScreen.previewInventory.put(22, new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.DIAMOND_PICKAXE, 1));
        {
            // The awkward cases on purpose: a long name, a two-digit stack.
            PersonChatScreen.onMarketOffers(new MarketOffersPacket(0, "Larkspur Creek", "blocked".equalsIgnoreCase(MODE),
                    List.of(new MarketOffersPacket.Row("minecraft:bread", 1, 4),
                            new MarketOffersPacket.Row("minecraft:slime_ball", 1, 2),
                            new MarketOffersPacket.Row("minecraft:golden_apple", 1, 32)),
                    List.of(new MarketOffersPacket.Row("minecraft:bread", 2, 1),
                            new MarketOffersPacket.Row("minecraft:wheat", 4, 1),
                            new MarketOffersPacket.Row("minecraft:iron_ingot", 2, 1),
                            new MarketOffersPacket.Row("minecraft:oak_log", 34, 1),
                            new MarketOffersPacket.Row("minecraft:coal", 9, 1),
                            new MarketOffersPacket.Row("minecraft:leather", 2, 1),
                            new MarketOffersPacket.Row("minecraft:cobblestone", 24, 1)),
                    ""));
        }
    }
}
