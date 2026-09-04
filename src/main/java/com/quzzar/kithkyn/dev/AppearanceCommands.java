package com.quzzar.kithkyn.dev;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.appearance.AppearanceAsset;
import com.quzzar.kithkyn.appearance.AppearanceCatalog;
import com.quzzar.kithkyn.appearance.AppearanceInputs;
import com.quzzar.kithkyn.appearance.AppearancePart;
import com.quzzar.kithkyn.appearance.AppearanceRecipeAudit;
import com.quzzar.kithkyn.appearance.AppearanceRecipeFactory;
import com.quzzar.kithkyn.appearance.LifeStage;
import com.quzzar.kithkyn.appearance.PigmentColor;
import com.quzzar.kithkyn.appearance.PigmentPalette;
import com.quzzar.kithkyn.appearance.SkinRecipe;
import com.quzzar.kithkyn.entities.AgeStage;
import com.quzzar.kithkyn.entities.Gender;
import com.quzzar.kithkyn.entities.Person;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.entities.genetics.AppearanceGenes;
import com.quzzar.kithkyn.entities.genetics.GeneticCondition;
import com.quzzar.kithkyn.entities.genetics.PigmentGene;
import com.quzzar.kithkyn.entities.genetics.Stat;
import com.quzzar.kithkyn.entities.genetics.StatBlock;
import com.quzzar.kithkyn.relationships.ChildCreationService;
import com.quzzar.kithkyn.village.Occupation;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Live development controls and invariant audits for the layered appearance system. */
public final class AppearanceCommands {

  private static final String CATALOG_RESOURCE = "assets/kithkyn/appearance/catalog.json";
  private static final int SYSTEM_AUDIT_SEEDS = 4;
  private static final int MAXIMUM_FAILURE_EXAMPLES = 12;

  private static final SuggestionProvider<CommandSourceStack> CONDITIONS = (context, builder) ->
      SharedSuggestionProvider.suggest(
          java.util.Arrays.stream(GeneticCondition.values())
              .map(condition -> condition.name().toLowerCase(Locale.ROOT)),
          builder);
  private static final SuggestionProvider<CommandSourceStack> OCCUPATIONS = (context, builder) ->
      SharedSuggestionProvider.suggest(
          java.util.Arrays.stream(Occupation.values())
              .map(occupation -> occupation.name().toLowerCase(Locale.ROOT)),
          builder);
  private static final SuggestionProvider<CommandSourceStack> PIGMENT_PARTS = (context, builder) ->
      SharedSuggestionProvider.suggest(List.of("skin", "hair", "eyes", "alternate-eyes"), builder);
  private static final SuggestionProvider<CommandSourceStack> LIFE_STAGES = (context, builder) ->
      SharedSuggestionProvider.suggest(
          java.util.Arrays.stream(AgeStage.values())
              .map(stage -> stage.name().toLowerCase(Locale.ROOT)),
          builder);

  private static AppearanceCatalog builtInCatalog;

  private AppearanceCommands() {
  }

