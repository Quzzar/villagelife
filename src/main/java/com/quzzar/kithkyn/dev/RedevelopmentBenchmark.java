package com.quzzar.kithkyn.dev;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.gson.GsonBuilder;
import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.PersonEntityType;
import com.quzzar.kithkyn.configuration.KithkynConfig;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.llm.LlmService;
import com.quzzar.kithkyn.persona.PersonaData;
import com.quzzar.kithkyn.persona.PersonaService;
import com.quzzar.kithkyn.village.Occupation;
import com.quzzar.kithkyn.village.Village;
import com.quzzar.kithkyn.village.VillageManager;
import com.quzzar.kithkyn.village.buildings.MaterialAmount;
import com.quzzar.kithkyn.village.buildings.Building;
import com.quzzar.kithkyn.village.buildings.Buildings;
import com.quzzar.kithkyn.village.buildings.RedevelopmentPlanner;
import com.quzzar.kithkyn.village.buildings.VillageStyle;
import com.quzzar.kithkyn.village.buildings.VillageContextSnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Explicit JVM-only development harness; normal games never enter these paths. */
@EventBusSubscriber(modid = Kithkyn.MODID)
public final class RedevelopmentBenchmark {
  private static final String MODE = System.getProperty("kithkyn.redevelopment.benchmark", "");
  private static final String WAIT = "nothing yet: keep what we have and wait";
  private static final String ORDINARY = "build level 1 house on open land";
  private static final long HORIZON = Long.getLong("kithkyn.redevelopment.days", 5L) * 24_000L;
  private static long started = -1;
  private static long modelWaitStarted;
  private static boolean awaitingModel;
  private static final List<Village> waitingVillages = new ArrayList<>();
  private static long lastDay = -1;
  private static long lastCompletion;
  private static int builds;
  private static final List<Observation> observations = new ArrayList<>();

  private RedevelopmentBenchmark() {
  }

  @SubscribeEvent
  public static void started(ServerStartedEvent event) {
    MinecraftServer server = event.getServer();
    if (MODE.isEmpty()) {
      return;
    }
    try {
      if (MODE.equals("seed")) {
        seed(server.overworld());
        server.halt(false);
      } else if (MODE.equals("seed-opportunity") || MODE.equals("seed-growth")) {
        seedAutonomous(server.overworld(), MODE.equals("seed-opportunity"));
        server.halt(false);
      } else if (MODE.equals("model")) {
        Thread worker = new Thread(() -> modelTrials(server), "redevelopment-model-trials");
        worker.setDaemon(true);
        worker.start();
      } else if (MODE.equals("timelapse")) {
        KithkynConfig.RedevelopmentEnabled = Boolean.getBoolean("kithkyn.redevelopment.enabled");
        waitingVillages.addAll(VillageManager.get(server.overworld()).getVillages().values());
        VillageManager.get(server.overworld()).getVillages().clear();
        awaitingModel = true;
        modelWaitStarted = System.nanoTime();
        server.tickRateManager().setFrozen(true);
        Kithkyn.LOGGER.info("[redevelopment-benchmark] waiting for the model before advancing simulation");
      }
    } catch (Exception failure) {
      Kithkyn.LOGGER.error("[redevelopment-benchmark] FAIL during setup", failure);
      server.halt(false);
    }
  }

