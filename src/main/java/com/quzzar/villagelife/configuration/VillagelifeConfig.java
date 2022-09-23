package com.quzzar.villagelife.configuration;

import java.util.ArrayList;
import java.util.List;

import com.quzzar.villagelife.Villagelife;

import org.apache.commons.lang3.tuple.Pair;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@EventBusSubscriber(modid = Villagelife.MODID, bus = EventBusSubscriber.Bus.MOD)
public class VillagelifeConfig {
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final CommonConfig COMMON;
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ClientConfig CLIENT;
    static {
        {
            final Pair<CommonConfig, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(CommonConfig::new);
            COMMON = specPair.getLeft();
            COMMON_SPEC = specPair.getRight();
        }
        {
            final Pair<ClientConfig, ForgeConfigSpec> specPair1 = new ForgeConfigSpec.Builder().configure(ClientConfig::new);
            CLIENT = specPair1.getLeft();
            CLIENT_SPEC = specPair1.getRight();
        }
    }

    public static boolean RaidAnimals;
    public static boolean AttackAllMobs;
    public static boolean FriendlyFire;
    public static double GuardVillagerHelpRange;
    public static List<String> MobBlackList;
    public static int DaysInYear;

    public static void bakeCommonConfig() {
        RaidAnimals = COMMON.RaidAnimals.get();
        AttackAllMobs = COMMON.AttackAllMobs.get();
        FriendlyFire = COMMON.FriendlyFire.get();
        MobBlackList = COMMON.MobBlackList.get();
        GuardVillagerHelpRange = COMMON.GuardVillagerHelpRange.get();
        DaysInYear = COMMON.DaysInYear.get();
    }

    public static void bakeClientConfig() {
        
    }

    @SubscribeEvent
    public static void onModConfigEvent(final ModConfigEvent.Loading configEvent) {
        if (configEvent.getConfig().getSpec() == VillagelifeConfig.COMMON_SPEC) {
            bakeCommonConfig();
        } else if (configEvent.getConfig().getSpec() == VillagelifeConfig.CLIENT_SPEC) {
            bakeClientConfig();
        }
    }

    public static class CommonConfig {
        public final ForgeConfigSpec.BooleanValue RaidAnimals;
        public final ForgeConfigSpec.BooleanValue AttackAllMobs;
        public final ForgeConfigSpec.BooleanValue FriendlyFire;
        public final ForgeConfigSpec.DoubleValue GuardVillagerHelpRange;
        public final ForgeConfigSpec.ConfigValue<List<String>> MobBlackList;
        public final ForgeConfigSpec.IntValue DaysInYear;

        public CommonConfig(ForgeConfigSpec.Builder builder) {
            RaidAnimals = builder.comment("Illagers In Raids Attack Animals?").translation(Villagelife.MODID + ".config.RaidAnimals").define("Illagers in raids attack animals?", false);
            AttackAllMobs = builder.comment("Guards will attack all hostiles with this option").translation(Villagelife.MODID + ".config.AttackAllMobs").define("Guards attack all mobs?", false);
            MobBlackList = builder.comment("Guards won't attack mobs in this list if AttackAllMobs is enabled, for example, putting minecraft:creeper in this list will make guards ignore creepers.").define("Mob BlackList", new ArrayList<>());
            FriendlyFire = builder.comment("This will make guards attempt to avoid friendly fire.").translation(Villagelife.MODID + ".config.FriendlyFire").define("Have guards attempt to avoid firing into other friendlies? (Experimental)", false);
            GuardVillagerHelpRange = builder.translation(Villagelife.MODID + ".config.range").comment("This is the range in which the guards will be aggroed to mobs that are attacking villagers. Higher values are more resource intensive, and setting this to zero will disable the goal.")
                    .defineInRange("Range", 50.0D, -500.0D, 500.0D);

            DaysInYear = builder.comment("Days in one Minecraft year (there are 8 days in one full lunar cycle).").translation(Villagelife.MODID + ".config.DaysInYear").defineInRange("Days in Year", 96, 8, 79992);

        }
    }

    public static class ClientConfig {
        public final ForgeConfigSpec.BooleanValue GuardSteve;

        public ClientConfig(ForgeConfigSpec.Builder builder) {
            GuardSteve = builder.comment("Textures not included, make your own textures by making a resource pack that adds guard_steve_0 - 6").translation(Villagelife.MODID + ".config.steveModel").define("Have guards use the steve model?", true);
        }
    }
}