  /** Mounted beneath the developer-only {@code /kkdev} root. */
  public static LiteralArgumentBuilder<CommandSourceStack> branch() {
    return Commands.literal("appearance")
        .then(Commands.literal("show")
            .then(Commands.argument("target", EntityArgument.entity())
                .executes(context -> show(
                    context.getSource(),
                    EntityArgument.getEntity(context, "target")))))
        .then(Commands.literal("audit")
            .executes(context -> auditSystem(context.getSource()))
            .then(Commands.argument("targets", EntityArgument.entities())
                .executes(context -> auditTargets(
                    context.getSource(),
                    EntityArgument.getEntities(context, "targets")))))
        .then(Commands.literal("reroll")
            .then(Commands.argument("target", EntityArgument.entity())
                .executes(context -> reroll(
                    context.getSource(),
                    EntityArgument.getEntity(context, "target")))))
        .then(Commands.literal("inherit")
            .then(Commands.argument("child", EntityArgument.entity())
                .then(Commands.argument("firstParent", EntityArgument.entity())
                    .then(Commands.argument("secondParent", EntityArgument.entity())
                        .executes(context -> inherit(
                            context.getSource(),
                            EntityArgument.getEntity(context, "child"),
                            EntityArgument.getEntity(context, "firstParent"),
                            EntityArgument.getEntity(context, "secondParent")))))))
        .then(Commands.literal("child")
            .then(Commands.argument("firstParent", EntityArgument.entity())
                .then(Commands.argument("secondParent", EntityArgument.entity())
                    .executes(context -> spawnChild(
                        context.getSource(),
                        EntityArgument.getEntity(context, "firstParent"),
                        EntityArgument.getEntity(context, "secondParent"))))))
        .then(Commands.literal("pigment")
            .then(Commands.argument("target", EntityArgument.entity())
                .then(Commands.argument("part", StringArgumentType.word())
                    .suggests(PIGMENT_PARTS)
                    .then(Commands.argument("depth", IntegerArgumentType.integer(0, 255))
                        .then(Commands.argument("warmth", IntegerArgumentType.integer(0, 255))
                            .executes(context -> setPigment(
                                context.getSource(),
                                EntityArgument.getEntity(context, "target"),
                                StringArgumentType.getString(context, "part"),
                                IntegerArgumentType.getInteger(context, "depth"),
                                IntegerArgumentType.getInteger(context, "warmth"))))))))
        .then(Commands.literal("condition")
            .then(Commands.argument("target", EntityArgument.entity())
                .then(Commands.argument("condition", StringArgumentType.word())
                    .suggests(CONDITIONS)
                    .executes(context -> setCondition(
                        context.getSource(),
                        EntityArgument.getEntity(context, "target"),
                        StringArgumentType.getString(context, "condition"))))))
        .then(Commands.literal("stage")
            .then(Commands.argument("target", EntityArgument.entity())
                .then(Commands.argument("stage", StringArgumentType.word())
                    .suggests(LIFE_STAGES)
                    .executes(context -> setLifeStage(
                        context.getSource(),
                        EntityArgument.getEntity(context, "target"),
                        StringArgumentType.getString(context, "stage"))))))
        .then(Commands.literal("occupation")
            .then(Commands.argument("target", EntityArgument.entity())
                .then(Commands.argument("occupation", StringArgumentType.word())
                    .suggests(OCCUPATIONS)
                    .executes(context -> setOccupation(
                        context.getSource(),
                        EntityArgument.getEntity(context, "target"),
                        StringArgumentType.getString(context, "occupation"))))));
  }

