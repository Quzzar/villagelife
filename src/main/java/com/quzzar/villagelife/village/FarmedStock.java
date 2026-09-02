package com.quzzar.villagelife.village;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * The farmed mark: which animals belong to a village rather than to the wild,
 * and how large a herd the village keeps.
 *
 * One bit of persistent entity data, written in three places so the herd stays
 * covered from three directions: on the animals a structure spawns with (both
 * build paths call {@link #markAnimalsWithin} right after placing entities), on
 * a newborn either of whose parents carries it (CoreEvents), and on whatever
 * the herder tends (HerdStep), which also adopts pens that predate the mark.
 * The hunter reads it to know what is not game (HuntStep), which is the whole
 * point: a butchery pen inside a hunting ground must not read as quarry.
 *
 * <b>The herd has a size, and it is two numbers.</b> The herder breeds a kind up
 * to {@link #BREED_CAP} and no further; once a kind has grown to that many the
 * butcher slaughters it down to {@link #KEEP} (CullStep), and the herder breeds
 * it back up. So a pen swells and is thinned in cycles, and every animal above
 * the six the village keeps is meat and hide. Without the numbers a butchery
 * with wheat in store bred until the animals filled the pen wall to wall and
 * the people working in it could not reach its door. Both figures are Aaron's:
 * twelve is what the shipped five-by-three yard can hold at a squeeze, six is
 * what it lives with. Young count toward both, so a pen at its size with lambs
 * on the ground does not breed again the moment they grow.
 */
public final class FarmedStock {

  /** Key in the entity's persistent data, namespaced so nothing else collides. */
  private static final String TAG = "villagelife:farmed";

  /** How many of each kind the herder breeds up to; the butcher's cue to thin the pen. */
  public static final int BREED_CAP = 12;

  /** How many of each kind a cull leaves standing. */
  public static final int KEEP = 6;

  /** How far around a pen's station its stock is counted and tended. */
  public static final double PASTURE_RADIUS = 12.0D;

  private FarmedStock() {
  }

  public static void mark(Entity animal) {
    animal.getPersistentData().putBoolean(TAG, true);
  }

  public static boolean isFarmed(Entity animal) {
    return animal.getPersistentData().getBoolean(TAG);
  }

  /** The kinds a village keeps; everything else near a pen is a stray, not stock. */
  public static boolean isStock(Animal animal) {
    return animal instanceof Cow || animal instanceof Pig
        || animal instanceof Sheep || animal instanceof Chicken;
  }

  /** The ground a pen's station tends: the pasture, as one box around it. */
  public static AABB pasture(BlockPos station) {
    return new AABB(station).inflate(PASTURE_RADIUS, 4.0D, PASTURE_RADIUS);
  }

  /**
   * Whether this animal's kind has been bred to the cap on the pasture,
   * counting itself and its young. Kind is the exact class, the same equality
   * the herder uses to pair breeding partners.
   */
  public static boolean atBreedCap(Level level, BlockPos station, Animal like) {
    return herds(level, station).getOrDefault(like.getClass(), List.of()).size() >= BREED_CAP;
  }

  /** Every living farmed animal of a kept kind on the pasture, by kind. */
  public static Map<Class<?>, List<Animal>> herds(Level level, BlockPos station) {
    Map<Class<?>, List<Animal>> herds = new HashMap<>();
    for (Animal animal : level.getEntitiesOfClass(Animal.class, pasture(station),
        candidate -> candidate.isAlive() && isStock(candidate) && isFarmed(candidate))) {
      herds.computeIfAbsent(animal.getClass(), kind -> new ArrayList<>()).add(animal);
    }
    return herds;
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
