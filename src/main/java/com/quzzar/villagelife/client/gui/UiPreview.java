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

    private UiPreview() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (MODE == null || shot) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (ticks < 0) {
            // Not TitleScreen specifically: a first launch shows accessibility
            // onboarding first, and waiting for a title that never comes is how
            // this sat idle forever. Any screen, once the loading overlay is
            // gone, means the client is drawing and the atlases are ready.
            if (client.getOverlay() == null && client.screen != null) {
                Villagelife.LOGGER.info("UI preview: opening {} over {}", MODE,
                        client.screen.getClass().getSimpleName());
                openSample(client);
                ticks = 0;
            }
            return;
        }
        if (++ticks < SETTLE_TICKS) {
            return;
        }
        shot = true;
        Screenshot.grab(client.gameDirectory, "ui-" + MODE + ".png",
                client.getMainRenderTarget(),
                message -> Villagelife.LOGGER.info("UI preview saved: {}", message.getString()));
        // One frame is the whole job; lingering only burns a GPU and a window.
        client.execute(client::stop);
    }

    private static void openSample(Minecraft client) {
        boolean trade = !"chat".equalsIgnoreCase(MODE);
        // "blocked" renders the trade tab with the market unable to deal.
        // Always a merchant: the tabs and the chat input only ever share a
        // screen on someone who has both, so that is the case worth seeing.
        if (!trade) {
            PersonChatScreen.previewChatTab();
        }
        PersonChatScreen.open(0, "Fallon Moody", "Merchant of Larkspur Creek", true,
                List.of(
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