  private static int show(CommandSourceStack source, Entity entity) {
    RealPerson person = requirePerson(source, entity);
    if (person == null) {
      return 0;
    }
    try {
      AppearanceCatalog catalog = catalog();
      AppearanceInputs inputs = inputs(person);
      SkinRecipe recipe = AppearanceRecipeFactory.create(catalog, inputs);
      AppearanceGenes genes = person.getAppearanceGenes();
      StatBlock stats = person.getStatBlock();
      List<String> failures = AppearanceRecipeAudit.validate(catalog, inputs, recipe);
      String output = person.getFullName()
          + " — appearance " + (failures.isEmpty() ? "PASS" : "FAIL")
          + "\n  state: " + inputs.gender().name().toLowerCase(Locale.ROOT)
          + ", " + person.getLifeStage().name().toLowerCase(Locale.ROOT)
          + " (" + inputs.lifeStage().name().toLowerCase(Locale.ROOT) + " wardrobe)"
          + ", " + inputs.occupation().name().toLowerCase(Locale.ROOT)
          + ", " + inputs.condition().name().toLowerCase(Locale.ROOT)
          + "\n  expression: " + recipe.expression().name().toLowerCase(Locale.ROOT)
          + ", " + recipe.model().name().toLowerCase(Locale.ROOT)
          + ", seed " + person.getAppearanceSeed()
          + "\n  stats: " + (stats == null ? "unavailable" : describeStats(stats))
          + "\n  structure: skin " + describe(catalog, recipe.skin())
          + "; hair " + describe(catalog, recipe.hair())
          + "\n  eyes: left " + describe(catalog, recipe.leftEye())
          + "; right " + describe(catalog, recipe.rightEye())
          + "\n  clothing: " + describe(catalog, recipe.clothing())
          + (recipe.headwearOccludesHair() ? " (headwear occludes hair)" : "")
          + "\n  " + pigmentDescription("skin", genes.skinPigment(), recipe.skinPigment())
          + "\n  " + pigmentDescription("hair", genes.hairPigment(), recipe.hairPigment())
          + "\n  " + pigmentDescription("primary eye", genes.eyePigment(), recipe.leftEyePigment())
          + "\n  " + pigmentDescription(
              "alternate eye", genes.alternateEyePigment(), PigmentPalette.eyes(genes.alternateEyePigment()))
          + (inputs.condition() == GeneticCondition.HETEROCHROMIA
              ? "\n  active right-eye colors: " + colorPair(recipe.rightEyePigment())
              : "")
          + (failures.isEmpty() ? "" : "\n  failures: " + String.join("; ", failures));
      source.sendSuccess(() -> Component.literal(output), false);
      return failures.isEmpty() ? 1 : 0;
    } catch (IOException | RuntimeException exception) {
      return reportFailure(source, "Could not derive appearance for " + person.getFullName(), exception);
    }
  }

  private static int auditSystem(CommandSourceStack source) {
    AppearanceCatalog catalog;
    try {
      catalog = catalog();
    } catch (IOException | RuntimeException exception) {
      return reportFailure(source, "Could not load the built-in appearance catalog", exception);
    }

    MinecraftServer server = source.getServer();
    source.sendSuccess(() -> Component.literal(
        "Appearance audit started in the background: packaged PNGs plus the full recipe matrix."), false);
    CompletableFuture.supplyAsync(() -> runSystemAudit(catalog)).whenComplete((result, error) ->
        server.execute(() -> {
          if (error != null) {
            Kithkyn.LOGGER.error("Appearance system audit crashed", error);
            source.sendFailure(Component.literal("Appearance audit crashed; see the server log."));
            return;
          }
          if (result.failureCount() == 0) {
            String summary = String.format(
                Locale.ROOT,
                "Appearance audit PASS — %d assets, %d packaged 64x64 layers, %d deterministic recipes in %d ms.",
                result.assetCount(),
                result.layerCount(),
                result.recipeCount(),
                result.durationMillis());
            Kithkyn.LOGGER.info(summary);
            source.sendSuccess(() -> Component.literal(summary), false);
            return;
          }
          String summary = "Appearance audit FAIL — " + result.failureCount() + " failure(s) across "
              + result.recipeCount() + " recipes.\n  " + String.join("\n  ", result.failureExamples());
          Kithkyn.LOGGER.error(summary);
          source.sendFailure(Component.literal(summary));
        }));
    return 1;
  }

