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
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@EventBusSubscriber(modid = Villagelife.MODID)
public class LlmEvents {

  /**
   * Stops the LLM while the server is still running, which is the only moment
   * that works: a JVM shutdown hook runs after the JVM decides to exit, and it
   * will not decide while the worker keeps it alive (#67).
   */
  @SubscribeEvent
  public static void onServerStopping(ServerStoppingEvent event) {
    LlmService.get().shutdown();
  }

  @SubscribeEvent
  public static void onServerAboutToStart(ServerAboutToStartEvent event) {
    if (VillagelifeConfig.LlmEnabled) {
      LlmService.get().startLoading();
    }
  }

  /** Operator command: is the villager AI working? Lives on /villagelife. */
  public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> statusBranch() {
    return Commands.literal("status").executes(context -> {
          LlmService llm = LlmService.get();
          String detail = llm.getStatusDetail();
          String message = "LLM status: " + llm.getStatus() + (detail.isEmpty() ? "" : " (" + detail + ")");
          context.getSource().sendSuccess(() -> Component.literal(message), false);
          return 1;
        });
  }

  /** Operator command: retry after a failure. Lives on /villagelife. */
  public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> loadBranch() {
    return Commands.literal("load").executes(context -> {
          if (!VillagelifeConfig.LlmEnabled) {
            context.getSource().sendFailure(Component.literal("The LLM is disabled in the villagelife config."));
            return 0;
          }
          LlmService.get().startLoading();
          context.getSource().sendSuccess(() -> Component.literal("LLM loading started, check /villagelife status."), false);
          return 1;
        });
  }

