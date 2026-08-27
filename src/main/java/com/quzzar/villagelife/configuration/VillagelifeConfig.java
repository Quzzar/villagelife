package com.quzzar.villagelife.configuration;

import java.util.ArrayList;
import java.util.List;

import com.quzzar.villagelife.Villagelife;

import org.apache.commons.lang3.tuple.Pair;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;

@EventBusSubscriber(modid = Villagelife.MODID, bus = EventBusSubscriber.Bus.MOD)
public class VillagelifeConfig {
    public static final ModConfigSpec COMMON_SPEC;
    public static final CommonConfig COMMON;
    static {
        {
            final Pair<CommonConfig, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(CommonConfig::new);
            COMMON = specPair.getLeft();
            COMMON_SPEC = specPair.getRight();
        }
    }

    public static boolean RaidAnimals;
    public static boolean AttackAllMobs;
    public static boolean FriendlyFire;
    public static double GuardVillagerHelpRange;
    public static List<? extends String> MobBlackList;
    public static int DaysInYear;
    public static boolean LlmEnabled;
    public static String LlmModel;
    public static int LlmMaxNewTokens;
    public static double LlmTemperature;
    public static int LlmWorkerHeapMb;
    public static String LlmProviderName;
    public static String LlmApiKey;
    public static String LlmCloudModel;
    public static String LlmLocalUrl;

    public static double AttractivenessBase;
    public static double AttractivenessFoodMax;
    public static double AttractivenessFoodTargetPerCapita;
    public static double AttractivenessFreeBedsMax;
    public static double AttractivenessFreeBedsTarget;
    public static double AttractivenessHomelessMax;
    public static double AttractivenessDeathWeight;
    public static double AttractivenessHurtWeight;
    public static double AttractivenessShortageWeight;
    public static double AttractivenessTheftWeight;
    public static int StandingHostileBelow;
    public static int StandingShunnedBelow;
    public static int StandingUnwelcomeBelow;
    public static int StandingDislikedBelow;
    public static int StandingTrustedAbove;
    public static double StandingWorstMarkup;
    public static double AttractivenessArriveThreshold;
    public static double AttractivenessEmigrateThreshold;
    public static int ShortageEventCooldownSeconds;
    public static int PopulationCheckIntervalSeconds;
    public static int ArrivalEdgeMinDistance;
    public static int TravelTimeoutSeconds;
    public static int IdleCapFallback;
    public static int WandererRecruitRadius;
    public static int WandererCap;
    public static int BuildOptionsOffered;
    public static double JobSwapThreshold;
    public static int JobSwapIntervalSeconds;
    public static double JobSwapCooldownDays;
    public static double BankSpread;
    public static boolean DeveloperCommands;