  private static SystemAuditResult runSystemAudit(AppearanceCatalog catalog) {
    long started = System.nanoTime();
    AuditFailures failures = new AuditFailures();
    int layerCount = 0;
    for (AppearanceAsset asset : catalog.assets()) {
      for (AppearancePart part : AppearancePart.values()) {
        if (!asset.has(part)) {
          continue;
        }
        layerCount++;
        String layerFailure = validatePackagedLayer(asset, part);
        if (layerFailure != null) {
          failures.add(layerFailure);
        }
      }
    }

    int recipeCount = 0;
    for (Gender gender : Gender.values()) {
      for (Occupation occupation : Occupation.values()) {
        for (LifeStage lifeStage : LifeStage.values()) {
          for (GeneticCondition condition : GeneticCondition.values()) {
            for (int seed = 0; seed < SYSTEM_AUDIT_SEEDS; seed++) {
              recipeCount++;
              AppearanceInputs inputs = new AppearanceInputs(
                  seed,
                  AppearanceGenes.fromLegacySeed(seed * 31 + 17),
                  gender,
                  occupation,
                  lifeStage,
                  condition);
              try {
                SkinRecipe recipe = AppearanceRecipeFactory.create(catalog, inputs);
                SkinRecipe repeated = AppearanceRecipeFactory.create(catalog, inputs);
                List<String> recipeFailures = new ArrayList<>(
                    AppearanceRecipeAudit.validate(catalog, inputs, recipe));
                if (!recipe.equals(repeated)) {
                  recipeFailures.add("same inputs produced a different recipe");
                }
                if (!recipeFailures.isEmpty()) {
                  failures.add(gender + "/" + occupation + "/" + lifeStage + "/" + condition
                      + "/seed=" + seed + ": " + String.join("; ", recipeFailures));
                }
              } catch (RuntimeException exception) {
                failures.add(gender + "/" + occupation + "/" + lifeStage + "/" + condition
                    + "/seed=" + seed + ": " + safeMessage(exception));
              }
            }
          }
        }
      }
    }
    long durationMillis = (System.nanoTime() - started) / 1_000_000L;
    return new SystemAuditResult(
        catalog.assets().size(),
        layerCount,
        recipeCount,
        durationMillis,
        failures.count(),
        failures.examples());
  }

  private static int auditTargets(CommandSourceStack source, Collection<? extends Entity> entities) {
    AppearanceCatalog catalog;
    try {
      catalog = catalog();
    } catch (IOException | RuntimeException exception) {
      return reportFailure(source, "Could not load the built-in appearance catalog", exception);
    }

    int passed = 0;
    int failed = 0;
    List<String> details = new ArrayList<>();
    for (Entity entity : entities) {
      if (!(entity instanceof RealPerson person)) {
        failed++;
        addExample(details, entity.getName().getString() + ": not a kithkyn person");
        continue;
      }
      try {
        AppearanceGenes genes = person.getAppearanceGenes();
        AppearanceInputs inputs = inputs(person);
        SkinRecipe recipe = AppearanceRecipeFactory.create(catalog, inputs);
        List<String> personFailures = new ArrayList<>(AppearanceRecipeAudit.validate(catalog, inputs, recipe));
        if (!AppearanceGenes.load(genes.save()).equals(genes)) {
          personFailures.add("appearance genes fail an NBT round trip");
        }
        if (person.getStatBlock() == null) {
          personFailures.add("missing stat block");
        } else if (person.getStatBlock().getCondition() != person.getGeneticCondition()) {
          personFailures.add("stored and synced conditions disagree");
        }
        if (personFailures.isEmpty()) {
          passed++;
          addExample(details, "PASS " + person.getFullName() + " — " + recipe.model().name().toLowerCase(Locale.ROOT)
              + ", " + inputs.occupation().name().toLowerCase(Locale.ROOT)
              + ", " + inputs.condition().name().toLowerCase(Locale.ROOT));
        } else {
          failed++;
          addExample(details, "FAIL " + person.getFullName() + " — " + String.join("; ", personFailures));
        }
      } catch (RuntimeException exception) {
        failed++;
        addExample(details, "FAIL " + person.getFullName() + " — " + safeMessage(exception));
      }
    }

    String summary = "Live appearance audit — " + passed + " passed, " + failed + " failed"
        + (details.isEmpty() ? "." : ":\n  " + String.join("\n  ", details));
    if (failed > 0) {
      source.sendFailure(Component.literal(summary));
      return 0;
    }
    source.sendSuccess(() -> Component.literal(summary), false);
    return 1;
  }

