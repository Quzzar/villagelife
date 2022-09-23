package com.quzzar.villagelife.entities;

import java.util.Random;

public enum Gender {
    MALE(0.45),
    FEMALE(0.45),
    NONBINARY(0.1);

    private final double chance;
    private Gender(double chance) {
        this.chance = chance;
    }

    public double getChance(){
        return chance;
    }

    public String getSymbol(){
        switch(this){
            case MALE: return "♂";
            case FEMALE: return "♀";
            case NONBINARY: return "⚧";
            default: return "";
        }
    }

    public static Gender generateGender(){
        double d = new Random().nextDouble();
        if(d > Gender.MALE.getChance()) {
            if(d > Gender.MALE.getChance()+Gender.FEMALE.getChance()) {
                return Gender.NONBINARY;
            } else {
                return Gender.FEMALE;
            }
        } else {
            return Gender.MALE;
        }
    }

}
