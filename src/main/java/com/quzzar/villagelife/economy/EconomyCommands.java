package com.quzzar.villagelife.economy;

import java.util.Optional;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.VillageManager;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Inspection for the economy engine (docs/economy.md), so values, the bank's
 * spread, and a village's own offers are all verifiable before any trading UI
 * exists.
 *
 * <pre>
 * /vleconomy value &lt;item&gt;              what it is worth, and how that was decided
 * /vleconomy bank &lt;give&gt; &lt;want&gt;        the barter rate at the exchange
 * /vleconomy offers &lt;item&gt;             what the nearest village pays and charges
 * </pre>
 */
@EventBusSubscriber(modid = Villagelife.MODID)
public final class EconomyCommands {

  private EconomyCommands() {
  }

  @SubscribeEvent
  public static void onRegisterCommands(RegisterCommandsEvent event) {
    event.getDispatcher().register(Commands.literal("vleconomy")
        .requires(source -> source.hasPermission(2))
        .then(Commands.literal("value")
            .then(Commands.argument("item", ResourceLocationArgument.id())
                .executes(context -> value(context.getSource(), ResourceLocationArgument.getId(context, "item").toString()))))
        .then(Commands.literal("bank")
            .then(Commands.argument("give", ResourceLocationArgument.id())
                .then(Commands.argument("want", ResourceLocationArgument.id())
                    .executes(context -> bank(context.getSource(),
                        ResourceLocationArgument.getId(context, "give").toString(),
                        ResourceLocationArgument.getId(context, "want").toString())))))
        .then(Commands.literal("offers")
            .then(Commands.argument("item", ResourceLocationArgument.id())
                .executes(context -> offers(context.getSource(), ResourceLocationArgument.getId(context, "item").toString())))));
  }

  private static int value(CommandSourceStack source, String itemId) {
    Optional<Item> item = ItemValues.item(itemId);
    if (item.isEmpty()) {
      source.sendFailure(Component.literal("No such item: " + itemId));
      return 0;
    }
    MinecraftServer server = source.getServer();
    Optional<Double> value = ItemValues.valueOf(item.get(), server);
    if (value.isEmpty()) {
      source.sendSuccess(() -> Component.literal(itemId + " has no value: not tradeable."), false);
      return 1;
    }
    double emeralds = value.get();
    // Only useful for things cheaper than an emerald; "1 per emerald" for a
    // diamond sword is noise.
    String perEmerald = emeralds > 0 && emeralds < 1 ? String.format(" (%.0f per emerald)", 1.0 / emeralds) : "";
    source.sendSuccess(() -> Component.literal(String.format("%s: %.3f emeralds%s%n  bank pays %.3f, charges %.3f",
        itemId, emeralds, perEmerald,
        Bank.buyPrice(item.get(), server).orElse(0.0),
        Bank.sellPrice(item.get(), server).orElse(0.0))), false);
    return 1;
  }

  private static int bank(CommandSourceStack source, String giveId, String wantId) {
    Optional<Item> give = ItemValues.item(giveId);
    Optional<Item> want = ItemValues.item(wantId);
    if (give.isEmpty() || want.isEmpty()) {
      source.sendFailure(Component.literal("No such item."));
      return 0;
    }
    Optional<Integer> rate = Bank.barterRate(give.get(), want.get(), source.getServer());
    if (rate.isEmpty()) {
      source.sendFailure(Component.literal("One of those cannot be valued, so the bank will not trade it."));
      return 0;
    }
    source.sendSuccess(() -> Component.literal(
        "The exchange wants " + rate.get() + " " + giveId + " for one " + wantId + "."), false);
    return 1;
  }

  private static int offers(CommandSourceStack source, String itemId) {
    Optional<Item> item = ItemValues.item(itemId);
    if (item.isEmpty()) {
      source.sendFailure(Component.literal("No such item: " + itemId));
      return 0;
    }
    BlockPos pos = BlockPos.containing(source.getPosition());
    Village village = VillageManager.get(source.getLevel()).getNearestVillage(pos);
    if (village == null) {
      source.sendFailure(Component.literal("No village near here."));
      return 0;
    }
    MinecraftServer server = source.getServer();
    Optional<Double> buys = VillagePricing.villageBuys(village, item.get(), server);
    if (buys.isEmpty()) {
      source.sendSuccess(() -> Component.literal(itemId + " is not tradeable."), false);
      return 1;
    }
    int held = VillagePricing.countHeld(village, item.get());
    double need = VillagePricing.demand(village, item.get());
    source.sendSuccess(() -> Component.literal(String.format(
        "'%s' and %s%n  holds %d (demand %.0f%%)%n  pays %.3f, charges %.3f%n  bank floor %.3f, ceiling %.3f",
        village.getName(), itemId, held, need * 100,
        buys.get(), VillagePricing.villageSells(village, item.get(), server).orElse(0.0),
        Bank.buyPrice(item.get(), server).orElse(0.0),
        Bank.sellPrice(item.get(), server).orElse(0.0))), false);
    return 1;
  }
}
