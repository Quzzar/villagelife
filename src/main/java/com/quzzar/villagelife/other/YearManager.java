package com.quzzar.villagelife.other;

import com.quzzar.villagelife.configuration.VillagelifeConfig;

import net.minecraft.world.level.Level;

public class YearManager {

    private static long gameTime = -1;

    public static void update(long newGameTime){
        gameTime = newGameTime;
    }

    public static float getDays(){
        if(gameTime == -1){ return 0F; }
        return gameTime / Level.TICKS_PER_DAY;
    }

    public static float getYears(){
        if(gameTime == -1){ return 0F; }
        return getDays() / VillagelifeConfig.DaysInYear;
    }

    public static int getDayOfYear(){
        if(gameTime == -1){ return 0; }
        return (int) getDays() % VillagelifeConfig.DaysInYear;
    }

    public static int getDaysUntil(int dayNum){
        if(gameTime == -1){ return -1; }
        int dayOfYear = getDayOfYear();
        if(dayNum > dayOfYear){
            return dayNum - dayOfYear;
        } else {
            return (VillagelifeConfig.DaysInYear+dayNum) - dayOfYear;
        }
    }

}
