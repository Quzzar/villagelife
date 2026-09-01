package com.quzzar.villagelife.village;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * The farmed mark: which animals belong to a village rather than to the wild.
 *
 * One bit of persistent entity data, written in three places so the herd stays
 * covered from three directions: on the animals a structure spawns with (both
 * build paths call {@link #markAnimalsWithin} right after placing entities), on
 * a newborn either of whose parents carries it (CoreEvents), and on whatever
 * the herder tends (HerdStep), which also adopts pens that predate the mark.
 * The hunter reads it to know what is not game (HuntStep), which is the whole
 * point: a butchery pen inside a hunting ground must not read as quarry.
 */
public final class FarmedStock {

  /** Key in the entity's persistent data, namespaced so nothing else collides. */
  private static final String TAG = "villagelife:farmed";

  private FarmedStock() {
  }

  public static void mark(Entity animal) {
    animal.getPersistentData().putBoolean(TAG, true);
  }

  public static boolean isFarmed(Entity animal) {
    return animal.getPersistentData().getBoolean(TAG);
  }

  /**
   * Marks every animal inside a just-placed structure's bounds. Called in the
   * same tick as addEntitiesToWorld, so the box holds exactly the stock the
   * template carried -- plus any wild animal already standing in the pen, which
   * is the pen adopting it rather than an error.
   */
  public static void markAnimalsWithin(LevelAccessor level, BoundingBox bounds) {
    AABB box = AABB.of(bounds).inflate(1.0D);
    for (Animal animal : level.getEntitiesOfClass(Animal.class, box)) {
      mark(animal);
    }
  }
}
