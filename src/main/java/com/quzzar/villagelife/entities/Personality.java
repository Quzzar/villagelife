package com.quzzar.villagelife.entities;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.quzzar.villagelife.utils.WeightedCollection;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public enum Personality {
    LAZY (List.of(Items.CAKE, Items.APPLE, Items.BAKED_POTATO, Items.COOKED_BEEF, Items.COOKED_CHICKEN, Items.COOKED_COD, Items.COOKED_MUTTON, Items.COOKED_PORKCHOP, Items.COOKED_RABBIT, Items.COOKED_SALMON, Items.COOKIE, Items.PUMPKIN_PIE, Items.AXOLOTL_BUCKET, Items.HONEY_BOTTLE, Items.SLIME_BALL, Items.MUSHROOM_STEW, Items.SUSPICIOUS_STEW, Items.RABBIT_STEW, Items.OXEYE_DAISY, Items.GOLDEN_CARROT)),

    CHEERFUL (List.of(Items.GOLDEN_CARROT, Items.GLOWSTONE_DUST, Items.GLOW_INK_SAC, Items.GLOW_BERRIES, Items.AMETHYST_SHARD, Items.DIAMOND, Items.EMERALD, Items.CAKE, Items.SPYGLASS, Items.GOLD_INGOT, Items.WRITABLE_BOOK, Items.HONEYCOMB, Items.HEART_OF_THE_SEA, Items.MUSIC_DISC_CAT, Items.SWEET_BERRIES, Items.LILY_OF_THE_VALLEY, Items.BLUE_ORCHID, Items.AZURE_BLUET, Items.DANDELION, Items.POPPY, Items.MUSIC_DISC_OTHERSIDE)),

    BUBBLY (List.of(Items.TROPICAL_FISH_BUCKET, Items.AXOLOTL_BUCKET, Items.TURTLE_EGG, Items.HEART_OF_THE_SEA, Items.MUSIC_DISC_PIGSTEP, Items.MUSIC_DISC_BLOCKS, Items.AMETHYST_SHARD, Items.GLOWSTONE, Items.SWEET_BERRIES, Items.CAKE, Items.ROSE_BUSH, Items.ALLIUM, Items.PINK_TULIP, Items.PEONY, Items.LILAC, Items.COOKIE, Items.MUSIC_DISC_OTHERSIDE)),

    STOUT (List.of(Items.SPYGLASS, Items.COMPASS, Items.RABBIT_STEW, Items.BEETROOT_SOUP, Items.MUSIC_DISC_FAR)),
    CRANKY (List.of(Items.CAKE, Items.EGG, Items.MUSIC_DISC_STAL, Items.MUSIC_DISC_WARD, Items.TNT, Items.CLOCK, Items.BELL)),
    SECLUDED (List.of(Items.CAKE, Items.CARVED_PUMPKIN, Items.MUSIC_DISC_WARD, Items.FLINT_AND_STEEL, Items.WITHER_ROSE)),
    SASSY (List.of(Items.CAKE, Items.EGG, Items.MUSIC_DISC_STAL, Items.BELL, Items.CHORUS_FRUIT)),
    SMUG (List.of(Items.MUSIC_DISC_MELLOHI, Items.BEETROOT_SOUP, Items.DRAGON_BREATH, Items.TURTLE_HELMET, Items.CLOCK, Items.ENDER_EYE, Items.EXPERIENCE_BOTTLE, Items.WRITABLE_BOOK));

    private final List<Item> favoriteItems;
    private Personality(List<Item> favoriteItems) {
        this.favoriteItems = favoriteItems;
    }

    public List<String> getFavoriteItems(){
        List<String> list = new ArrayList<>();
        for(Item item : favoriteItems){
            list.add(item.toString().toUpperCase());
        }
        for(Item item : UNIVERSAL_LOVED_ITEMS){
            list.add(item.toString().toUpperCase());
        }
        return list;
    }


    private static final List<Item> UNIVERSAL_LOVED_ITEMS = List.of(Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.DIAMOND_BLOCK, Items.EMERALD_BLOCK, Items.NETHER_STAR);
    public static List<Item> getUniversalLovedItems(){
        return UNIVERSAL_LOVED_ITEMS;
    }


    public static Personality generateFromVirtues(float aggression, float curiosity, float drive, float protectOthers, float protectSelf){
        float lazy, cheerful, bubbly, stout, cranky, secluded, sassy, smug;
        lazy = cheerful = bubbly = stout = cranky = secluded = sassy = smug = 1.0F;

        lazy += drive * -10;
        cheerful += curiosity * 4; cheerful += protectOthers * 6; cheerful += aggression * -4;
        bubbly += protectOthers * 4; bubbly += protectSelf * 2; bubbly += aggression * -2;
        stout += aggression * 3; stout += drive * 4; stout += protectOthers * 3;
        cranky += aggression * 3; cranky += protectSelf * 3; cranky += protectOthers * -7;
        secluded += protectOthers * -6; secluded += curiosity * -1; secluded += drive * -3;
        sassy += protectSelf * 7; sassy += protectOthers * -3; sassy += drive * 3;
        smug += protectSelf * 5; smug += curiosity * 5; smug += protectOthers * -2;

        WeightedCollection<Personality> randCollection = new WeightedCollection<>();
        randCollection.add(lazy, Personality.LAZY);
        randCollection.add(cheerful, Personality.CHEERFUL);
        randCollection.add(bubbly, Personality.BUBBLY);
        randCollection.add(stout, Personality.STOUT);
        randCollection.add(cranky, Personality.CRANKY);
        randCollection.add(secluded, Personality.SECLUDED);
        randCollection.add(sassy, Personality.SASSY);
        randCollection.add(smug, Personality.SMUG);

        return randCollection.getRandValue();
    }


    public static Item itemFromString(String name){
        try {
            return (Item) Items.class.getField(name).get(Item.class);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

}
