package com.quzzar.kithkyn.client.gui;

import java.util.List;
import java.util.Locale;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.entities.AgeStage;
import com.quzzar.kithkyn.networking.MarketOffersPacket;

import net.minecraft.client.CameraType;
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
 * ./gradlew runClientJoinLocal -Puipreview=trade
 * ./gradlew runClientJoinLocal -Puipreview=age-lineup
 * ./gradlew runClientJoinLocal -Puipreview=age-lineup-world
 * </pre>
 *
 * The client joins the local development server, opens the named preview over
 * controlled sample data, saves a PNG under {@code run-preview/screenshots/},
 * and quits. Sample data rather than live village state keeps every preview
 * deterministic and makes awkward cases reproducible.
 */
@EventBusSubscriber(modid = Kithkyn.MODID, value = Dist.CLIENT)
public final class UiPreview {

    /** Which tab to photograph, or null when not previewing. */
    private static final String MODE = normalise(System.getProperty("kithkyn.uipreview"));

    private static String normalise(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    /** Long enough for fonts, item models and the panel to be ready. */
    private static final int SETTLE_TICKS = 40;
    /** Extra time for server-spawned people and their composed skins to arrive. */
    private static final int WORLD_SETTLE_TICKS = 80;
    private static final String WORLD_LINEUP_MODE = "age-lineup-world";
    private static final String WORLD_LINEUP_TAG = "kithkyn_age_lineup_preview";
    private static final String WORLD_LINEUP_CAMERA_TAG = WORLD_LINEUP_TAG + "_camera";
    private static final String WORLD_LINEUP_CENTER_TAG = WORLD_LINEUP_TAG + "_center";
    private static final String WORLD_LINEUP_RETURN_TAG = WORLD_LINEUP_TAG + "_return";
    private static final double[] WORLD_LINEUP_OFFSETS = {2.7D, 0.9D, -0.9D, -2.7D};

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
        // Preview captures compare our UI, not transient system notices. Clear
        // toasts during settling so the final rendered frame is unobstructed.
        client.getToasts().clear();
        if (isWorldLineup()) {
            client.options.hideGui = false;
            client.gui.getChat().clearMessages(false);
        }
        if (ticks < 0) {
            Kithkyn.LOGGER.info("UI preview: opening {} in world", MODE);
            openSample(client);
            ticks = 0;
            return;
        }
        // Joining a server can drop the pause menu over us the moment the
        // window loses focus, and the first run photographed exactly that.
        if (!isExpectedScreen(client)) {
            if (isWorldLineup()) {
                client.setScreen(null);
            } else {
                openSample(client);
            }
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
        int settleTicks = isWorldLineup() ? WORLD_SETTLE_TICKS : SETTLE_TICKS;
        if (++ticks < settleTicks) {
            return;
        }
        shot = true;
        Screenshot.grab(client.gameDirectory, "ui-" + MODE + ".png",
                client.getMainRenderTarget(),
                message -> Kithkyn.LOGGER.info("UI preview saved: {}", message.getString()));
        if (isWorldLineup()) {
            cleanWorldLineup(client);
        }
        client.execute(client::stop);
    }

    private static void openSample(Minecraft client) {
        if (isWorldLineup()) {
            openWorldLineup(client);
            return;
        }
        if ("age-lineup".equalsIgnoreCase(MODE)) {
            client.setScreen(new AgeLineupScreen(client.level));
            return;
        }
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
        var menu = new com.quzzar.kithkyn.menu.MarketMenu(0, inventory, 0,
                merchant ? "Fallon Moody" : "Bartleby Sanford",
                merchant ? "Merchant of Larkspur Creek" : "Wanderer of Larkspur Creek", merchant);
        client.setScreen(new PersonChatScreen(menu, inventory,
                net.minecraft.network.chat.Component.literal("Fallon Moody")));
        PersonChatScreen.openChat(0, List.of(
                new com.quzzar.kithkyn.networking.OpenPersonChatPacket.ExchangeLine(
                        "", "Morning. You look like you have been walking a while."),
                new com.quzzar.kithkyn.networking.OpenPersonChatPacket.ExchangeLine(
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

    private static boolean isExpectedScreen(Minecraft client) {
        if (isWorldLineup()) {
            return client.screen == null;
        }
        if ("age-lineup".equalsIgnoreCase(MODE)) {
            return client.screen instanceof AgeLineupScreen;
        }
        return client.screen instanceof PersonChatScreen;
    }

    /** Spawns a disposable, server-authoritative lineup directly in front of the preview player. */
    private static void openWorldLineup(Minecraft client) {
        client.setScreen(null);
        client.options.setCameraType(CameraType.FIRST_PERSON);
        client.options.hideGui = false;

        sendCommand(client, "kill @e[tag=" + WORLD_LINEUP_TAG + "]");
        sendCommand(client, "tag @s add " + WORLD_LINEUP_CAMERA_TAG);
        sendCommand(client,
                "execute at @s run summon minecraft:marker ~ ~ ~ {Tags:[\""
                        + WORLD_LINEUP_TAG + "\",\"" + WORLD_LINEUP_RETURN_TAG + "\"]}");
        sendCommand(client, "fill -6 198 -2 6 198 9 minecraft:grass_block");
        sendCommand(client, "tp @s 0.5 199 0.5 0 0");
        sendCommand(client, "effect give @s minecraft:night_vision 30 0 true");
        for (int index = 0; index < AgeStage.values().length; index++) {
            AgeStage stage = AgeStage.values()[index];
            String command = String.format(
                    Locale.ROOT,
                    "execute at @s rotated as @s run summon kithkyn:person ^%.2f ^ ^5.50 %s",
                    WORLD_LINEUP_OFFSETS[index],
                    worldPersonNbt(stage));
            sendCommand(client, command);
        }
        sendCommand(client,
                "execute at @s rotated as @s run summon minecraft:marker ^ ^0.35 ^5.50 "
                        + "{Tags:[\"" + WORLD_LINEUP_TAG + "\",\"" + WORLD_LINEUP_CENTER_TAG + "\"]}");
        sendCommand(client,
                "execute as @e[type=kithkyn:person,tag=" + WORLD_LINEUP_TAG
                        + ",distance=..16] at @s run tp @s ~ ~ ~ facing entity "
                        + "@a[tag=" + WORLD_LINEUP_CAMERA_TAG + ",limit=1] eyes");
        sendCommand(client,
                "tp @s ~ ~ ~ facing entity @e[type=minecraft:marker,tag="
                        + WORLD_LINEUP_CENTER_TAG + ",limit=1,sort=nearest] eyes");
        sendCommand(client, "tag @s remove " + WORLD_LINEUP_CAMERA_TAG);
    }

    private static String worldPersonNbt(AgeStage stage) {
        return "{Tags:[\"" + WORLD_LINEUP_TAG + "\"],"
                + "SkinVariant:" + AgeLineupScreen.PREVIEW_SEED + ","
                + "StatBlock:{Strength:10,Dexterity:10,Constitution:10,Intelligence:10,"
                + "Wisdom:10,Charisma:10,Size:10,Eyesight:10},"
                + "AgeStage:\"" + stage.name() + "\",FirstName:\"Avery\",LastName:\"Stone\","
                + "Title:\"Adult\",Occupation:\"WANDERER\",NoAI:1b,Immobile:1b,"
                + "Invulnerable:1b,Silent:1b,CustomNameVisible:1b}";
    }

    private static void cleanWorldLineup(Minecraft client) {
        sendCommand(client,
                "tp @s @e[type=minecraft:marker,tag=" + WORLD_LINEUP_RETURN_TAG + ",limit=1]");
        sendCommand(client, "kill @e[tag=" + WORLD_LINEUP_TAG + "]");
        sendCommand(client, "fill -6 198 -2 6 198 9 minecraft:air");
        sendCommand(client, "effect clear @s minecraft:night_vision");
        sendCommand(client, "tag @s remove " + WORLD_LINEUP_CAMERA_TAG);
    }

    private static void sendCommand(Minecraft client, String command) {
        client.player.connection.sendCommand(command);
    }

    private static boolean isWorldLineup() {
        return WORLD_LINEUP_MODE.equalsIgnoreCase(MODE);
    }
}