  private static int reroll(CommandSourceStack source, Entity entity) {
    RealPerson person = requirePerson(source, entity);
    if (person == null) {
      return 0;
    }
    person.setAppearanceSeed(person.getRandom().nextInt(Person.APPEARANCE_SEED_BOUND));
    person.setAppearanceGenes(AppearanceGenes.roll(person.getRandom()));
    source.sendSuccess(() -> Component.literal(
        "Rerolled " + person.getFullName() + " appearance. Condition and occupation stayed unchanged."), true);
    return 1;
  }

  private static int inherit(
      CommandSourceStack source,
      Entity childEntity,
      Entity firstParentEntity,
      Entity secondParentEntity) {
    RealPerson child = requirePerson(source, childEntity);
    RealPerson firstParent = requirePerson(source, firstParentEntity);
    RealPerson secondParent = requirePerson(source, secondParentEntity);
    if (child == null || firstParent == null || secondParent == null) {
      return 0;
    }
    ChildCreationService.applyInheritance(child, firstParent, secondParent);
    source.sendSuccess(() -> Component.literal(
        "Applied inherited appearance and stats from " + firstParent.getFullName() + " and "
            + secondParent.getFullName() + " to " + child.getFullName()
            + "; the target is now a Toddler using child geometry and commonwear."), true);
    return 1;
  }

  private static int spawnChild(
      CommandSourceStack source,
      Entity firstParentEntity,
      Entity secondParentEntity) {
    RealPerson firstParent = requirePerson(source, firstParentEntity);
    RealPerson secondParent = requirePerson(source, secondParentEntity);
    if (firstParent == null || secondParent == null) {
      return 0;
    }
    if (firstParent == secondParent) {
      source.sendFailure(Component.literal("A child needs two different parents."));
      return 0;
    }
    if (firstParent.level() != secondParent.level()
        || !(firstParent.level() instanceof ServerLevel level)) {
      source.sendFailure(Component.literal("Both parents must be together in the same server dimension."));
      return 0;
    }

    source.sendSuccess(() -> Component.literal(
        "Generating a child of " + firstParent.getFullName() + " and " + secondParent.getFullName() + "..."),
        false);
    com.quzzar.kithkyn.village.Village familyVillage =
        ChildCreationService.sharedVillage(firstParent, secondParent);
    ChildCreationService.create(level, firstParent, secondParent)
        .whenComplete((attempt, error) -> {
          if (error != null) {
            Kithkyn.LOGGER.error("Child generation failed for {} and {}",
                firstParent.getFullName(), secondParent.getFullName(), error);
            source.sendFailure(Component.literal("Child generation failed; see the server log."));
            return;
          }
          if (attempt.spawned().isEmpty()) {
            source.sendFailure(Component.literal(
                "No child spawned because persona generation did not complete successfully."));
            return;
          }
          RealPerson child = attempt.spawned().get();
          String summary = "Spawned " + child.getFullName() + ", a child of "
              + firstParent.getFullName() + " and " + secondParent.getFullName()
              + (familyVillage == null ? "." : " in " + familyVillage.getName() + ".")
              + " Stats: " + describeStats(child.getStatBlock()) + ".";
          Kithkyn.LOGGER.info("[appearance child] {}", summary);
          source.sendSuccess(() -> Component.literal(summary), true);
        });
    return 1;
  }