    public static void bakeCommonConfig() {
        RaidAnimals = COMMON.RaidAnimals.get();
        AttackAllMobs = COMMON.AttackAllMobs.get();
        FriendlyFire = COMMON.FriendlyFire.get();
        MobBlackList = COMMON.MobBlackList.get();
        GuardVillagerHelpRange = COMMON.GuardVillagerHelpRange.get();
        DaysInYear = COMMON.DaysInYear.get();
        LlmEnabled = COMMON.LlmEnabled.get();
        LlmModel = COMMON.LlmModel.get();
        LlmMaxNewTokens = COMMON.LlmMaxNewTokens.get();
        LlmTemperature = COMMON.LlmTemperature.get();
        LlmWorkerHeapMb = COMMON.LlmWorkerHeapMb.get();
        LlmProviderName = COMMON.LlmProviderName.get();
        LlmApiKey = COMMON.LlmApiKey.get();
        LlmCloudModel = COMMON.LlmCloudModel.get();
        LlmLocalUrl = COMMON.LlmLocalUrl.get();
        AttractivenessBase = COMMON.AttractivenessBase.get();
        AttractivenessFoodMax = COMMON.AttractivenessFoodMax.get();
        AttractivenessFoodTargetPerCapita = COMMON.AttractivenessFoodTargetPerCapita.get();
        AttractivenessFreeBedsMax = COMMON.AttractivenessFreeBedsMax.get();
        AttractivenessFreeBedsTarget = COMMON.AttractivenessFreeBedsTarget.get();
        AttractivenessHomelessMax = COMMON.AttractivenessHomelessMax.get();
        AttractivenessDeathWeight = COMMON.AttractivenessDeathWeight.get();
        AttractivenessHurtWeight = COMMON.AttractivenessHurtWeight.get();
        AttractivenessShortageWeight = COMMON.AttractivenessShortageWeight.get();
        AttractivenessTheftWeight = COMMON.AttractivenessTheftWeight.get();
        StandingHostileBelow = COMMON.StandingHostileBelow.get();
        StandingShunnedBelow = COMMON.StandingShunnedBelow.get();
        StandingUnwelcomeBelow = COMMON.StandingUnwelcomeBelow.get();
        StandingDislikedBelow = COMMON.StandingDislikedBelow.get();
        StandingTrustedAbove = COMMON.StandingTrustedAbove.get();
        StandingWorstMarkup = COMMON.StandingWorstMarkup.get();
        AttractivenessArriveThreshold = COMMON.AttractivenessArriveThreshold.get();
        AttractivenessEmigrateThreshold = COMMON.AttractivenessEmigrateThreshold.get();
        ShortageEventCooldownSeconds = COMMON.ShortageEventCooldownSeconds.get();
        PopulationCheckIntervalSeconds = COMMON.PopulationCheckIntervalSeconds.get();
        ArrivalEdgeMinDistance = COMMON.ArrivalEdgeMinDistance.get();
        TravelTimeoutSeconds = COMMON.TravelTimeoutSeconds.get();
        IdleCapFallback = COMMON.IdleCapFallback.get();
        WandererRecruitRadius = COMMON.WandererRecruitRadius.get();
        WandererCap = COMMON.WandererCap.get();
        BuildOptionsOffered = COMMON.BuildOptionsOffered.get();
        JobSwapThreshold = COMMON.JobSwapThreshold.get();
        JobSwapIntervalSeconds = COMMON.JobSwapIntervalSeconds.get();
        JobSwapCooldownDays = COMMON.JobSwapCooldownDays.get();
        BankSpread = COMMON.BankSpread.get();
        DeveloperCommands = COMMON.DeveloperCommands.get();
    }

    @SubscribeEvent
    public static void onModConfigEvent(final ModConfigEvent configEvent) {
        if (configEvent instanceof ModConfigEvent.Unloading) {
            return;
        }
        if (configEvent.getConfig().getSpec() == VillagelifeConfig.COMMON_SPEC) {
            bakeCommonConfig();
        }
    }

    public static class CommonConfig {
        public final ModConfigSpec.BooleanValue RaidAnimals;
        public final ModConfigSpec.BooleanValue AttackAllMobs;
        public final ModConfigSpec.BooleanValue FriendlyFire;
        public final ModConfigSpec.DoubleValue GuardVillagerHelpRange;
        public final ModConfigSpec.ConfigValue<List<? extends String>> MobBlackList;
        public final ModConfigSpec.IntValue DaysInYear;
        public final ModConfigSpec.BooleanValue LlmEnabled;
        public final ModConfigSpec.ConfigValue<String> LlmModel;
        public final ModConfigSpec.ConfigValue<String> LlmLocalUrl;
        public final ModConfigSpec.IntValue LlmMaxNewTokens;
        public final ModConfigSpec.DoubleValue LlmTemperature;
        public final ModConfigSpec.IntValue LlmWorkerHeapMb;
        public final ModConfigSpec.ConfigValue<String> LlmProviderName;
        public final ModConfigSpec.ConfigValue<String> LlmApiKey;
        public final ModConfigSpec.ConfigValue<String> LlmCloudModel;

