package com.quzzar.villagelife.relationships;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.AgeStage;
import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.genetics.AppearanceGenes;
import com.quzzar.villagelife.entities.genetics.StatBlock;
import com.quzzar.villagelife.persona.PersonaSpawner;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/** The single child-creation seam shared by development tooling and future family gameplay. */
public final class ChildCreationService {

  private static final int PARENT_CHILD_BOND = 85;
  private static final int SIBLING_BOND = 75;

  private record BirthGenome(StatBlock stats, int appearanceSeed, AppearanceGenes appearance) {
  }

  private ChildCreationService() {
  }

  /**
   * Creates one distinct child through the ordinary persona-before-spawn path.
   * Parents in the same village register the child there; otherwise the child
   * still carries parentage but is not silently inserted into either village.
   */
  public static CompletableFuture<PersonaSpawner.SpawnAttempt> create(
      ServerLevel level, RealPerson firstParent, RealPerson secondParent) {
    return create(level, firstParent, secondParent, inheritGenome(firstParent, secondParent));
  }

  /** Creates one singleton or a genetically identical set of siblings in sequence. */
  public static CompletableFuture<List<PersonaSpawner.SpawnAttempt>> createBirth(
      ServerLevel level, RealPerson firstParent, RealPerson secondParent, int childCount) {
    int count = Math.max(1, Math.min(3, childCount));
    BirthGenome sharedGenome = inheritGenome(firstParent, secondParent);
    List<PersonaSpawner.SpawnAttempt> attempts = new ArrayList<>(count);
    CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
    for (int child = 0; child < count; child++) {
      chain = chain.thenCompose(ignored -> create(level, firstParent, secondParent, sharedGenome)
          .thenAccept(attempts::add));
    }
    return chain.thenApply(ignored -> List.copyOf(attempts));
  }

  private static CompletableFuture<PersonaSpawner.SpawnAttempt> create(
      ServerLevel level, RealPerson firstParent, RealPerson secondParent, BirthGenome genome) {
    Optional<BlockPos> spawnPosition = childSpawnPosition(level, firstParent, secondParent);
    if (spawnPosition.isEmpty()) {
      Villagelife.LOGGER.warn(
          "[family] birth postponed because no safe spawn position exists near '{}' and '{}'",
          firstParent.getFullName(), secondParent.getFullName());
      return CompletableFuture.completedFuture(new PersonaSpawner.SpawnAttempt(
          "", "", genome.stats(), Optional.empty(), Optional.empty()));
    }
    Village familyVillage = sharedVillage(firstParent, secondParent);
    return PersonaSpawner.trySpawn(level, spawnPosition.get(), child -> {
      applyInheritance(child, firstParent, secondParent, genome);
      if (familyVillage != null) {
        child.setVillage(familyVillage.getID());
        child.setVillageName(familyVillage.getName());
      }
    }).thenApply(attempt -> {
      if (familyVillage != null && attempt.spawned().isPresent()) {
        RealPerson child = attempt.spawned().get();
        familyVillage.addBornResident(child);
        initializeFamilyRelationships(familyVillage, level, child);
      }
      return attempt;
    });
  }

  /** Applies mechanical and appearance inheritance, parentage, and the newborn stage. */
  public static void applyInheritance(
      RealPerson child, RealPerson firstParent, RealPerson secondParent) {
    applyInheritance(child, firstParent, secondParent, inheritGenome(firstParent, secondParent));
  }

  private static void applyInheritance(
      RealPerson child, RealPerson firstParent, RealPerson secondParent, BirthGenome genome) {
    child.setStatBlock(genome.stats());
    child.setAppearanceSeed(genome.appearanceSeed());
    child.setAppearanceGenes(genome.appearance());
    child.setParents(firstParent, secondParent);
    child.takeHouseholdSurname(householdSurname(firstParent, secondParent));
    child.setLifeStage(AgeStage.TODDLER);
    child.setHealth(child.getMaxHealth());
  }