  private static int setPigment(
      CommandSourceStack source,
      Entity entity,
      String part,
      int depth,
      int warmth) {
    RealPerson person = requirePerson(source, entity);
    if (person == null) {
      return 0;
    }
    PigmentGene replacement = PigmentGene.homozygous(depth, warmth);
    AppearanceGenes current = person.getAppearanceGenes();
    AppearanceGenes changed = switch (part.toLowerCase(Locale.ROOT)) {
      case "skin" -> new AppearanceGenes(
          current.skin(), current.hair(), current.eyes(), current.alternateEyes(),
          replacement, current.hairPigment(), current.eyePigment(), current.alternateEyePigment());
      case "hair" -> new AppearanceGenes(
          current.skin(), current.hair(), current.eyes(), current.alternateEyes(),
          current.skinPigment(), replacement, current.eyePigment(), current.alternateEyePigment());
      case "eyes" -> new AppearanceGenes(
          current.skin(), current.hair(), current.eyes(), current.alternateEyes(),
          current.skinPigment(), current.hairPigment(), replacement, current.alternateEyePigment());
      case "alternate-eyes" -> new AppearanceGenes(
          current.skin(), current.hair(), current.eyes(), current.alternateEyes(),
          current.skinPigment(), current.hairPigment(), current.eyePigment(), replacement);
      default -> null;
    };
    if (changed == null) {
      source.sendFailure(Component.literal(
          "Unknown pigment part '" + part + "'; use skin, hair, eyes, or alternate-eyes."));
      return 0;
    }
    person.setAppearanceGenes(changed);
    source.sendSuccess(() -> Component.literal(
        "Set " + person.getFullName() + " " + part + " pigment to depth " + depth
            + ", warmth/hue " + warmth + "."), true);
    return 1;
  }