  /** LLM test harness, mounted under /vldev: superseded for players by talking to a villager. */
  public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> devBranch() {
    return Commands.literal("llm")
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
                }))))
        .then(Commands.literal("talk")
            .then(Commands.argument("first", net.minecraft.commands.arguments.EntityArgument.entity())
                .then(Commands.argument("second", net.minecraft.commands.arguments.EntityArgument.entity())
                    .executes(context -> talk(context.getSource(),
                        net.minecraft.commands.arguments.EntityArgument.getEntity(context, "first"),
                        net.minecraft.commands.arguments.EntityArgument.getEntity(context, "second"))))))
        .then(Commands.literal("say")
            .then(Commands.argument("target", net.minecraft.commands.arguments.EntityArgument.entity())
                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(context -> {
                  net.minecraft.world.entity.Entity target = net.minecraft.commands.arguments.EntityArgument
                      .getEntity(context, "target");
                  String message = StringArgumentType.getString(context, "message");
                  return say(context.getSource(), target, message);
                }))))
        .then(Commands.literal("plan")
            .then(Commands.argument("target", net.minecraft.commands.arguments.EntityArgument.entity())
                .executes(context -> plan(context.getSource(),
                    net.minecraft.commands.arguments.EntityArgument.getEntity(context, "target")))));
  }

  /**
   * Prototype: has a quartermaster plan their storehouse aloud through the
   * two-turn dialogue, saves the plan, tidies once so the shelving is visible
   * immediately, and prints the result. The automatic triggers come later.
   */
  private static int plan(CommandSourceStack source, net.minecraft.world.entity.Entity target) {
    if (!(target instanceof com.quzzar.villagelife.entities.RealPerson person)) {
      source.sendFailure(Component.literal("Target is not a villager."));
      return 0;
    }
    com.quzzar.villagelife.village.Village village = person.getVillage();
    if (village == null) {
      source.sendFailure(Component.literal("That villager belongs to no village."));
      return 0;
    }
    if (com.quzzar.villagelife.village.Storehouse.chests(person).isEmpty()) {
      source.sendFailure(Component.literal(
          "No storehouse to organise: aim this at a quartermaster who has a storehouse workplace."));
      return 0;
    }
    LlmService llm = LlmService.get();
    if (!llm.isReady()) {
      source.sendFailure(Component.literal(
          "LLM is not ready (status: " + llm.getStatus() + "). Try /vldev llm load."));
      return 0;
    }
    source.sendSuccess(() -> Component.literal("Consulting " + person.getFullName()
        + " about the storehouse; follow the [quartermaster] log lines."), false);
    com.quzzar.villagelife.village.QuartermasterPlanner.plan(person).thenAccept(outcome -> {
      net.minecraft.server.MinecraftServer server = person.getServer();
      if (server == null) {
        return;
      }
      server.execute(() -> {
        if (outcome.isEmpty()) {
          source.sendFailure(Component.literal(
              "No usable plan came back; the storehouse keeps its current order."));
          return;
        }
        com.quzzar.villagelife.village.QuartermasterPlanner.Outcome result = outcome.get();
        com.quzzar.villagelife.village.ShelvingPlan.store(village, result.plan());
        com.quzzar.villagelife.entities.ai.goals.work.ConsolidateStep.tidyStorehouse(person);
        reportPlan(source, person, result);
      });
    });
    return 1;
  }

  private static void reportPlan(CommandSourceStack source,
      com.quzzar.villagelife.entities.RealPerson person,
      com.quzzar.villagelife.village.QuartermasterPlanner.Outcome result) {
    source.sendSuccess(() -> Component.literal(
        person.getFullName() + ": “" + result.note() + "”"), false);
    for (com.quzzar.villagelife.village.ShelvingPlan.Category category : result.plan().categories()) {
      java.util.List<String> names = new java.util.ArrayList<>();
      for (String id : category.itemIds()) {
        names.add(displayName(id));
      }
      int first = category.firstSlot() + 1;
      int last = category.firstSlot() + category.slotCount();
      String span = first == last ? "slot " + first : "slots " + first + " to " + last;
      String contents = names.isEmpty() ? "(no items)" : String.join(", ", names);
      String line = category.name() + " (" + span + "): " + contents;
      source.sendSuccess(() -> Component.literal(line), false);
    }
  }

  private static String displayName(String itemId) {
    net.minecraft.resources.ResourceLocation location =
        net.minecraft.resources.ResourceLocation.tryParse(itemId);
    if (location != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(location)) {
      return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(location).getDescription().getString();
    }
    return itemId;
  }

  /**
   * Forces one speech bubble above a villager, with no LLM involved. The line
   * is cleaned exactly as a generated one would be, so typing an em dash shows
   * the strip (it lands as a semicolon). For eyeballing the bubble render.
   */
  private static int say(CommandSourceStack source, net.minecraft.world.entity.Entity target, String message) {
    if (!(target instanceof com.quzzar.villagelife.entities.RealPerson person)) {
      source.sendFailure(Component.literal("Target is not a villager."));
      return 0;
    }
    com.quzzar.villagelife.chat.VillagerConversation.speakTest(person, message);
    source.sendSuccess(() -> Component.literal(person.getFullName() + " speaks a bubble."), false);
    return 1;
  }

  /**
   * Forces a conversation between two villagers: the same driver
   * SeekConversationGoal hands off to, minus the walk and the cooldown. The
   * pair must already stand within talking range (a few blocks), because the
   * driver ends any conversation whose parties are apart. Progress is read
   * from the [villager chat] and [chat] log lines, headless-style.
   */
  private static int talk(CommandSourceStack source, net.minecraft.world.entity.Entity first,
      net.minecraft.world.entity.Entity second) {
    if (!(first instanceof com.quzzar.villagelife.entities.RealPerson a)
        || !(second instanceof com.quzzar.villagelife.entities.RealPerson b)) {
      source.sendFailure(Component.literal("Both targets must be villagers."));
      return 0;
    }
    if (com.quzzar.villagelife.chat.VillagerConversation.tryStart(a, b, true)) {
      source.sendSuccess(() -> Component.literal(a.getFullName() + " strikes up a conversation with "
          + b.getFullName() + "; follow the [villager chat] log lines."), false);
      return 1;
    }
    source.sendFailure(Component.literal("Could not start: LLM not ready or villager conversations "
        + "disabled, one of them is already mid-conversation, another conversation holds the slot, "
        + "or they stand too far apart (talking range is a few blocks)."));
    return 0;
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
