package com.quzzar.kithkyn.dev;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.entities.genetics.GeneticCondition;
import com.quzzar.kithkyn.entities.genetics.Stat;
import com.quzzar.kithkyn.entities.genetics.StatBlock;
import com.quzzar.kithkyn.relationships.BirthMultiplicity;
import com.quzzar.kithkyn.relationships.ChildCreationService;
import com.quzzar.kithkyn.relationships.FamilyPlanningService;
import com.quzzar.kithkyn.village.FamilyPlans;
import com.quzzar.kithkyn.village.Village;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

/** Developer controls for family scheduling, births, and non-spawning genetic samples. */
public final class FamilyCommands {

  private FamilyCommands() {
  }

  public static LiteralArgumentBuilder<CommandSourceStack> branch() {
    return Commands.literal("family")
        .then(twoPeople("status", FamilyCommands::status))
        .then(twoPeople("consider", FamilyCommands::consider))
        .then(Commands.literal("birth")
            .then(Commands.argument("firstParent", EntityArgument.entity())
                .then(Commands.argument("secondParent", EntityArgument.entity())
                    .executes(context -> birth(
                        context.getSource(),
                        EntityArgument.getEntity(context, "firstParent"),
                        EntityArgument.getEntity(context, "secondParent"),
                        BirthMultiplicity.SINGLETON))
                    .then(Commands.argument("multiplicity", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                            List.of("singleton", "twins", "triplets"), builder))
                        .executes(context -> birth(
                            context.getSource(),
                            EntityArgument.getEntity(context, "firstParent"),
                            EntityArgument.getEntity(context, "secondParent"),
                            multiplicity(StringArgumentType.getString(context, "multiplicity"))))))))
        .then(Commands.literal("sample")
            .then(Commands.argument("firstParent", EntityArgument.entity())
                .then(Commands.argument("secondParent", EntityArgument.entity())
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 10_000))
                        .executes(context -> sample(
                            context.getSource(),
                            EntityArgument.getEntity(context, "firstParent"),
                            EntityArgument.getEntity(context, "secondParent"),
                            IntegerArgumentType.getInteger(context, "count")))))));
  }

  @FunctionalInterface
  private interface PairCommand {
    int run(CommandSourceStack source, Entity first, Entity second);
  }

  private static LiteralArgumentBuilder<CommandSourceStack> twoPeople(String name, PairCommand command) {
    return Commands.literal(name)
        .then(Commands.argument("firstParent", EntityArgument.entity())
            .then(Commands.argument("secondParent", EntityArgument.entity())
                .executes(context -> command.run(
                    context.getSource(),
                    EntityArgument.getEntity(context, "firstParent"),
                    EntityArgument.getEntity(context, "secondParent")))));
  }

  private static int status(CommandSourceStack source, Entity firstEntity, Entity secondEntity) {
    RealPerson first = person(source, firstEntity);
    RealPerson second = person(source, secondEntity);
    if (first == null || second == null) {
      return 0;
    }
    Village village = ChildCreationService.sharedVillage(first, second);
    if (village == null) {
      source.sendFailure(Component.literal("The two people do not share a village."));
      return 0;
    }
    String problem = FamilyPlanningService.eligibilityProblem(village, first, second);
    Optional<FamilyPlans.Plan> plan = FamilyPlans.get(village, first.getUUID(), second.getUUID());
    String schedule = plan.map(value -> value.hasPendingBirth()
        ? "birth due on day " + value.birthDay()
        : "next conversation on day " + value.nextTalkDay()).orElse("no plan created yet");
    source.sendSuccess(() -> Component.literal(
        first.getFullName() + " + " + second.getFullName() + ": "
            + (problem == null ? "eligible" : "not eligible, " + problem) + "; " + schedule + "."), false);
    return 1;
  }

  private static int consider(CommandSourceStack source, Entity firstEntity, Entity secondEntity) {
    RealPerson first = person(source, firstEntity);
    RealPerson second = person(source, secondEntity);
    if (first == null || second == null || !(first.level() instanceof ServerLevel level)) {
      return 0;
    }
    Village village = ChildCreationService.sharedVillage(first, second);
    if (village == null) {
      source.sendFailure(Component.literal("The two people do not share a village."));
      return 0;
    }
    String problem = FamilyPlanningService.eligibilityProblem(village, first, second);
    if (problem != null) {
      source.sendFailure(Component.literal("Cannot start family planning: " + problem + "."));
      return 0;
    }
    source.sendSuccess(() -> Component.literal("Starting their family-planning conversation..."), false);
    FamilyPlanningService.considerNow(village, level, first, second).thenAccept(decision -> {
      String result = decision.map(value -> value == com.quzzar.kithkyn.relationships.FamilyPlanningDialogue.Decision.WANT_CHILD
          ? "They both want a child; the birth is scheduled for tomorrow morning."
          : "They decided not to have a child yet.")
          .orElse("Their conversation reached no decision and will be retried later.");
      source.sendSuccess(() -> Component.literal(result), true);
    });
    return 1;
  }

  private static int birth(
      CommandSourceStack source, Entity firstEntity, Entity secondEntity, BirthMultiplicity multiplicity) {
    if (multiplicity == null) {
      source.sendFailure(Component.literal("Use singleton, twins, or triplets."));
      return 0;
    }
    RealPerson first = person(source, firstEntity);
    RealPerson second = person(source, secondEntity);
    if (first == null || second == null || first == second
        || first.level() != second.level() || !(first.level() instanceof ServerLevel level)) {
      source.sendFailure(Component.literal("A birth needs two different parents together in one dimension."));
      return 0;
    }
    source.sendSuccess(() -> Component.literal("Generating " + multiplicity.name().toLowerCase(Locale.ROOT)
        + " for " + first.getFullName() + " and " + second.getFullName() + "..."), false);
    FamilyPlanningService.birthNow(level, first, second, multiplicity).whenComplete((attempts, error) -> {
      if (error != null) {
        Kithkyn.LOGGER.error("Family development birth failed", error);
        source.sendFailure(Component.literal("Birth failed; see the server log."));
        return;
      }
      List<String> names = attempts.stream()
          .flatMap(attempt -> attempt.spawned().stream())
          .map(RealPerson::getFullName)
          .toList();
      source.sendSuccess(() -> Component.literal("Born: " + String.join(", ", names) + "."), true);
    });
    return 1;
  }

  private static int sample(
      CommandSourceStack source, Entity firstEntity, Entity secondEntity, int count) {
    RealPerson first = person(source, firstEntity);
    RealPerson second = person(source, secondEntity);
    if (first == null || second == null || first == second) {
      return 0;
    }
    RandomSource random = RandomSource.create(first.getUUID().getMostSignificantBits()
        ^ second.getUUID().getLeastSignificantBits() ^ count);
    EnumMap<Stat, Integer> minimum = new EnumMap<>(Stat.class);
    EnumMap<Stat, Integer> maximum = new EnumMap<>(Stat.class);
    EnumMap<Stat, Long> total = new EnumMap<>(Stat.class);
    EnumMap<GeneticCondition, Integer> conditions = new EnumMap<>(GeneticCondition.class);
    for (Stat stat : Stat.values()) {
      minimum.put(stat, Integer.MAX_VALUE);
      maximum.put(stat, Integer.MIN_VALUE);
      total.put(stat, 0L);
    }
    for (int index = 0; index < count; index++) {
      StatBlock child = StatBlock.inherit(
          first.getStatBlock(), second.getStatBlock(), random::nextLong);
      for (Stat stat : Stat.values()) {
        int value = child.get(stat);
        minimum.put(stat, Math.min(minimum.get(stat), value));
        maximum.put(stat, Math.max(maximum.get(stat), value));
        total.put(stat, total.get(stat) + value);
      }
      conditions.merge(child.getCondition(), 1, Integer::sum);
    }
    List<String> stats = new ArrayList<>();
    for (Stat stat : Stat.values()) {
      stats.add(String.format(Locale.ROOT, "%s %d/%.2f/%d",
          stat.name().toLowerCase(Locale.ROOT), minimum.get(stat), total.get(stat) / (double) count,
          maximum.get(stat)));
    }
    source.sendSuccess(() -> Component.literal(
        count + " simulated children (min/mean/max): " + String.join(", ", stats)
            + ". Conditions: " + conditions + ". No entities were spawned."), false);
    return 1;
  }

  private static BirthMultiplicity multiplicity(String name) {
    try {
      return BirthMultiplicity.valueOf(name.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private static RealPerson person(CommandSourceStack source, Entity entity) {
    if (entity instanceof RealPerson person) {
      return person;
    }
    source.sendFailure(Component.literal(entity.getName().getString() + " is not a kithkyn person."));
    return null;
  }
}
