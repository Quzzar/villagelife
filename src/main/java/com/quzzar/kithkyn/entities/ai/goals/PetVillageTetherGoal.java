package com.quzzar.kithkyn.entities.ai.goals;

import java.util.EnumSet;
import java.util.Optional;

import com.quzzar.kithkyn.village.CompanionPets;
import com.quzzar.kithkyn.village.Village;
import com.quzzar.kithkyn.village.VillageManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

/**
 * A masterless companion pet drifting home. When a pet has no loaded, living
 * owner, whether the owner died, or is simply away, it belongs to its village
 * rather than to no one, so it wanders back toward the gathering point when it has
 * strayed too far. Within the tether it does nothing, leaving the pet's own
 * vanilla RandomStroll to mill about the village center.
 *
 * <p>This needs no death hook: a dead owner's UUID never resolves to a live
 * villager again ({@link CompanionPets#loadedOwner}), so the pet permanently falls
 * to this goal and stays tied to its village. It sits below the follow goal in
 * priority, so a pet with its owner nearby heels to them and only tethers when
 * there is no one to heel to.
 */
public class PetVillageTetherGoal extends Goal {

  /** Strayed beyond this from the village center, a masterless pet heads back. */
  private static final double TETHER = 20.0D;

  /** An easy amble home; the pet is not in a hurry. */
  private static final double SPEED = 1.0D;

  private final TamableAnimal pet;
  private Vec3 target;

  public PetVillageTetherGoal(TamableAnimal pet) {
    this.pet = pet;
    this.setFlags(EnumSet.of(Goal.Flag.MOVE));
  }

  @Override
  public boolean canUse() {
    // Only a pet with no one to heel to tethers; a loaded owner means the follow
    // goal is in charge.
    if (CompanionPets.loadedOwner(pet) != null) {
      return false;
    }
    BlockPos center = villageCenter();
    if (center == null || center.closerToCenterThan(pet.position(), TETHER)) {
      return false;
    }
    Vec3 toward = LandRandomPos.getPosTowards(pet, 10, 7, Vec3.atBottomCenterOf(center));
    if (toward == null) {
      return false;
    }
    this.target = toward;
    return true;
  }

  @Override
  public boolean canContinueToUse() {
    return target != null && !pet.getNavigation().isDone() && CompanionPets.loadedOwner(pet) == null;
  }

  @Override
  public void start() {
    pet.getNavigation().moveTo(target.x, target.y, target.z, SPEED);
  }

  @Override
  public void stop() {
    this.target = null;
    pet.getNavigation().stop();
  }

  /** The pet's home village center, or null when its village cannot be resolved. */
  private BlockPos villageCenter() {
    if (!(pet.level() instanceof ServerLevel serverLevel)) {
      return null;
    }
    Optional<String> villageUuid = CompanionPets.villageUuid(pet);
    if (villageUuid.isEmpty()) {
      return null;
    }
    Village village = VillageManager.get(serverLevel).getVillage(villageUuid.get());
    if (village == null) {
      return null;
    }
    BlockPos center = village.getGatheringPoint();
    return center == null || center.equals(BlockPos.ZERO) ? null : center;
  }
}