  private static BirthGenome inheritGenome(RealPerson firstParent, RealPerson secondParent) {
    StatBlock inheritedStats = StatBlock.inherit(
        firstParent.getStatBlock(), secondParent.getStatBlock(), firstParent.getRandom()::nextLong);
    AppearanceGenes inheritedAppearance = AppearanceGenes.inherit(
        firstParent.getAppearanceGenes(), secondParent.getAppearanceGenes(), firstParent.getRandom());
    return new BirthGenome(
        inheritedStats,
        firstParent.getRandom().nextInt(Person.APPEARANCE_SEED_BOUND),
        inheritedAppearance);
  }

  private static String householdSurname(RealPerson firstParent, RealPerson secondParent) {
    if (firstParent.getLastName().equals(secondParent.getLastName())) {
      return firstParent.getLastName();
    }
    return firstParent.getUUID().compareTo(secondParent.getUUID()) <= 0
        ? firstParent.getLastName() : secondParent.getLastName();
  }

  private static void initializeFamilyRelationships(
      Village village, ServerLevel level, RealPerson child) {
    for (java.util.UUID parent : child.getParentIds()) {
      village.putRelationship(RelationshipPair.create(
          child.getUUID(), parent, PARENT_CHILD_BOND, 0, 0, false, "close family"));
    }
    for (java.util.UUID residentId : village.getPopulation()) {
      if (residentId.equals(child.getUUID()) || child.getParentIds().contains(residentId)) {
        continue;
      }
      RealPerson resident = village.getPerson(level, residentId);
      if (resident == null || resident.getParentIds().stream().noneMatch(child.getParentIds()::contains)) {
        continue;
      }
      village.putRelationship(RelationshipPair.create(
          child.getUUID(), residentId, SIBLING_BOND, 0, 0, false, "close family"));
    }
  }

  @Nullable
  public static Village sharedVillage(RealPerson firstParent, RealPerson secondParent) {
    Village first = firstParent.getVillage();
    Village second = secondParent.getVillage();
    return first != null && second != null && first.getID().equals(second.getID()) ? first : null;
  }

  private static Optional<BlockPos> childSpawnPosition(
      ServerLevel level, RealPerson firstParent, RealPerson secondParent) {
    BlockPos midpoint = BlockPos.containing(
        (firstParent.getX() + secondParent.getX()) * 0.5D,
        (firstParent.getY() + secondParent.getY()) * 0.5D,
        (firstParent.getZ() + secondParent.getZ()) * 0.5D);
    return findNearbySpawnPosition(midpoint, candidate -> isSafeSpawnPosition(level, candidate));
  }

  static Optional<BlockPos> findNearbySpawnPosition(
      BlockPos midpoint, Predicate<BlockPos> isSafe) {
    int[] verticalOffsets = {0, 1, -1, 2, -2, 3, -3};
    for (int verticalOffset : verticalOffsets) {
      for (int radius = 0; radius <= 2; radius++) {
        for (int xOffset = -radius; xOffset <= radius; xOffset++) {
          for (int zOffset = -radius; zOffset <= radius; zOffset++) {
            if (Math.max(Math.abs(xOffset), Math.abs(zOffset)) != radius) {
              continue;
            }
            BlockPos candidate = midpoint.offset(xOffset, verticalOffset, zOffset);
            if (isSafe.test(candidate)) {
              return Optional.of(candidate);
            }
          }
        }
      }
    }
    return Optional.empty();
  }

  private static boolean isSafeSpawnPosition(ServerLevel level, BlockPos feet) {
    BlockPos head = feet.above();
    BlockPos ground = feet.below();
    if (!level.hasChunkAt(feet)
        || ground.getY() < level.getMinBuildHeight()
        || head.getY() >= level.getMaxBuildHeight()) {
      return false;
    }
    AABB body = new AABB(
        feet.getX() + 0.15D,
        feet.getY(),
        feet.getZ() + 0.15D,
        feet.getX() + 0.85D,
        feet.getY() + 1.8D,
        feet.getZ() + 0.85D);
    return level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)
        && level.getFluidState(feet).isEmpty()
        && level.getFluidState(head).isEmpty()
        && level.noCollision(body)
        && level.getEntitiesOfClass(RealPerson.class, body).isEmpty();
  }
}