  private static void seed(ServerLevel level) {
    var fixture = RedevelopmentVerification.fixture(level);
    Village village = fixture.village();
    BlockPos ground = fixture.ground();
    RedevelopmentVerification.place(village, "mine_plains_1", ground.offset(-30, 0, 30));
    RedevelopmentVerification.place(village, "lumberjack_plains_1", ground.offset(25, 0, 25));
    VillageManager.get(level).getVillages().put(village.getID(), village);
    VillageManager.get(level).setDirty();
    level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
    level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level.getServer());
    Occupation[] jobs = {Occupation.BUILDER, Occupation.FARMER, Occupation.QUARTERMASTER,
        Occupation.MINER, Occupation.LUMBERJACK, Occupation.WANDERER};
    for (int index = 0; index < jobs.length; index++) {
      RealPerson person = spawnFixtureResident(level, village, jobs[index], index);
      Occupation occupation = jobs[index];
      if (occupation != Occupation.WANDERER) {
        village.assignJob(person.getUUID(), village.getUnassignedJobs().stream()
            .filter(job -> job.getOccupation() == occupation)
            .filter(job -> occupation != Occupation.FARMER
                || job.getBuildingUUID().equals(fixture.survivingFarm().getUUID()))
            .findFirst().orElseThrow());
      }
    }
    for (var item : List.of(Items.OAK_LOG, Items.COBBLESTONE, Items.DIRT, Items.COOKED_BEEF)) {
      for (int stack = 0; stack < 6; stack++) {
        if (!village.storeAwayFrom(new ItemStack(item, 64), List.of()).isEmpty()) {
          throw new IllegalStateException("Fixture storage cannot hold initial supplies");
        }
      }
    }
    Kithkyn.LOGGER.info("[redevelopment-benchmark] seed population={} buildings={} stock={}",
        village.getPopulation().size(), village.getBuildings().size(), village.stockTally());
  }

  /** Seeds facts once; subsequent planning, material gathering and construction remain autonomous. */
  private static void seedAutonomous(ServerLevel level, boolean opportunity) throws java.io.IOException {
    level.getChunk(8, 8);
    BlockPos ground = new BlockPos(128,
        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 128, 128), 128);
    if (!level.getFluidState(ground.below()).isEmpty()) {
      throw new IllegalStateException("Founding fixture must start on dry land");
    }
    Village village = new Village(opportunity ? "Willowfield opportunity" : "New growth");
    village.attach(level);
    village.setStyle(VillageStyle.PLAINS);
    village.initNew(ground);
    if (village.getTownCenter() == null) {
      throw new IllegalStateException("Normal founding did not create a center");
    }
    VillageManager.get(level).getVillages().put(village.getID(), village);
    VillageManager.get(level).setDirty();
    level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
    level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level.getServer());
    if (opportunity) {
      RedevelopmentVerification.place(village, "house_plains_3", ground.offset(-30, 0, 25));
      RedevelopmentVerification.place(village, "lumberjack_plains_1", ground.offset(-30, 0, -25));
      BlockPos field = ground.offset(30, 0, 0);
      Building source = RedevelopmentVerification.place(village, "farm_plains_1", field);
      RedevelopmentVerification.place(village, "farm_plains_2", ground.offset(30, 0, 25));
      RedevelopmentVerification.place(village, "well_plains_1", field.offset(-5, 0, 0));
      RedevelopmentVerification.place(village, "well_plains_1", field.offset(8, 0, 0));
      Occupation[] jobs = {Occupation.BUILDER, Occupation.FARMER, Occupation.FARMER,
          Occupation.QUARTERMASTER, Occupation.MINER, Occupation.LUMBERJACK,
          Occupation.WANDERER, Occupation.WANDERER};
      for (int index = 0; index < jobs.length; index++) {
        RealPerson person = spawnFixtureResident(level, village, jobs[index], index);
        if (jobs[index] != Occupation.WANDERER) {
          Occupation occupation = jobs[index];
          village.assignJob(person.getUUID(), village.getUnassignedJobs().stream()
              .filter(job -> job.getOccupation() == occupation).findFirst().orElseThrow());
        }
      }
      for (var item : List.of(Items.OAK_LOG, Items.COBBLESTONE, Items.DIRT)) {
        for (int stack = 0; stack < 6; stack++) {
          if (!village.storeAwayFrom(new ItemStack(item, 64), List.of()).isEmpty()) {
            throw new IllegalStateException("Opportunity storage cannot hold the construction budget");
          }
        }
      }
      if (!village.storeAwayFrom(new ItemStack(Items.COOKED_BEEF, 16), List.of()).isEmpty()) {
        throw new IllegalStateException("Opportunity storage cannot hold the initial food");
      }
      // This is an established camp; its completed defense and age are initial conditions.
      village.devBuildWall(com.quzzar.kithkyn.village.buildings.WallTier.STONE);
      level.getServer().getWorldData().overworldData().setGameTime(144_000L);
      Kithkyn.LOGGER.info("[redevelopment-benchmark] opportunity preflight food={} freshFood={} target={} vacancies={} netPlots={} stock={}",
          village.getAttractiveness().foodPerCapita(), village.computeAttractiveness().foodPerCapita(),
          KithkynConfig.AttractivenessFoodTargetPerCapita, village.claimableJobs().stream().map(job -> job.getOccupation()).toList(),
          com.quzzar.kithkyn.village.buildings.RedevelopmentDemand.netFoodPlots(village,
              Buildings.getByName("farm_plains_2"), List.of(source)), village.stockTally());
      var assessment = RedevelopmentPlanner.assess(village, Buildings.getByName("farm_plains_2"),
          source, field, Rotation.NONE);
      var plan = assessment.plan().orElseThrow(() -> new IllegalStateException(
          "Opportunity is not feasible: " + assessment.reason()));
      write("redevelopment-initial-opportunity.json", List.of(RedevelopmentPlanner.label(plan),
          RedevelopmentPlanner.describe(village, plan)));
      Kithkyn.LOGGER.info("[redevelopment-benchmark] prepared opportunity: {}",
          RedevelopmentPlanner.describe(village, plan));
    }
    write("redevelopment-initial-state.json", VillageContextSnapshot.capture(village,
        village.stockTally()).plannerBriefing());
    Kithkyn.LOGGER.info("[redevelopment-benchmark] seeded scenario={} population={} buildings={}",
        opportunity ? "opportunity" : "growth", village.getPopulation().size(), village.getBuildings().size());
  }

  private static RealPerson spawnFixtureResident(ServerLevel level, Village village, Occupation occupation, int index) {
    RealPerson person = PersonEntityType.PERSON.get().create(level);
    if (person == null) {
      throw new IllegalStateException("Could not create fixture resident");
    }
    BlockPos fire = village.getGatheringPoint();
    person.moveTo(fire.getX() + index % 3, fire.getY(), fire.getZ() + index / 3, 0, 0);
    person.finalizeSpawn(level, level.getCurrentDifficultyAt(fire), MobSpawnType.COMMAND, null);
    PersonaService.attach(person, new PersonaData("A practical resident who wants a well-fed, comfortable village.",
        "Checks supplies before starting work.", "fixed benchmark fixture", 0, 1));
    person.setVillage(village.getID());
    person.setVillageName(village.getName());
    person.setOccupation(occupation);
    level.addFreshEntity(person);
    village.getPopulation().add(person.getUUID());
    return person;
  }

  private record Case(String name, String situation, List<String> options, String expected) {
  }

  private record Trial(String scenario, int repeat, int rotation, String expected, String action,
      boolean valid, boolean matched, String reason, long milliseconds, int situationChars) {
  }

  private static void modelTrials(MinecraftServer server) {
    try {
      long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
      while (!LlmService.get().isReady() && System.nanoTime() < deadline) {
        Thread.sleep(200);
      }
      if (!LlmService.get().isReady()) {
        throw new IllegalStateException("Default local model did not become ready");
      }
      List<String> proposal = Files.readAllLines(Path.of("redevelopment-proposal.txt"));
      String action = proposal.getFirst();
      String facts = proposal.get(1);
      List<Case> cases = new ArrayList<>(List.of(
          new Case("useful_blocked", "The village needs one additional bed. Every separate house site has failed its terrain survey. "
              + "The remaining staffed farm supplies the current population; food stocks are ample. "
              + "All net construction materials are in storage. The only feasible building option is below.",
              List.of(action, WAIT), action),
          new Case("cheap_open_land", "The village needs one additional bed. An ordinary level 1 house fits on open land, "
              + "adds one bed, removes nothing, and costs 14 logs and 19 cobblestone. Both choices are fully affordable.",
              List.of(action, ORDINARY, WAIT), ORDINARY),
          new Case("no_current_need", "The village has twelve empty beds, ample storage, and all workplaces staffed. "
              + "There are no requests for buildings. Current materials must last until the next delivery; "
              + "no additional housing is currently needed.", List.of(action, WAIT), WAIT),
          new Case("salvage_affordable", "The village needs one additional bed. Every separate house site failed its terrain survey. "
              + "The net materials listed below are all in storage. Without salvage the upgrade is unaffordable. "
              + "The game has verified that salvage plus these secured materials completes the entire project. "
              + "The surviving staffed farm supplies the population and food stocks are ample.", List.of(action, WAIT), action)));
      if (proposal.size() >= 4) {
        String storage = proposal.get(2);
        String ordinaryStore = "build a small storehouse on open land";
        cases.addAll(List.of(
            new Case("heldout_storage_blocked", "Current storage is full and deliveries are being lost. No separate storehouse site fits. "
                + "The proposed redevelopment adds sufficient storage. All remaining materials are secured, affected contents can be evacuated, "
                + "and food output and homes remain sufficient.", List.of(storage, WAIT), storage),
            new Case("heldout_storage_open_land", "One extra shared container is needed. A small storehouse on open land meets the whole "
                + "shortage, removes nothing and costs fewer materials than redevelopment. Both projects are affordable.",
                List.of(storage, ordinaryStore, WAIT), ordinaryStore),
            new Case("heldout_storage_no_need", "Half of the village's shared containers are empty. Deliveries all fit. "
                + "There is no need or request for extra storage, jobs or beds. The village has limited spare materials.",
                List.of(storage, WAIT), WAIT)));
      }
      List<Trial> trials = new ArrayList<>();
      for (Case scenario : cases) {
        for (int repeat = 0; repeat < 3; repeat++) {
          for (int rotation = 0; rotation < scenario.options().size(); rotation++) {
            List<String> options = new ArrayList<>(scenario.options());
            Collections.rotate(options, rotation);
            String redevelopment = scenario.name().startsWith("heldout_") ? proposal.get(2) : action;
            String proposalFacts = scenario.name().startsWith("heldout_") ? proposal.get(3) : facts;
            String situation = scenario.situation() + "\nOption " + (options.indexOf(redevelopment) + 1) + " facts: " + proposalFacts;
            long start = System.nanoTime();
            var answer = LlmService.get().decideStrict("redevelopment benchmark " + scenario.name(), situation, options)
                .get(90, TimeUnit.SECONDS);
            Trial trial = new Trial(scenario.name(), repeat, rotation, scenario.expected(),
                answer.map(value -> value.choice()).orElse(""), answer.isPresent(),
                answer.map(value -> value.choice().equals(scenario.expected())).orElse(false),
                answer.map(value -> value.reason()).orElse(""), (System.nanoTime() - start) / 1_000_000,
                situation.length());
            trials.add(trial);
            write("redevelopment-model-results.json", trials);
            Kithkyn.LOGGER.info("[redevelopment-model] {}", trial);
          }
        }
      }
      Kithkyn.LOGGER.info("[redevelopment-model] COMPLETE trials={} valid={} matched={}", trials.size(),
          trials.stream().filter(Trial::valid).count(), trials.stream().filter(Trial::matched).count());
    } catch (Exception failure) {
      Kithkyn.LOGGER.error("[redevelopment-model] FAIL", failure);
    } finally {
      server.execute(() -> server.halt(false));
    }
  }

  private record BuildingObservation(String id, String name, long origin) {
  }

  private record Observation(long elapsedTicks, int builds, int population, int buildings, String project,
      String stock, String metrics, String briefing, List<BuildingObservation> standing,
      int unhousedAdults, int staffedFoodPosts, double foodPerPerson, String siteBlocker) {
  }

  @SubscribeEvent
  public static void tick(ServerTickEvent.Post event) {
    if (!MODE.equals("timelapse")) {
      return;
    }
    MinecraftServer server = event.getServer();
    if (awaitingModel) {
      if (!LlmService.get().isReady()) {
        if (System.nanoTime() - modelWaitStarted > TimeUnit.MINUTES.toNanos(5)) {
          awaitingModel = false;
          restoreWaitingVillages(server);
          Kithkyn.LOGGER.error("[redevelopment-benchmark] FAIL: model startup timed out");
          server.halt(false);
        }
        return;
      }
      awaitingModel = false;
      restoreWaitingVillages(server);
      started = server.overworld().getGameTime();
      lastCompletion = VillageManager.get(server.overworld()).getVillages().values().stream()
          .mapToLong(Village::getLastBuildCompletedTime).max().orElse(0L);
      server.tickRateManager().setFrozen(false);
      Kithkyn.LOGGER.info("[redevelopment-benchmark] begin enabled={} horizonTicks={}",
          KithkynConfig.RedevelopmentEnabled, HORIZON);
    }
    if (started < 0) {
      return;
    }
    Village village = VillageManager.get(server.overworld()).getVillages().values().stream().findFirst().orElse(null);
    if (village == null) {
      Kithkyn.LOGGER.error("[redevelopment-benchmark] FAIL: fixture village missing");
      server.halt(false);
      started = -1;
      return;
    }
    long elapsed = server.overworld().getGameTime() - started;
    if (village.getLastBuildCompletedTime() > lastCompletion) {
      lastCompletion = village.getLastBuildCompletedTime();
      builds++;
    }
    if (elapsed / 24_000 != lastDay || elapsed >= HORIZON) {
      lastDay = elapsed / 24_000;
      var project = village.getCurrentProject();
      var strategy = village.getBrain().getStrategy();
      String metrics = strategy.getAllKeys().stream().filter(key -> key.startsWith("redevelopment_"))
          .sorted().map(key -> key + "=" + strategy.get(key)).collect(java.util.stream.Collectors.joining(";"));
      Observation observation = new Observation(elapsed, builds, village.getPopulation().size(),
          village.getBuildings().size(), project == null ? "none" : project.getBuilding().getName() + ":" + project.getProgress(),
          MaterialAmount.describe(MaterialAmount.fromStacks(village.stockTally().entrySet().stream()
              .map(entry -> new ItemStack(entry.getKey(), entry.getValue())).toList())), metrics,
          VillageContextSnapshot.capture(village, village.stockTally()).plannerBriefing(),
          village.getBuildings().stream().map(building -> new BuildingObservation(building.getUUID().toString(),
              building.getName(), building.getOriginLocation())).sorted(java.util.Comparator.comparing(BuildingObservation::id)).toList(),
          village.getUnhousedAdultResidentCount(), (int) village.getJobAssignmentsView().values().stream()
              .filter(job -> List.of(Occupation.FARMER, Occupation.FISHER, Occupation.HUNTER).contains(job.getOccupation())).count(),
          village.getAttractiveness().foodPerCapita(), project == null ? "" : project.siteBlocker());
      observations.add(observation);
      try {
        write("redevelopment-timelapse-results.json", observations);
      } catch (java.io.IOException failure) {
        throw new IllegalStateException("Cannot write benchmark results", failure);
      }
      Kithkyn.LOGGER.info("[redevelopment-benchmark] {}", observation);
    }
    if (elapsed >= HORIZON) {
      server.tickRateManager().stopSprinting();
      started = -1;
      Kithkyn.LOGGER.info("[redevelopment-benchmark] COMPLETE enabled={} elapsed={} builds={}",
          KithkynConfig.RedevelopmentEnabled, elapsed, builds);
      server.halt(false);
    } else if (!LlmService.get().isReady() || village.hasPendingBrainDecision()) {
      server.tickRateManager().stopSprinting();
    } else if (!server.tickRateManager().isSprinting()) {
      server.tickRateManager().requestGameToSprint((int) Math.min(24_000L, HORIZON - elapsed));
    }
  }

  @SubscribeEvent
  public static void stopping(ServerStoppingEvent event) {
    if (!waitingVillages.isEmpty()) {
      restoreWaitingVillages(event.getServer());
    }
  }

  /** Minecraft freezing does not pause the mod's server-event bookkeeping. */
  private static void restoreWaitingVillages(MinecraftServer server) {
    var manager = VillageManager.get(server.overworld());
    for (Village village : waitingVillages) {
      manager.getVillages().put(village.getID(), village);
    }
    waitingVillages.clear();
    manager.setDirty();
  }

  private static void write(String path, Object value) throws java.io.IOException {
    Files.writeString(Path.of(path), new GsonBuilder().setPrettyPrinting().create().toJson(value));
  }
}
