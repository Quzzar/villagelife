package com.quzzar.villagelife.relationships;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.AgeStage;
import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.entities.genetics.AppearanceGenes;
import com.quzzar.villagelife.persona.PersonaSpawner;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/** The single child-creation seam shared by development tooling and future family gameplay. */
public final class ChildCreationService {

  private ChildCreationService() {
  }

  /**
   * Creates one distinct child through the ordinary persona-before-spawn path.
   * Parents in the same village register the child there; otherwise the child
   * still carries parentage but is not silently inserted into either village.
   */
  public static CompletableFuture<PersonaSpawner.SpawnAttempt> create(
      ServerLevel level, RealPerson firstParent, RealPerson secondParent) {
    BlockPos spawnPosition = childSpawnPosition(level, firstParent, secondParent);
    Village familyVillage = sharedVillage(firstParent, secondParent);
    return PersonaSpawner.trySpawn(level, spawnPosition, child -> {
      applyInheritance(child, firstParent, secondParent);
      if (familyVillage != null) {
        child.setVillage(familyVillage.getID());
        child.setVillageName(familyVillage.getName());
      }
    }).thenApply(attempt -> {
      if (familyVillage != null && attempt.spawned().isPresent()) {
        familyVillage.addBornResident(attempt.spawned().get());
      }
      return attempt;
    });
  }

  /** Applies the inheritance already implemented for appearance, plus parentage and newborn stage. */
  public static void applyInheritance(
      RealPerson child, RealPerson firstParent, RealPerson secondParent) {
    child.setAppearanceSeed(child.getRandom().nextInt(Person.APPEARANCE_SEED_BOUND));
    child.setAppearanceGenes(AppearanceGenes.inherit(
        firstParent.getAppearanceGenes(),
        secondParent.getAppearanceGenes(),
        child.getRandom()));
    child.setParents(firstParent, secondParent);
    child.setLifeStage(AgeStage.TODDLER);
  }

  @Nullable
  public static Village sharedVillage(RealPerson firstParent, RealPerson secondParent) {
    Village first = firstParent.getVillage();
    Village second = secondParent.getVillage();
    return first != null && second != null && first.getID().equals(second.getID()) ? first : null;
  }

  private static BlockPos childSpawnPosition(
      ServerLevel level, RealPerson firstParent, RealPerson secondParent) {
    BlockPos midpoint = BlockPos.containing(
        (firstParent.getX() + secondParent.getX()) * 0.5D,
        (firstParent.getY() + secondParent.getY()) * 0.5D,
        (firstParent.getZ() + secondParent.getZ()) * 0.5D);
    int[][] offsets = {
        {0, 0},
        {0, -1},
        {1, 0},
        {0, 1},
        {-1, 0},
        {1, -1},
        {1, 1},
        {-1, 1},
        {-1, -1}
    };
    for (int[] offset : offsets) {
      BlockPos candidate = midpoint.offset(offset[0], 0, offset[1]);
      AABB body = new AABB(candidate).inflate(0.15D, 0.0D, 0.15D);
      if (level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty()
          && level.getBlockState(candidate.above()).getCollisionShape(level, candidate.above()).isEmpty()
          && level.getEntitiesOfClass(RealPerson.class, body).isEmpty()) {
        return candidate;
      }
    }
    return midpoint;
  }
}