        public final ModConfigSpec.DoubleValue AttractivenessBase;
        public final ModConfigSpec.DoubleValue AttractivenessFoodMax;
        public final ModConfigSpec.DoubleValue AttractivenessFoodTargetPerCapita;
        public final ModConfigSpec.DoubleValue AttractivenessFreeBedsMax;
        public final ModConfigSpec.DoubleValue AttractivenessFreeBedsTarget;
        public final ModConfigSpec.DoubleValue AttractivenessHomelessMax;
        public final ModConfigSpec.DoubleValue AttractivenessDeathWeight;
        public final ModConfigSpec.DoubleValue AttractivenessHurtWeight;
        public final ModConfigSpec.DoubleValue AttractivenessShortageWeight;
        public final ModConfigSpec.DoubleValue AttractivenessTheftWeight;
        public final ModConfigSpec.IntValue StandingHostileBelow;
        public final ModConfigSpec.IntValue StandingShunnedBelow;
        public final ModConfigSpec.IntValue StandingUnwelcomeBelow;
        public final ModConfigSpec.IntValue StandingDislikedBelow;
        public final ModConfigSpec.IntValue StandingTrustedAbove;
        public final ModConfigSpec.DoubleValue StandingWorstMarkup;
        public final ModConfigSpec.DoubleValue AttractivenessArriveThreshold;
        public final ModConfigSpec.DoubleValue AttractivenessEmigrateThreshold;
        public final ModConfigSpec.IntValue ShortageEventCooldownSeconds;
        public final ModConfigSpec.IntValue PopulationCheckIntervalSeconds;
        public final ModConfigSpec.IntValue ArrivalEdgeMinDistance;
        public final ModConfigSpec.IntValue TravelTimeoutSeconds;
        public final ModConfigSpec.IntValue IdleCapFallback;
        public final ModConfigSpec.IntValue WandererRecruitRadius;
        public final ModConfigSpec.IntValue WandererCap;
        public final ModConfigSpec.IntValue BuildOptionsOffered;
        public final ModConfigSpec.DoubleValue JobSwapThreshold;
        public final ModConfigSpec.IntValue JobSwapIntervalSeconds;
        public final ModConfigSpec.DoubleValue JobSwapCooldownDays;
        public final ModConfigSpec.DoubleValue BankSpread;
        public final ModConfigSpec.BooleanValue DeveloperCommands;

