package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.CompanionPets;

import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * A companion pet heeling to its villager owner. Vanilla's own owner-follow only
 * ever resolves a player as owner ({@code TamableAnimal.getOwner()}), so a pet
 * belonging to a villager would never follow anyone; this supplies that half, the
 * same shape as {@link FollowFamilyGoal} keeping a household together on the road.
 *
 * <p>It closes the gap once the pet has fallen behind and yields the moment it is
 * back at heel, so the pet can mill about its owner (its own vanilla RandomStroll)
 * rather than stand rigidly on top of them. A pet ordered to sit stays put, and a
 * pet whose owner is gone from the loaded world follows no one, handing the pet to
 * {@link PetVillageTetherGoal}. If the owner outruns the pet entirely, the pet
 * snaps to their side rather than path forever ({@link CompanionPets#teleportNear}).
 */
public class PetFollowOwnerGoal extends Goal {

  /** A touch quicker than a strolling villager, so the pet closes the gap. */
  private static final double SPEED = 1.15D;

  /** Fall in this close and yield; drift past the far mark and start closing again. */
  private static final double KEEP_WITHIN = 2.5D;
  private static final double FALL_BEHIND = 5.0D;

  /** Beyond this the pet stops pathing and teleports to the owner's side. */
  private static final double TELEPORT_DISTANCE = 12.0D;

  /** Goal ticks between re-issuing the path to a moving owner. */
  private static final int REPATH_EVERY = 10;

  private final TamableAnimal pet;
  private RealPerson owner;
  private int ticks;

  public PetFollowOwnerGoal(TamableAnimal pet) {
    this.pet = pet;
    this.setFlags(EnumSet.of(Goal.Flag.MOVE));
  }

  @Override
  public boolean canUse() {
    if (pet.isOrderedToSit()) {
      return false;
    }
    RealPerson master = CompanionPets.loadedOwner(pet);
    // Only close the gap once the pet has fallen behind; at heel this yields so
    // the pet's own strolling can mill around its owner.
    if (master == null || pet.distanceToSqr(master) < FALL_BEHIND * FALL_BEHIND) {
      return false;
    }
    this.owner = master;
    return true;
  }

  @Override
  public boolean canContinueToUse() {
    return !pet.isOrderedToSit() && owner != null && owner.isAlive()
        && pet.distanceToSqr(owner) > KEEP_WITHIN * KEEP_WITHIN;
  }

  @Override
  public void start() {
    ticks = 0;
    pet.getNavigation().moveTo(owner, SPEED);
  }

  @Override
  public void stop() {
    this.owner = null;
    pet.getNavigation().stop();
  }

  @Override
  public void tick() {
    if (owner == null) {
      return;
    }
    pet.getLookControl().setLookAt(owner, 10.0F, pet.getMaxHeadXRot());
    if (pet.distanceToSqr(owner) > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
      CompanionPets.teleportNear(pet, owner);
      return;
    }
    if (++ticks % REPATH_EVERY == 0 || pet.getNavigation().isDone()) {
      pet.getNavigation().moveTo(owner, SPEED);
    }
  }
}
