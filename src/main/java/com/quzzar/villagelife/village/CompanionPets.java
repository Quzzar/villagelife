package com.quzzar.villagelife.village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.CatVariant;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.WolfVariant;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.network.chat.Component;

/**
 * Companion pets: a villager's dog or cat, a vanilla tamed animal that belongs to
 * the person, not the post. This is the whole domain in one place, so the rules a
 * pet lives by, who owns one, who never gets a second, and how one finds its way
 * back to its owner, are read here and nowhere else.
 *
 * <p>A pet is a free world entity. It carries no village save data: everything it
 * needs to know its owner and its home is written on its own persistent data and
 * resolved by UUID on demand ({@link #loadedOwner}, {@link #villageUuid}). A dead
 * owner's UUID simply never resolves again, and the pet falls back to wandering
 * its village ({@code PetVillageTetherGoal}) with no death hook to fire.
 *
 * <p>Only two things a pet needs are not vanilla: it does not follow a villager on
 * its own (vanilla owner-follow resolves players only), and a villager's job
 * assignment is what grants a pet in the first place. Both live here; the follow
 * and tether behaviours are the two custom goals the join handler attaches, and
 * the ownership tag ({@link #COMPANION_PET_KEY}) is what marks a pet for them.
 */
public final class CompanionPets {

  /** Marks a spawned entity as one of ours, so the join handler attaches our goals to it. */
  public static final String COMPANION_PET_KEY = "villagelife:companionPet";

  /** The owning villager's UUID, written on the pet with putUUID. */
  public static final String PET_OWNER_KEY = "villagelife:petOwner";

  /** The owning village's UUID string, written on the pet, so a masterless pet knows where home is. */
  public static final String PET_VILLAGE_KEY = "villagelife:petVillage";

  /**
   * The species a villager already keeps, comma-joined on the PERSON. One pet per
   * species is the cap: a villager may gather a dog and a cat across different
   * jobs, but never a second of either.
   */
  private static final String PET_SPECIES_OWNED_KEY = "villagelife:petSpeciesOwned";

  /**
   * The occupations a villager has already been rolled for, comma-joined on the
   * PERSON. Being reassigned to the same job must not re-roll its chance, or a
   * guard shuffled in and out of the post would eventually always draw a dog.
   */
  private static final String PET_ROLLED_OCCUPATIONS_KEY = "villagelife:petRolledOccupations";

  /** Beyond this from its owner, a pet stops pathing and teleports to their side (vanilla's own threshold). */
  private static final double UNSIT_TELEPORT_DISTANCE = 12.0D;

  /** A pet marks a chance out of one; these are those chances by job. */
  private static final double HUNTER_DOG_CHANCE = 1.0D;
  private static final double GUARD_DOG_CHANCE = 0.25D;
  private static final double QUARTERMASTER_CAT_CHANCE = 0.25D;

  private CompanionPets() {
  }

  /**
   * A companion species and the vanilla animal it is, each with a short pool of
   * plain fallback names. The name pool is the safety net for when the LLM is
   * down or its answer is unusable: a pet is always named, LLM or no.
   */
  public enum Species {
    DOG(EntityType.WOLF, "dog", List.of("Rusty", "Scout", "Bramble", "Fen", "Pippin")),
    CAT(EntityType.CAT, "cat", List.of("Marmalade", "Willow", "Smoke", "Clover", "Juniper"));

    private final EntityType<? extends TamableAnimal> type;
    private final String word;
    private final List<String> fallbackNames;

    Species(EntityType<? extends TamableAnimal> type, String word, List<String> fallbackNames) {
      this.type = type;
      this.word = word;
      this.fallbackNames = fallbackNames;
    }

    public EntityType<? extends TamableAnimal> type() {
      return type;
    }

    /** The everyday word for the animal, for prompts and log lines: "dog", "cat". */
    public String word() {
      return word;
    }

