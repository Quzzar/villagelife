package com.quzzar.villagelife.other;

import com.quzzar.villagelife.configuration.VillagelifeConfig;

import net.minecraft.world.level.Level;

public class YearManager {

    public static float getDays(Level level) {
        return level.getGameTime() / (float) Level.TICKS_PER_DAY;
    }

    public static float getYears(Level level) {
        return getDays(level) / VillagelifeConfig.DaysInYear;
    }

    public static int getDayOfYear(Level level) {
        return (int) getDays(level) % VillagelifeConfig.DaysInYear;
    }

    public static int getDaysUntil(Level level, int dayNum) {
        int dayOfYear = getDayOfYear(level);
        if (dayNum > dayOfYear) {
            return dayNum - dayOfYear;
        } else {
            return (VillagelifeConfig.DaysInYear + dayNum) - dayOfYear;
        }
    }

}