  private static int setCondition(CommandSourceStack source, Entity entity, String name) {
    RealPerson person = requirePerson(source, entity);
    if (person == null) {
      return 0;
    }
    GeneticCondition condition;
    try {
      condition = GeneticCondition.valueOf(name.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      source.sendFailure(Component.literal("Unknown genetic condition: " + name));
      return 0;
    }
    person.replaceGeneticCondition(condition);
    source.sendSuccess(() -> Component.literal(
        "Set " + person.getFullName() + " condition to " + condition.name().toLowerCase(Locale.ROOT)
            + "; visual and mechanical projections were reapplied."), true);
    return 1;
  }

  private static int setLifeStage(CommandSourceStack source, Entity entity, String stageName) {
    RealPerson person = requirePerson(source, entity);
    if (person == null) {
      return 0;
    }
    AgeStage stage;
    try {
      stage = AgeStage.valueOf(stageName.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      source.sendFailure(Component.literal("Unknown life stage: " + stageName));
      return 0;
    }
    person.setLifeStage(stage);
    source.sendSuccess(() -> Component.literal(
        "Set " + person.getFullName() + " life stage to " + stage.name().toLowerCase(Locale.ROOT) + "."),
        true);
    return 1;
  }

  private static int setOccupation(CommandSourceStack source, Entity entity, String occupationName) {
    RealPerson person = requirePerson(source, entity);
    if (person == null) {
      return 0;
    }
    Occupation occupation;
    try {
      occupation = Occupation.valueOf(occupationName.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      source.sendFailure(Component.literal("Unknown occupation: " + occupationName));
      return 0;
    }
    person.setWanderingMerchant(occupation == Occupation.WANDERING_MERCHANT);
    person.setOccupation(occupation);
    person.reloadState();
    source.sendSuccess(() -> Component.literal(
        "Set " + person.getFullName() + " occupation appearance to "
            + occupation.name().toLowerCase(Locale.ROOT)
            + ". This development override does not update the village's job-assignment ledger."), true);
    return 1;
  }

  private static AppearanceInputs inputs(RealPerson person) {
    Occupation occupation = person.isWanderingMerchant()
        ? Occupation.WANDERING_MERCHANT
        : person.getOccupation();
    return new AppearanceInputs(
        person.getAppearanceSeed(),
        person.getAppearanceGenes(),
        person.getGender(),
        occupation,
        wardrobeStage(person, occupation),
        person.getGeneticCondition());
  }

  private static String describeStats(StatBlock stats) {
    List<String> values = new ArrayList<>();
    for (Stat stat : Stat.values()) {
      values.add(stat.name().toLowerCase(Locale.ROOT) + " " + stats.get(stat));
    }
    return String.join(", ", values);
  }

  private static LifeStage wardrobeStage(RealPerson person, Occupation occupation) {
    AgeStage stage = person.getLifeStage();
    boolean childWardrobe = stage.usesChildWardrobe(!occupation.isIdle());
    return childWardrobe ? LifeStage.CHILD : LifeStage.ADULT;
  }

  private static String validatePackagedLayer(AppearanceAsset asset, AppearancePart part) {
    String path = "assets/kithkyn/" + asset.texturePath(part);
    try (InputStream stream = AppearanceCommands.class.getClassLoader().getResourceAsStream(path)) {
      if (stream == null) {
        return path + " is missing";
      }
      byte[] header = stream.readNBytes(24);
      if (header.length != 24
          || (header[0] & 0xFF) != 0x89
          || header[1] != 0x50
          || header[2] != 0x4E
          || header[3] != 0x47
          || header[12] != 0x49
          || header[13] != 0x48
          || header[14] != 0x44
          || header[15] != 0x52) {
        return path + " is not a valid PNG";
      }
      int width = readBigEndianInt(header, 16);
      int height = readBigEndianInt(header, 20);
      return width == 64 && height == 64
          ? null
          : path + " is " + width + "x" + height + " instead of 64x64";
    } catch (IOException exception) {
      return path + " could not be read: " + safeMessage(exception);
    }
  }

  private static int readBigEndianInt(byte[] bytes, int offset) {
    return (bytes[offset] & 0xFF) << 24
        | (bytes[offset + 1] & 0xFF) << 16
        | (bytes[offset + 2] & 0xFF) << 8
        | bytes[offset + 3] & 0xFF;
  }

  private static synchronized AppearanceCatalog catalog() throws IOException {
    if (builtInCatalog != null) {
      return builtInCatalog;
    }
    InputStream stream = AppearanceCommands.class.getClassLoader().getResourceAsStream(CATALOG_RESOURCE);
    if (stream == null) {
      throw new IOException("Missing " + CATALOG_RESOURCE);
    }
    try (stream; InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      builtInCatalog = AppearanceCatalog.load(reader);
    }
    return builtInCatalog;
  }

  private static RealPerson requirePerson(CommandSourceStack source, Entity entity) {
    if (entity instanceof RealPerson person) {
      return person;
    }
    source.sendFailure(Component.literal(entity.getName().getString() + " is not a kithkyn person."));
    return null;
  }

  private static String describe(AppearanceCatalog catalog, String assetId) {
    AppearanceAsset asset = catalog.asset(assetId);
    return asset.label() + " [" + assetId + "]";
  }

  private static String pigmentDescription(String label, PigmentGene gene, PigmentColor colors) {
    return String.format(
        Locale.ROOT,
        "%s pigment: depth %.1f%% [%d/%d], warmth/hue %.1f%% [%d/%d], colors #%06X/#%06X",
        label,
        gene.depth() * 100.0F,
        gene.firstDepth(),
        gene.secondDepth(),
        gene.warmth() * 100.0F,
        gene.firstWarmth(),
        gene.secondWarmth(),
        colors.baseRgb(),
        colors.shadowRgb());
  }

  private static String colorPair(PigmentColor colors) {
    return String.format(Locale.ROOT, "#%06X/#%06X", colors.baseRgb(), colors.shadowRgb());
  }

  private static int reportFailure(CommandSourceStack source, String message, Exception exception) {
    Kithkyn.LOGGER.error(message, exception);
    source.sendFailure(Component.literal(message + ": " + safeMessage(exception)));
    return 0;
  }

  private static String safeMessage(Throwable throwable) {
    return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
  }

  private static void addExample(List<String> examples, String example) {
    if (examples.size() < MAXIMUM_FAILURE_EXAMPLES) {
      examples.add(example);
    }
  }

  private record SystemAuditResult(
      int assetCount,
      int layerCount,
      int recipeCount,
      long durationMillis,
      int failureCount,
      List<String> failureExamples) {
  }

  private static final class AuditFailures {

    private int count;
    private final List<String> examples = new ArrayList<>();

    void add(String failure) {
      count++;
      addExample(examples, failure);
    }

    int count() {
      return count;
    }

    List<String> examples() {
      return List.copyOf(examples);
    }
  }
}