    /** A random plain name from the pool, the default a pet wears until the LLM improves on it. */
    public String randomFallbackName(RandomSource random) {
      return fallbackNames.get(random.nextInt(fallbackNames.size()));
    }
  }

  /**
   * Considers a companion pet for a villager who has just taken a job. The roll
   * is per job (HUNTER always draws a dog; a guard or quartermaster sometimes
   * does), gated so a villager is rolled for a given occupation only once and
   * never keeps two of a species. A pet, once granted, stays with the person
   * through every later job change: the bond is to them, not the post.
   */
  public static void onJobAssigned(ServerLevel level, Village village, RealPerson person, Occupation occupation) {
    Species species = speciesFor(occupation);
    if (species == null) {
      return;
    }

    // Once per occupation: a re-assignment to the same job cannot re-roll its
    // chance, so the tag is written whether the roll passes or fails.
    List<String> rolled = readSet(person.getPersistentData(), PET_ROLLED_OCCUPATIONS_KEY);
    if (rolled.contains(occupation.name())) {
      return;
    }
    rolled.add(occupation.name());
    writeSet(person.getPersistentData(), PET_ROLLED_OCCUPATIONS_KEY, rolled);

    // Never a second of a species, even across different pet-granting jobs.
    List<String> owned = readSet(person.getPersistentData(), PET_SPECIES_OWNED_KEY);
    if (owned.contains(species.name())) {
      return;
    }

    if (level.getRandom().nextDouble() >= chanceFor(occupation)) {
      return;
    }

    spawn(level, village, person, species);
    owned.add(species.name());
    writeSet(person.getPersistentData(), PET_SPECIES_OWNED_KEY, owned);
  }

  /** The species a job can grant, or null for a job that keeps no companion. */
  private static Species speciesFor(Occupation occupation) {
    return switch (occupation) {
      case HUNTER, GUARD -> Species.DOG;
      case QUARTERMASTER -> Species.CAT;
      default -> null;
    };
  }

  /** The chance out of one that this job's roll grants its pet. */
  private static double chanceFor(Occupation occupation) {
    return switch (occupation) {
      case HUNTER -> HUNTER_DOG_CHANCE;
      case GUARD -> GUARD_DOG_CHANCE;
      case QUARTERMASTER -> QUARTERMASTER_CAT_CHANCE;
      default -> 0.0D;
    };
  }

  /**
   * Creates a tamed pet at the owner's side and adds it to the world, which fires
   * the join event where its goals attach. Everything set here is vanilla and
   * auto-syncs to clients: tamed, owned, no-despawn, a random coat, collar, and a
   * random fallback name. The LLM is then asked to improve on the name, collar,
   * and coat ({@link PetNaming}); its answer, when it lands, replaces the
   * defaults, and when it does not, the defaults simply stand.
   */
  private static void spawn(ServerLevel level, Village village, RealPerson owner, Species species) {
    TamableAnimal pet = species.type().create(level);
    if (pet == null) {
      return;
    }

    RandomSource random = level.getRandom();
    double offsetX = (random.nextDouble() - 0.5D) * 2.0D;
    double offsetZ = (random.nextDouble() - 0.5D) * 2.0D;
    pet.moveTo(owner.getX() + offsetX, owner.getY(), owner.getZ() + offsetZ, random.nextFloat() * 360.0F, 0.0F);

    pet.setTame(true, true);
    pet.setOwnerUUID(owner.getUUID());
    pet.setPersistenceRequired();

    CompoundTag data = pet.getPersistentData();
    data.putBoolean(COMPANION_PET_KEY, true);
    data.putUUID(PET_OWNER_KEY, owner.getUUID());
    data.putString(PET_VILLAGE_KEY, village.getID());

    DyeColor collar = DyeColor.byId(random.nextInt(DyeColor.values().length));
    String name = species.randomFallbackName(random);
    applyLook(level, pet, species, name, collar, randomVariant(level, species));
    pet.setCustomNameVisible(true);

    level.addFreshEntity(pet);

    Villagelife.LOGGER.info("[companion pet] {} takes in a {} named {}", owner.getFullName(), species.word(), name);

    PetNaming.decide(owner, species, pet).whenComplete((decision, error) -> {
      if (error != null || decision == null || decision.isEmpty()) {
        return;
      }
      level.getServer().execute(() -> {
        if (pet.isRemoved()) {
          return;
        }
        PetNaming.PetDecision chosen = decision.get();
        applyLook(level, pet, species, chosen.name(), chosen.collar(), chosen.variant());
        Villagelife.LOGGER.info("[companion pet] {} settles on {} for their {}", owner.getFullName(),
            chosen.name(), species.word());
      });
    });
  }