        public CommonConfig(ModConfigSpec.Builder builder) {
            RaidAnimals = builder.comment("Illagers In Raids Attack Animals?").translation(Villagelife.MODID + ".config.RaidAnimals").define("Illagers in raids attack animals?", false);
            AttackAllMobs = builder.comment("Guards will attack all hostiles with this option").translation(Villagelife.MODID + ".config.AttackAllMobs").define("Guards attack all mobs?", false);
            MobBlackList = builder.comment("Guards won't attack mobs in this list if AttackAllMobs is enabled, for example, putting minecraft:creeper in this list will make guards ignore creepers.").defineListAllowEmpty("MobBlackList", ArrayList::new, () -> "", o -> o instanceof String);
            FriendlyFire = builder.comment("This will make guards attempt to avoid friendly fire.").translation(Villagelife.MODID + ".config.FriendlyFire").define("Have guards attempt to avoid firing into other friendlies? (Experimental)", false);
            GuardVillagerHelpRange = builder.translation(Villagelife.MODID + ".config.range").comment("This is the range in which the guards will be aggroed to mobs that are attacking villagers. Higher values are more resource intensive, and setting this to zero will disable the goal.")
                    .defineInRange("Range", 50.0D, -500.0D, 500.0D);

            DaysInYear = builder.comment("Days in one Minecraft year (there are 8 days in one full lunar cycle).").translation(Villagelife.MODID + ".config.DaysInYear").defineInRange("Days in Year", 96, 8, 79992);

            LlmEnabled = builder.comment("Enable the local LLM that helps villagers make decisions. The model (a few hundred MB) is downloaded from HuggingFace on first server start and cached in <game dir>/villagelife/models. Runs in its own small worker process, so no launcher setup is needed; budget roughly 1.5GB of RAM beyond the game's needs (on small hosted servers, pick the 0.5B model or disable this). Check /vlbrain status in-game.").translation(Villagelife.MODID + ".config.LlmEnabled").define("Enable villager LLM?", true);
            LlmModel = builder.comment("Offline model for the jlama provider (any Jlama-compatible JQ4 HuggingFace id works). tjake/Llama-3.2-1B-Instruct-JQ4 is the default and the only actively tested model. Untested alternates some players use: tjake/granite-3.0-2b-instruct-JQ4 (~1.4GB) and tjake/gemma-2-2b-it-JQ4 (~1.6GB) — bigger and slower, not routinely verified. Downloaded on first server start.").translation(Villagelife.MODID + ".config.LlmModel").define("LLM model id", "tjake/Llama-3.2-1B-Instruct-JQ4");
            LlmMaxNewTokens = builder.comment("Maximum number of tokens the LLM may generate per decision.").translation(Villagelife.MODID + ".config.LlmMaxNewTokens").defineInRange("LLM max new tokens", 96, 16, 1024);
            LlmTemperature = builder.comment("LLM sampling temperature. 0.0 is deterministic (recommended for decisions); higher values are more random.").translation(Villagelife.MODID + ".config.LlmTemperature").defineInRange("LLM temperature", 0.0D, 0.0D, 2.0D);
            LlmWorkerHeapMb = builder.comment("Max heap (MB) for the LLM worker process. The model weights live outside this heap, so the worker's total memory is roughly this value plus the model size. Lower it on memory-constrained hosts; big offline models (7B) need well above the default. Only used by the jlama provider.").translation(Villagelife.MODID + ".config.LlmWorkerHeapMb").defineInRange("LLM worker heap MB", 1024, 256, 16384);
            LlmLocalUrl = builder.comment("Where the 'local' provider finds a runtime on this machine speaking the OpenAI protocol: llama.cpp's server, Ollama (http://localhost:11434), LM Studio, vLLM. Several times faster than the built-in offline model and will run anything in its own format. No API key needed.").translation(Villagelife.MODID + ".config.LlmLocalUrl").define("LLM local URL", "http://localhost:8080");
            LlmProviderName = builder.comment("Which LLM answers for the villagers: 'jlama' runs the offline model above on this machine (default, no account needed); 'local' talks to a faster runtime already running on this machine (see LLM local URL); 'claude', 'openai', or 'deepseek' use that cloud service with your API key. Changing this requires a game restart.").translation(Villagelife.MODID + ".config.LlmProvider").define("LLM provider", "jlama");
            LlmApiKey = builder.comment("Your API key for the chosen cloud provider (unused by jlama). Paste it here in plain text and TREAT THIS FILE LIKE A PASSWORD: anyone with this file can spend your account's money.").translation(Villagelife.MODID + ".config.LlmApiKey").define("LLM API key", "");
            LlmCloudModel = builder.comment("Model id for the cloud provider (unused by jlama). Leave empty for the provider's default (Claude: claude-haiku-4-5, OpenAI: gpt-5.6-luna, DeepSeek: deepseek-chat).").translation(Villagelife.MODID + ".config.LlmCloudModel").define("LLM cloud model", "");

            AttractivenessBase = builder.comment("Attractiveness starting value before any inputs apply.").translation(Villagelife.MODID + ".config.AttractivenessBase").defineInRange("Attractiveness base", 50.0D, 0.0D, 100.0D);
            AttractivenessFoodMax = builder.comment("Maximum attractiveness bonus from stocked food.").translation(Villagelife.MODID + ".config.AttractivenessFoodMax").defineInRange("Attractiveness food max", 25.0D, 0.0D, 100.0D);
            AttractivenessFoodTargetPerCapita = builder.comment("Edible items per villager at which the food bonus reaches its maximum.").translation(Villagelife.MODID + ".config.AttractivenessFoodTargetPerCapita").defineInRange("Attractiveness food target per capita", 8.0D, 0.1D, 1000.0D);
            AttractivenessFreeBedsMax = builder.comment("Maximum attractiveness bonus from free-bed headroom.").translation(Villagelife.MODID + ".config.AttractivenessFreeBedsMax").defineInRange("Attractiveness free beds max", 10.0D, 0.0D, 100.0D);
            AttractivenessFreeBedsTarget = builder.comment("Number of free beds at which the headroom bonus reaches its maximum.").translation(Villagelife.MODID + ".config.AttractivenessFreeBedsTarget").defineInRange("Attractiveness free beds target", 2.0D, 1.0D, 100.0D);
            AttractivenessHomelessMax = builder.comment("Maximum attractiveness penalty when every villager is homeless.").translation(Villagelife.MODID + ".config.AttractivenessHomelessMax").defineInRange("Attractiveness homeless max", 20.0D, 0.0D, 100.0D);
            AttractivenessDeathWeight = builder.comment("Attractiveness penalty per unit of decaying death-event impact.").translation(Villagelife.MODID + ".config.AttractivenessDeathWeight").defineInRange("Attractiveness death weight", 8.0D, 0.0D, 100.0D);
            AttractivenessHurtWeight = builder.comment("Attractiveness penalty per unit of decaying hurt-by-player impact.").translation(Villagelife.MODID + ".config.AttractivenessHurtWeight").defineInRange("Attractiveness hurt weight", 3.0D, 0.0D, 100.0D);
            AttractivenessShortageWeight = builder.comment("Attractiveness penalty per unit of decaying resource-shortage impact.").translation(Villagelife.MODID + ".config.AttractivenessShortageWeight").defineInRange("Attractiveness shortage weight", 2.0D, 0.0D, 100.0D);
            AttractivenessTheftWeight = builder.comment("Attractiveness penalty per unit of decaying theft impact. Being robbed makes a village less appealing to move to, on the same scale as its other griefs.").translation(Villagelife.MODID + ".config.AttractivenessTheftWeight").defineInRange("Attractiveness theft weight", 1.0D, 0.0D, 100.0D);
            StandingHostileBelow = builder.comment("Standing at or below which a village's guards attack you on sight. Standing is the average of its residents' opinions, from -100 to 100.").translation(Villagelife.MODID + ".config.StandingHostileBelow").defineInRange("Standing hostile below", -70, -100, 100);
            StandingShunnedBelow = builder.comment("Standing at or below which nobody in the village will approach or talk to you.").translation(Villagelife.MODID + ".config.StandingShunnedBelow").defineInRange("Standing shunned below", -50, -100, 100);
            StandingUnwelcomeBelow = builder.comment("Standing at or below which the village's market is closed to you.").translation(Villagelife.MODID + ".config.StandingUnwelcomeBelow").defineInRange("Standing unwelcome below", -30, -100, 100);
            StandingDislikedBelow = builder.comment("Standing at or below which the village charges you over the odds, rising the further you fall.").translation(Villagelife.MODID + ".config.StandingDislikedBelow").defineInRange("Standing disliked below", -10, -100, 100);
            StandingTrustedAbove = builder.comment("Standing at or above which a village counts you a friend. Nothing hangs on it yet.").translation(Villagelife.MODID + ".config.StandingTrustedAbove").defineInRange("Standing trusted above", 40, -100, 100);
            StandingWorstMarkup = builder.comment("What the village charges at its most grudging, as a multiple of the ordinary price, reached at the bottom of the disliked band.").translation(Villagelife.MODID + ".config.StandingWorstMarkup").defineInRange("Standing worst markup", 2.0D, 1.0D, 10.0D);
            AttractivenessArriveThreshold = builder.comment("Score above which new villagers arrive (consumed by the arrival system).").translation(Villagelife.MODID + ".config.AttractivenessArriveThreshold").defineInRange("Attractiveness grow threshold", 50.0D, 0.0D, 100.0D);
            AttractivenessEmigrateThreshold = builder.comment("Score below which villagers leave (consumed by the emigration system).").translation(Villagelife.MODID + ".config.AttractivenessEmigrateThreshold").defineInRange("Attractiveness decline threshold", 25.0D, 0.0D, 100.0D);
            ShortageEventCooldownSeconds = builder.comment("Minimum seconds between resource-shortage events logged by a village's planner.").translation(Villagelife.MODID + ".config.ShortageEventCooldownSeconds").defineInRange("Shortage event cooldown seconds", 600, 10, 86400);

            PopulationCheckIntervalSeconds = builder.comment("Seconds between a village's arrival/emigration checks (phase-staggered per village).").translation(Villagelife.MODID + ".config.PopulationCheckIntervalSeconds").defineInRange("Population check interval seconds", 100, 5, 86400);
            ArrivalEdgeMinDistance = builder.comment("Minimum distance from the village center at which newcomers appear (grows with building spread).").translation(Villagelife.MODID + ".config.ArrivalEdgeMinDistance").defineInRange("Arrival edge min distance", 32, 8, 256);
            TravelTimeoutSeconds = builder.comment("Seconds a newcomer or emigrant may spend walking before the village snaps them to their destination.").translation(Villagelife.MODID + ".config.TravelTimeoutSeconds").defineInRange("Travel timeout seconds", 90, 10, 3600);
            IdleCapFallback = builder.comment("Campfire idle cap used when no village tier ladder is loaded.").translation(Villagelife.MODID + ".config.IdleCapFallback").defineInRange("Idle cap fallback", 2, 1, 64);
            WandererRecruitRadius = builder.comment("How far (blocks) a growing village looks for an existing wanderer to recruit before spawning a new arrival.").translation(Villagelife.MODID + ".config.WandererRecruitRadius").defineInRange("Wanderer recruit radius", 128, 16, 512);
            WandererCap = builder.comment("Loaded wanderers the world keeps. Past the cap, an emigrant finishing their walk out moves on beyond the horizon instead of lingering.").translation(Villagelife.MODID + ".config.WandererCap").defineInRange("Wanderer cap", 8, 0, 256);
            BuildOptionsOffered = builder.comment("How many building options the village brain is offered before it chooses. More options means a less railroaded village and worse choices from a small offline model; fewer means the reverse. Waiting is always offered in addition to these.").translation(Villagelife.MODID + ".config.BuildOptionsOffered").defineInRange("Build options offered", 8, 2, 16);
            JobSwapThreshold = builder.comment("Minimum aptitude improvement (on the 3-18 stat scale) before the village reassigns a job to someone better suited. Higher = less churn.").translation(Villagelife.MODID + ".config.JobSwapThreshold").defineInRange("Job swap threshold", 3.0, 0.5, 15.0);
            JobSwapIntervalSeconds = builder.comment("Seconds between job-swap evaluation passes (phase-staggered per village).").translation(Villagelife.MODID + ".config.JobSwapIntervalSeconds").defineInRange("Job swap interval seconds", 60, 10, 3600);
            JobSwapCooldownDays = builder.comment("Game days a person is protected from further job swaps after being placed, swapped, or displaced.").translation(Villagelife.MODID + ".config.JobSwapCooldownDays").defineInRange("Job swap cooldown days", 2.0, 0.0, 30.0);
            BankSpread = builder.comment("How punishing the always-available exchange is: it pays value/spread for goods and charges value*spread. Higher means trading with players and other villages matters more.").translation(Villagelife.MODID + ".config.BankSpread").defineInRange("Bank spread", 4.0, 1.0, 32.0);
            DeveloperCommands = builder.comment("Registers the /vldev command tree: scaffolding, diagnostics, and stand-ins for interfaces that do not exist yet. Off for players; on for anyone developing the mod.").translation(Villagelife.MODID + ".config.DeveloperCommands").define("Developer commands", false);
        }
    }
}