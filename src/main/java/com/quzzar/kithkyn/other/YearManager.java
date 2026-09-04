package com.quzzar.kithkyn.other;

import com.quzzar.kithkyn.configuration.KithkynConfig;

import net.minecraft.world.level.Level;

public class YearManager {

    public static float getDays(Level level) {
        return level.getGameTime() / (float) Level.TICKS_PER_DAY;
    }

    public static float getYears(Level level) {
        return getDays(level) / KithkynConfig.DaysInYear;
    }

    public static int getDayOfYear(Level level) {
        return (int) getDays(level) % KithkynConfig.DaysInYear;
    }

    public static int getDaysUntil(Level level, int dayNum) {
        int dayOfYear = getDayOfYear(level);
        if (dayNum > dayOfYear) {
            return dayNum - dayOfYear;
        } else {
            return (KithkynConfig.DaysInYear + dayNum) - dayOfYear;
        }
    }

}