  /**
   * Writes a pet's name, collar, and coat in one place, so the random defaults
   * and the LLM's later choice go on the same way. The variant is species-typed,
   * so it is looked up in the matching registry and only applied when it resolves.
   */
  private static void applyLook(ServerLevel level, TamableAnimal pet, Species species, String name, DyeColor collar,
      ResourceLocation variant) {
    pet.setCustomName(Component.literal(name));
    if (pet instanceof Wolf wolf) {
      wolf.setCollarColor(collar);
      if (variant != null) {
        level.registryAccess().registryOrThrow(Registries.WOLF_VARIANT).getHolder(variant).ifPresent(wolf::setVariant);
      }
    } else if (pet instanceof Cat cat) {
      cat.setCollarColor(collar);
      if (variant != null) {
        level.registryAccess().registryOrThrow(Registries.CAT_VARIANT).getHolder(variant).ifPresent(cat::setVariant);
      }
    }
  }

  /** A random coat key for the species, or null when the registry somehow holds none. */
  private static ResourceLocation randomVariant(ServerLevel level, Species species) {
    if (species == Species.DOG) {
      Optional<Holder.Reference<WolfVariant>> holder =
          level.registryAccess().registryOrThrow(Registries.WOLF_VARIANT).getRandom(level.getRandom());
      return holder.map(reference -> reference.key().location()).orElse(null);
    }
    Optional<Holder.Reference<CatVariant>> holder =
        level.registryAccess().registryOrThrow(Registries.CAT_VARIANT).getRandom(level.getRandom());
    return holder.map(reference -> reference.key().location()).orElse(null);
  }

  /**
   * Sits a pet on command, or stands it back up. Standing up while the owner is
   * far off snaps the pet to their side (mirroring vanilla's teleport-to-owner),
   * so a pet ordered to stay does not have to walk back across the world. The
   * trigger for this is a later phase; only the mechanics live here.
   */
  public static void commandSit(TamableAnimal pet, boolean sit) {
    if (sit) {
      pet.setOrderedToSit(true);
      pet.setInSittingPose(true);
      return;
    }
    pet.setOrderedToSit(false);
    pet.setInSittingPose(false);
    RealPerson owner = loadedOwner(pet);
    if (owner != null && pet.distanceToSqr(owner) > UNSIT_TELEPORT_DISTANCE * UNSIT_TELEPORT_DISTANCE) {
      teleportNear(pet, owner);
    }
  }

