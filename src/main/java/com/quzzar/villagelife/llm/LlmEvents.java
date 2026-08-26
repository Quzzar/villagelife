package com.quzzar.villagelife.llm;

import java.util.Arrays;
import java.util.List;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.configuration.VillagelifeConfig;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

@EventBusSubscriber(modid = Villagelife.MODID)
public class LlmEvents {

  @SubscribeEvent
  public static void onServerAboutToStart(ServerAboutToStartEvent event) {
    if (VillagelifeConfig.LlmEnabled) {
      LlmService.get().startLoading();
    }
  }

  @SubscribeEvent
  public static void onRegisterCommands(RegisterCommandsEvent event) {
    event.getDispatcher().register(Commands.literal("vlbrain")
        .requires(source -> source.hasPermission(2))
        .then(Commands.literal("status").executes(context -> {
          LlmService llm = LlmService.get();
          String detail = llm.getStatusDetail();
          String message = "LLM status: " + llm.getStatus() + (detail.isEmpty() ? "" : " (" + detail + ")");
          context.getSource().sendSuccess(() -> Component.literal(message), false);
          return 1;
        }))
        .then(Commands.literal("load").executes(context -> {
          if (!VillagelifeConfig.LlmEnabled) {
            context.getSource().sendFailure(Component.literal("The LLM is disabled in the villagelife config."));
            return 0;
          }
          LlmService.get().startLoading();
          context.getSource().sendSuccess(() -> Component.literal("LLM loading started, check /vlbrain status."), false);
          return 1;
        }))
        .then(Commands.literal("ask")
            .then(Commands.argument("query", StringArgumentType.greedyString()).executes(context -> {
              String query = StringArgumentType.getString(context, "query");
              return ask(context.getSource(), query);
            })))
        .then(Commands.literal("chat")
            .then(Commands.argument("target", net.minecraft.commands.arguments.EntityArgument.entity())
                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(context -> {
                  net.minecraft.world.entity.Entity target = net.minecraft.commands.arguments.EntityArgument
                      .getEntity(context, "target");
                  String message = StringArgumentType.getString(context, "message");
                  return chat(context.getSource(), target, message);
                })))));
  }

  /**
   * Scriptable entry into the conversation pipeline — the same code path as
   * right-clicking a villager, minus the client screen. From a player, speaks
   * as that player; from console/RCON, speaks as a synthetic "Tester". A give
   * the villager decides on is reported, not tossed (there may be nobody to
   * toss it to).
   */
  private static int chat(CommandSourceStack source, net.minecraft.world.entity.Entity target, String message) {
    if (!(target instanceof com.quzzar.villagelife.entities.RealPerson person)) {
      source.sendFailure(Component.literal("Target is not a villager."));
      return 0;
    }
    LlmService llm = LlmService.get();
    if (!llm.isReady()) {
      source.sendFailure(Component.literal("LLM is not ready (status: " + llm.getStatus() + "). Try /vlbrain load."));
      return 0;
    }
    String speakerName = source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
        ? player.getGameProfile().getName()
        : "Tester";
    java.util.UUID speakerUUID = source.getEntity() != null
        ? source.getEntity().getUUID()
        : java.util.UUID.nameUUIDFromBytes("villagelife-tester".getBytes());

    source.sendSuccess(() -> Component.literal("Thinking..."), false);
    long start = System.currentTimeMillis();
    MinecraftServer server = source.getServer();
    com.quzzar.villagelife.chat.PersonChatDispatcher.converse(person, speakerName, speakerUUID, message)
        .thenAccept(reply -> server.execute(() -> {
          long tookMs = System.currentTimeMillis() - start;
          String give = reply.give() != null ? " [would give: " + reply.give() + "]" : "";
          source.sendSuccess(() -> Component.literal(
              person.getFullName() + ": " + reply.say() + give + " (" + tookMs + " ms)"), false);
        }));
    return 1;
  }

  /** Test command; query format: "situation | option 1 | option 2 | ...". */
  private static int ask(CommandSourceStack source, String query) {
    List<String> parts = Arrays.stream(query.split("\\|")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    if (parts.size() < 3) {
      source.sendFailure(Component.literal("Format: /vlbrain ask <situation> | <option 1> | <option 2> [| more options]"));
      return 0;
    }
    LlmService llm = LlmService.get();
    if (!llm.isReady()) {
      source.sendFailure(Component.literal("LLM is not ready (status: " + llm.getStatus() + "). Try /vlbrain load."));
      return 0;
    }

    String situation = parts.get(0);
    List<String> options = parts.subList(1, parts.size());
    source.sendSuccess(() -> Component.literal("Thinking..."), false);

    long start = System.currentTimeMillis();
    MinecraftServer server = source.getServer();
    llm.decide(situation, options).thenAccept(decision -> server.execute(() -> {
      long tookMs = System.currentTimeMillis() - start;
      String message = decision
          .map(d -> "Chose \"" + d.choice() + "\"" + (d.reason().isEmpty() ? "" : ": " + d.reason())
              + " (" + tookMs + " ms)")
          .orElse("The LLM gave no usable answer (" + tookMs + " ms), a rule-based fallback would be used.");
      source.sendSuccess(() -> Component.literal(message), false);
    }));
    return 1;
  }

}
