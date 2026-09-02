package com.quzzar.villagelife.configuration;


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

    // --- general ---
    public static int DaysInYear;
    public static boolean GenerateVillages;

    // --- llm ---
    public static boolean LlmEnabled;
    public static String LlmProviderName;
    public static String LlmApiKey;
    public static String LlmCloudModel;
    public static String LlmLocalModel;
    public static int LlmChatMaxNewTokens;
    public static double LlmChatTemperature;
    public static int LlmDecisionMaxNewTokens;
    public static double LlmDecisionTemperature;
    public static boolean LlmVillagerConversations;

    // --- attractiveness ---
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
    public static double AttractivenessArriveThreshold;
    public static double AttractivenessEmigrateThreshold;

    // --- standing ---
    public static int StandingHostileBelow;
    public static int StandingShunnedBelow;
    public static int StandingUnwelcomeBelow;
    public static int StandingDislikedBelow;
    public static int StandingTrustedAbove;
    public static double StandingWorstMarkup;
    public static int AssaultOpinionHit;
    public static int GrudgeAttackBelow;

    // --- population ---
    public static int PopulationCheckIntervalSeconds;
    public static int ShortageEventCooldownSeconds;
    public static int ArrivalEdgeMinDistance;
    public static int TravelTimeoutSeconds;
    public static int IdleCapFallback;
    public static int WandererRecruitRadius;
    public static int WandererCap;
    public static int WandererHorizonDistance;
    public static int WandererPoolCap;

    // --- labor ---
    public static double JobSwapThreshold;
    public static int JobSwapIntervalSeconds;
    public static double JobSwapCooldownDays;

    // --- economy ---
    public static double BankSpread;

    // --- developer ---
    public static boolean DeveloperCommands;
    public static String PersonaJudgeProvider;
    public static String PersonaJudgeApiKey;
    public static String PersonaJudgeModel;

    public static void bakeCommonConfig() {
        // general
        DaysInYear = COMMON.DaysInYear.get();
        GenerateVillages = COMMON.GenerateVillages.get();

        // llm
        LlmEnabled = COMMON.LlmEnabled.get();
        LlmProviderName = COMMON.LlmProviderName.get();
        LlmApiKey = COMMON.LlmApiKey.get();
        LlmCloudModel = COMMON.LlmCloudModel.get();
        LlmLocalModel = COMMON.LlmLocalModel.get();
        LlmChatMaxNewTokens = COMMON.LlmChatMaxNewTokens.get();
        LlmChatTemperature = COMMON.LlmChatTemperature.get();
        LlmDecisionMaxNewTokens = COMMON.LlmDecisionMaxNewTokens.get();
        LlmDecisionTemperature = COMMON.LlmDecisionTemperature.get();
        LlmVillagerConversations = COMMON.LlmVillagerConversations.get();

        // attractiveness
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
        AttractivenessArriveThreshold = COMMON.AttractivenessArriveThreshold.get();
        AttractivenessEmigrateThreshold = COMMON.AttractivenessEmigrateThreshold.get();

        // standing
        StandingHostileBelow = COMMON.StandingHostileBelow.get();
        StandingShunnedBelow = COMMON.StandingShunnedBelow.get();
        StandingUnwelcomeBelow = COMMON.StandingUnwelcomeBelow.get();
        StandingDislikedBelow = COMMON.StandingDislikedBelow.get();
        StandingTrustedAbove = COMMON.StandingTrustedAbove.get();
        StandingWorstMarkup = COMMON.StandingWorstMarkup.get();
        AssaultOpinionHit = COMMON.AssaultOpinionHit.get();
        GrudgeAttackBelow = COMMON.GrudgeAttackBelow.get();

        // population
        PopulationCheckIntervalSeconds = COMMON.PopulationCheckIntervalSeconds.get();
        ShortageEventCooldownSeconds = COMMON.ShortageEventCooldownSeconds.get();
        ArrivalEdgeMinDistance = COMMON.ArrivalEdgeMinDistance.get();
        TravelTimeoutSeconds = COMMON.TravelTimeoutSeconds.get();
        IdleCapFallback = COMMON.IdleCapFallback.get();
        WandererRecruitRadius = COMMON.WandererRecruitRadius.get();
        WandererCap = COMMON.WandererCap.get();
        WandererHorizonDistance = COMMON.WandererHorizonDistance.get();
        WandererPoolCap = COMMON.WandererPoolCap.get();

        // labor
        JobSwapThreshold = COMMON.JobSwapThreshold.get();
        JobSwapIntervalSeconds = COMMON.JobSwapIntervalSeconds.get();
        JobSwapCooldownDays = COMMON.JobSwapCooldownDays.get();

        // economy
        BankSpread = COMMON.BankSpread.get();

        // developer
        DeveloperCommands = COMMON.DeveloperCommands.get();
        PersonaJudgeProvider = COMMON.PersonaJudgeProvider.get();
        PersonaJudgeApiKey = COMMON.PersonaJudgeApiKey.get();
        PersonaJudgeModel = COMMON.PersonaJudgeModel.get();
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
        // general
        public final ModConfigSpec.IntValue DaysInYear;
        public final ModConfigSpec.BooleanValue GenerateVillages;

        // llm
        public final ModConfigSpec.BooleanValue LlmEnabled;
        public final ModConfigSpec.ConfigValue<String> LlmProviderName;
        public final ModConfigSpec.ConfigValue<String> LlmApiKey;
        public final ModConfigSpec.ConfigValue<String> LlmCloudModel;
        public final ModConfigSpec.ConfigValue<String> LlmLocalModel;
        public final ModConfigSpec.IntValue LlmChatMaxNewTokens;
        public final ModConfigSpec.DoubleValue LlmChatTemperature;
        public final ModConfigSpec.IntValue LlmDecisionMaxNewTokens;
        public final ModConfigSpec.DoubleValue LlmDecisionTemperature;
        public final ModConfigSpec.BooleanValue LlmVillagerConversations;

        // attractiveness
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
        public final ModConfigSpec.DoubleValue AttractivenessArriveThreshold;
        public final ModConfigSpec.DoubleValue AttractivenessEmigrateThreshold;

        // standing
        public final ModConfigSpec.IntValue StandingHostileBelow;
        public final ModConfigSpec.IntValue StandingShunnedBelow;
        public final ModConfigSpec.IntValue StandingUnwelcomeBelow;
        public final ModConfigSpec.IntValue StandingDislikedBelow;
        public final ModConfigSpec.IntValue StandingTrustedAbove;
        public final ModConfigSpec.DoubleValue StandingWorstMarkup;
        public final ModConfigSpec.IntValue AssaultOpinionHit;
        public final ModConfigSpec.IntValue GrudgeAttackBelow;

        // population
        public final ModConfigSpec.IntValue PopulationCheckIntervalSeconds;
        public final ModConfigSpec.IntValue ShortageEventCooldownSeconds;
        public final ModConfigSpec.IntValue ArrivalEdgeMinDistance;
        public final ModConfigSpec.IntValue TravelTimeoutSeconds;
        public final ModConfigSpec.IntValue IdleCapFallback;
        public final ModConfigSpec.IntValue WandererRecruitRadius;
        public final ModConfigSpec.IntValue WandererCap;
        public final ModConfigSpec.IntValue WandererHorizonDistance;
        public final ModConfigSpec.IntValue WandererPoolCap;

        // labor
        public final ModConfigSpec.DoubleValue JobSwapThreshold;
        public final ModConfigSpec.IntValue JobSwapIntervalSeconds;
        public final ModConfigSpec.DoubleValue JobSwapCooldownDays;

        // economy
        public final ModConfigSpec.DoubleValue BankSpread;

        // developer
        public final ModConfigSpec.BooleanValue DeveloperCommands;
        public final ModConfigSpec.ConfigValue<String> PersonaJudgeProvider;
        public final ModConfigSpec.ConfigValue<String> PersonaJudgeApiKey;
        public final ModConfigSpec.ConfigValue<String> PersonaJudgeModel;

        public CommonConfig(ModConfigSpec.Builder builder) {

            builder.comment("World generation and the passage of time.").push("general");

            DaysInYear = builder.comment("Days in one Minecraft year (there are 8 days in one full lunar cycle). Villagers know the current year from the world's age, derived from this.").translation(Villagelife.MODID + ".config.DaysInYear").defineInRange("Days in Year", 96, 8, 79992);
            GenerateVillages = builder.comment("Generate villagelife villages during world generation, replacing vanilla villages. On (default): our living villages generate in the world in place of vanilla ones. Off: no villages generate in the world - you can still spawn one manually with /villagelife create-village. Only vanilla minecraft:village is affected; other mods' villages are untouched.").translation(Villagelife.MODID + ".config.GenerateVillages").define("Generate villages", true);

            builder.pop();

            builder.comment("The language model that gives villagers their conversation, decisions, personas, and village names.").push("llm");

            LlmEnabled = builder.comment("Enable the LLM behind villager conversation, village decisions, personas, and settlement names. The offline model (about 2 GB) downloads from HuggingFace on first server start and is cached in <game dir>/villagelife/models. Budget roughly 3 GB of RAM beyond the game's needs for the offline model, or point LLM provider at a cloud service for no local cost. Check /vlbrain status in-game.").translation(Villagelife.MODID + ".config.LlmEnabled").define("Enable LLM?", true);
            LlmProviderName = builder.comment("Which LLM answers for the villagers: 'llamacpp' (default) downloads llama.cpp and an offline model and runs them for you - no account, nothing to install; 'claude', 'openai', or 'deepseek' use that cloud service with your API key. Changing this requires a game restart.").translation(Villagelife.MODID + ".config.LlmProvider").define("LLM provider", "llamacpp");
            LlmApiKey = builder.comment("Optional; only needed when 'LLM provider' is a cloud service (claude/openai/deepseek). The offline 'llamacpp' provider ignores it. Paste it here in plain text and TREAT THIS FILE LIKE A PASSWORD: anyone with this file can spend your account's money.").translation(Villagelife.MODID + ".config.LlmApiKey").define("LLM API key", "");
            LlmCloudModel = builder.comment("Model id for the cloud provider (unused by llamacpp, which uses LLM local model). Leave empty for the provider's default (Claude: claude-haiku-4-5, OpenAI: gpt-5.6-luna, DeepSeek: deepseek-chat).").translation(Villagelife.MODID + ".config.LlmCloudModel").define("LLM cloud model", "");
            LlmLocalModel = builder.comment("Which offline model the 'llamacpp' provider downloads and runs: 'llama-3b' (default, Llama-3.2-3B) or 'gemma-2-2b' (Gemma-2-2B). Llama was the clear best of the candidates at holding a conversation in character without looping the same line, which is what a player notices first. Gemma is a slightly smaller, slightly faster alternative that talks nearly as well. A one-time download of about 2 GB.").translation(Villagelife.MODID + ".config.LlmLocalModel").define("LLM local model", "llama-3b");
            LlmChatMaxNewTokens = builder.comment("Maximum tokens a villager may generate per line of conversation. Kept modest because generation time scales with it: on the smallest model 47 tokens took about 13 seconds in-game, so a low cap bounds the worst-case pause. Raise it to let villagers say more.").translation(Villagelife.MODID + ".config.LlmChatMaxNewTokens").defineInRange("LLM chat max new tokens", 64, 16, 1024);
            LlmChatTemperature = builder.comment("Sampling temperature for conversation. Higher is livelier and more varied; too high on a small model makes it ramble or break the JSON its reply is wrapped in. 0.4 is the tuned default.").translation(Villagelife.MODID + ".config.LlmChatTemperature").defineInRange("LLM chat temperature", 0.4D, 0.0D, 2.0D);
            LlmDecisionMaxNewTokens = builder.comment("Maximum tokens the LLM may generate per village decision (which building to start, which idle camper to hire). A decision is a short JSON answer, so this stays small.").translation(Villagelife.MODID + ".config.LlmDecisionMaxNewTokens").defineInRange("LLM decision max new tokens", 128, 16, 1024);
            LlmDecisionTemperature = builder.comment("Sampling temperature for village decisions. Low keeps them focused; the rules already narrow every choice to legal options, so a little variety here just adds character. 0.0 is fully deterministic.").translation(Villagelife.MODID + ".config.LlmDecisionTemperature").defineInRange("LLM decision temperature", 0.1D, 0.0D, 2.0D);
            LlmVillagerConversations = builder.comment("Whether villagers strike up conversations with each other (overheard as gray chat lines when you are close by). They talk, trade items, and file village requests among themselves on the LLM's spare time - it never delays a player's own conversation. Turn off to save LLM calls, which on a cloud provider are billed.").translation(Villagelife.MODID + ".config.LlmVillagerConversations").define("Villagers talk to each other", true);

            builder.pop();

            builder.comment("Attractiveness: the 0-100 score that decides whether people move into a village or leave it.").push("attractiveness");

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
            AttractivenessArriveThreshold = builder.comment("Score above which new villagers arrive (consumed by the arrival system).").translation(Villagelife.MODID + ".config.AttractivenessArriveThreshold").defineInRange("Attractiveness grow threshold", 50.0D, 0.0D, 100.0D);
            AttractivenessEmigrateThreshold = builder.comment("Score below which villagers leave (consumed by the emigration system).").translation(Villagelife.MODID + ".config.AttractivenessEmigrateThreshold").defineInRange("Attractiveness decline threshold", 25.0D, 0.0D, 100.0D);

            builder.pop();

            builder.comment("Standing: how a village treats a player, judged by the average of its residents' opinions (-100 to 100).").push("standing");

            StandingHostileBelow = builder.comment("Standing at or below which a village's guards attack you on sight. Standing is the average of its residents' opinions, from -100 to 100.").translation(Villagelife.MODID + ".config.StandingHostileBelow").defineInRange("Standing hostile below", -70, -100, 100);
            StandingShunnedBelow = builder.comment("Standing at or below which nobody in the village will approach or talk to you.").translation(Villagelife.MODID + ".config.StandingShunnedBelow").defineInRange("Standing shunned below", -50, -100, 100);
            StandingUnwelcomeBelow = builder.comment("Standing at or below which the village's market is closed to you.").translation(Villagelife.MODID + ".config.StandingUnwelcomeBelow").defineInRange("Standing unwelcome below", -30, -100, 100);
            StandingDislikedBelow = builder.comment("Standing at or below which the village charges you over the odds, rising the further you fall.").translation(Villagelife.MODID + ".config.StandingDislikedBelow").defineInRange("Standing disliked below", -10, -100, 100);
            StandingTrustedAbove = builder.comment("Standing at or above which a village counts you a friend. Nothing hangs on it yet.").translation(Villagelife.MODID + ".config.StandingTrustedAbove").defineInRange("Standing trusted above", 40, -100, 100);
            StandingWorstMarkup = builder.comment("What the village charges at its most grudging, as a multiple of the ordinary price, reached at the bottom of the disliked band.").translation(Villagelife.MODID + ".config.StandingWorstMarkup").defineInRange("Standing worst markup", 2.0D, 1.0D, 10.0D);
            AssaultOpinionHit = builder.comment("How much being struck by a player lowers the victim's own opinion of them, before the blow's damage is added on top. Every strike counts, so one punch is a small grievance and a beating crosses the grudge line.").translation(Villagelife.MODID + ".config.AssaultOpinionHit").defineInRange("Assault opinion hit", 5, 0, 15);
            GrudgeAttackBelow = builder.comment("Opinion of a player at or below which a villager treats them as an enemy: fighters attack on sight, everyone else keeps their distance. Personal, unlike the standing tiers above, which average the whole village.").translation(Villagelife.MODID + ".config.GrudgeAttackBelow").defineInRange("Grudge attack below", -30, -100, 0);

            builder.pop();

            builder.comment("Population: the campfire arrival and emigration loop, and its timings.").push("population");

            PopulationCheckIntervalSeconds = builder.comment("Seconds between a village's arrival/emigration checks (phase-staggered per village).").translation(Villagelife.MODID + ".config.PopulationCheckIntervalSeconds").defineInRange("Population check interval seconds", 100, 5, 86400);
            ShortageEventCooldownSeconds = builder.comment("Minimum seconds between resource-shortage events logged by a village's planner.").translation(Villagelife.MODID + ".config.ShortageEventCooldownSeconds").defineInRange("Shortage event cooldown seconds", 600, 10, 86400);
            ArrivalEdgeMinDistance = builder.comment("Minimum distance from the village center at which newcomers appear (grows with building spread).").translation(Villagelife.MODID + ".config.ArrivalEdgeMinDistance").defineInRange("Arrival edge min distance", 32, 8, 256);
            TravelTimeoutSeconds = builder.comment("Seconds a newcomer or emigrant may spend walking before the village snaps them to their destination.").translation(Villagelife.MODID + ".config.TravelTimeoutSeconds").defineInRange("Travel timeout seconds", 90, 10, 3600);
            IdleCapFallback = builder.comment("Campfire idle cap used when no village tier ladder is loaded.").translation(Villagelife.MODID + ".config.IdleCapFallback").defineInRange("Idle cap fallback", 2, 1, 64);
            WandererRecruitRadius = builder.comment("How far (blocks) a growing village looks for an existing wanderer to recruit before spawning a new arrival.").translation(Villagelife.MODID + ".config.WandererRecruitRadius").defineInRange("Wanderer recruit radius", 128, 16, 512);
            WandererCap = builder.comment("Loaded wanderers the world keeps on foot. Past the cap, a leaver reaching the village edge passes beyond the horizon at once instead of walking there.").translation(Villagelife.MODID + ".config.WandererCap").defineInRange("Wanderer cap", 8, 0, 256);
            WandererHorizonDistance = builder.comment("How far (blocks) a wanderer walks from where they set out before passing beyond the horizon: out of the loaded world and onto the road every growing village recruits from.").translation(Villagelife.MODID + ".config.WandererHorizonDistance").defineInRange("Wanderer horizon distance", 128, 32, 1024);
            WandererPoolCap = builder.comment("Most people the world remembers on the road beyond the horizon. Past it the longest-gone is forgotten; 0 forgets everyone the moment they cross.").translation(Villagelife.MODID + ".config.WandererPoolCap").defineInRange("Wanderer pool cap", 64, 0, 1024);

            builder.pop();

            builder.comment("Labor: how jobs are filled from the idle pool and reshuffled.").push("labor");

            JobSwapThreshold = builder.comment("Minimum aptitude improvement (on the 3-18 stat scale) before the village reassigns a job to someone better suited. Higher = less churn.").translation(Villagelife.MODID + ".config.JobSwapThreshold").defineInRange("Job swap threshold", 3.0, 0.5, 15.0);
            JobSwapIntervalSeconds = builder.comment("Seconds between job-swap evaluation passes (phase-staggered per village).").translation(Villagelife.MODID + ".config.JobSwapIntervalSeconds").defineInRange("Job swap interval seconds", 60, 10, 3600);
            JobSwapCooldownDays = builder.comment("Game days a person is protected from further job swaps after being placed, swapped, or displaced.").translation(Villagelife.MODID + ".config.JobSwapCooldownDays").defineInRange("Job swap cooldown days", 2.0, 0.0, 30.0);

            builder.pop();

            builder.comment("Economy: the always-available exchange that floors and caps every price.").push("economy");

            BankSpread = builder.comment("How punishing the always-available exchange is: it pays value/spread for goods and charges value*spread. Higher means trading with players and other villages matters more.").translation(Villagelife.MODID + ".config.BankSpread").defineInRange("Bank spread", 4.0, 1.0, 32.0);

            builder.pop();

            builder.comment("Developer tools. All off or blank by default: these are for anyone working on the mod, not for players.").push("developer");

            DeveloperCommands = builder.comment("Registers the /vldev command tree: scaffolding, diagnostics, and stand-ins for interfaces that do not exist yet. Off for players; on for anyone developing the mod.").translation(Villagelife.MODID + ".config.DeveloperCommands").define("Developer commands", false);
            PersonaJudgeProvider = builder.comment("Which cloud service scores persona quality in '/vldev persona audit' (developer benchmark only, never the live game): 'claude', 'openai', or 'deepseek'. A cloud judge is used because a small local model cannot reliably tell a well-paraphrased trait from a contradicted one. Leave the key blank to run the audit without scoring.").translation(Villagelife.MODID + ".config.PersonaJudgeProvider").define("Persona judge provider", "claude");
            PersonaJudgeApiKey = builder.comment("API key for the persona-judge provider (developer benchmark only). Separate from the villagers' 'LLM API key' so the game can run a local model while the judge calls the cloud. Blank disables scoring. TREAT THIS FILE LIKE A PASSWORD.").translation(Villagelife.MODID + ".config.PersonaJudgeApiKey").define("Persona judge API key", "");
            PersonaJudgeModel = builder.comment("Model id for the persona-judge provider. Leave empty for the provider's default (Claude: claude-haiku-4-5, OpenAI: gpt-5.6-luna, DeepSeek: deepseek-chat).").translation(Villagelife.MODID + ".config.PersonaJudgeModel").define("Persona judge model", "");

            builder.pop();
        }
    }
}