  /**
   * Snaps a pet to a safe spot beside its owner. Mirrors vanilla's own
   * teleport-to-owner: try a handful of offsets around the owner, take the first
   * that is walkable and clear, and give up rather than drop the pet somewhere
   * unsafe. Shared by the follow goal and the unsit snap, so the safe-spot rule
   * lives once.
   */
  public static void teleportNear(TamableAnimal pet, RealPerson owner) {
    BlockPos around = owner.blockPosition();
    RandomSource random = pet.getRandom();
    for (int attempt = 0; attempt < 10; attempt++) {
      int dx = random.nextIntBetweenInclusive(-3, 3);
      int dz = random.nextIntBetweenInclusive(-3, 3);
      if (Math.abs(dx) < 2 && Math.abs(dz) < 2) {
        continue;
      }
      int dy = random.nextIntBetweenInclusive(-1, 1);
      BlockPos target = new BlockPos(around.getX() + dx, around.getY() + dy, around.getZ() + dz);
      if (isSafeSpot(pet, target)) {
        pet.moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, pet.getYRot(), pet.getXRot());
        pet.getNavigation().stop();
        return;
      }
    }
  }

  /** Whether a pet may stand at a block: walkable ground with room for its body. */
  private static boolean isSafeSpot(TamableAnimal pet, BlockPos pos) {
    if (WalkNodeEvaluator.getPathTypeStatic(pet, pos) != PathType.WALKABLE) {
      return false;
    }
    BlockPos shift = pos.subtract(pet.blockPosition());
    return pet.level().noCollision(pet, pet.getBoundingBox().move(shift));
  }

  /**
   * The nearest companion pet this villager owns within {@code radius}, or null.
   * The owner-to-pet lookup, kept here beside the pet-to-owner one so both ends
   * of the bond resolve in the same home. Mirrors {@code HerdStep.select}: scan a
   * box, keep only what passes the ownership predicate, take the nearest.
   */
  public static TamableAnimal findOwnedPet(RealPerson owner, double radius) {
    AABB box = owner.getBoundingBox().inflate(radius);
    TamableAnimal nearest = null;
    double best = Double.MAX_VALUE;
    for (TamableAnimal pet : owner.level().getEntitiesOfClass(TamableAnimal.class, box,
        candidate -> candidate.isAlive() && isCompanionPet(candidate)
            && ownerUuid(candidate).map(id -> id.equals(owner.getUUID())).orElse(false))) {
      double distance = owner.distanceToSqr(pet);
      if (distance < best) {
        best = distance;
        nearest = pet;
      }
    }
    return nearest;
  }

  /** Whether an entity is one of our companion pets, by its ownership tag. */
  public static boolean isCompanionPet(Entity entity) {
    return entity.getPersistentData().getBoolean(COMPANION_PET_KEY);
  }

  /** The owning villager's UUID, if the pet carries one. */
  public static Optional<UUID> ownerUuid(Entity entity) {
    CompoundTag data = entity.getPersistentData();
    return data.hasUUID(PET_OWNER_KEY) ? Optional.of(data.getUUID(PET_OWNER_KEY)) : Optional.empty();
  }

  /** The owning village's UUID string, if the pet carries one. */
  public static Optional<String> villageUuid(Entity entity) {
    CompoundTag data = entity.getPersistentData();
    return data.contains(PET_VILLAGE_KEY) ? Optional.of(data.getString(PET_VILLAGE_KEY)) : Optional.empty();
  }

  /**
   * The pet's owner as a live, loaded villager, or null. The one place a pet
   * resolves its owner by UUID: the goals and the sit command all ask here, so
   * the getEntity-by-UUID idiom stays in a single spot. Null covers a dead owner,
   * an unloaded one, and a pet on the client side, which is exactly the set of
   * cases where a pet should tether to its village instead of following anyone.
   */
  public static RealPerson loadedOwner(TamableAnimal pet) {
    if (!(pet.level() instanceof ServerLevel serverLevel)) {
      return null;
    }
    Optional<UUID> owner = ownerUuid(pet);
    if (owner.isEmpty()) {
      return null;
    }
    return serverLevel.getEntity(owner.get()) instanceof RealPerson person && person.isAlive() ? person : null;
  }

  /** Reads a comma-joined set of names off persistent data as a mutable list. */
  private static List<String> readSet(CompoundTag data, String key) {
    List<String> values = new ArrayList<>();
    if (!data.contains(key)) {
      return values;
    }
    for (String part : data.getString(key).split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty() && !values.contains(trimmed)) {
        values.add(trimmed);
      }
    }
    return values;
  }

  /** Writes a comma-joined set of names back onto persistent data. */
  private static void writeSet(CompoundTag data, String key, List<String> values) {
    data.putString(key, String.join(",", values));
  }
}
