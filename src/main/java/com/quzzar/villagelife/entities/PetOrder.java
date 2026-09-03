package com.quzzar.villagelife.entities;

import java.util.List;
import java.util.Optional;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.llm.LlmDecision;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.village.CompanionPets;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Wolf;

/**
 * Puts a small choice to a pet owner's own brain: sit their companion down and
 * have it stay, or call it back to their side, or leave it exactly as it is.
 * Mirrors {@link CraftOffer}'s take-or-skip shape, with one deliberate inversion:
 * silence here is a NO-OP, not an action. A mute or absent model must never move
 * a pet, so only an explicit first-option choice acts; an empty answer, or the
 * "leave it" second option, leaves the pet where it stands. The caller
 * ({@code RealPerson.maybeOrderPet}) decides WHEN to ask, once a day when a pet
 * is near; this only carries the ask and the hands, per the decide() pattern of
 * docs/llm-brain.md.
 */
public final class PetOrder {

  private PetOrder() {
  }

  /**
   * Offers the owner the choice over their pet and lands the answer back on the
   * main thread. The pet's posture is read now and carried through, so the answer
   * acts on the state the question was actually asked about, however long the
   * model took.
   */
  public static void offer(RealPerson owner, TamableAnimal pet, String situation) {
    boolean sitting = pet.isOrderedToSit();
    LlmService llm = LlmService.get();
    MinecraftServer server = owner.getServer();
    if (!llm.isReady() || server == null) {
      return; // no brain to ask, so the pet stays exactly as it is
    }
    String name = pet.getName().getString();
    List<String> options = sitting
        ? List.of("Call " + name + " back to your side", "Let " + name + " keep resting where it is")
        : List.of("Have " + name + " sit and stay here", "Let " + name + " keep following you");
    String purpose = owner.getFirstName() + " sees to their " + petWord(pet);
    llm.decide(purpose, situation, options).whenComplete((decision, error) -> {
      if (error != null) {
        Villagelife.LOGGER.error("'{}' could not weigh what to do with {}", owner.getFullName(), name, error);
      }
      Optional<LlmDecision> settled = decision == null ? Optional.empty() : decision;
      server.execute(() -> finish(owner, pet, sitting, settled));
    });
  }

  /**
   * Acts on the owner's choice, or does nothing. The default is no-op: an empty
   * answer (model down, timed out, unparsed) or the "leave it" second option
   * leaves the pet untouched. Only the first option moves it, sitting it when it
   * was following, and calling it back when it was sat.
   */
  private static void finish(RealPerson owner, TamableAnimal pet, boolean sitting, Optional<LlmDecision> decision) {
    if (decision.isEmpty() || decision.get().choiceIndex() == 1) {
      return;
    }
    if (pet.isRemoved() || !pet.isAlive()) {
      return;
    }
    CompanionPets.commandSit(pet, !sitting);
    Villagelife.LOGGER.info("'{}' {} {}: {}", owner.getFullName(),
        sitting ? "calls back" : "sits down", pet.getName().getString(), decision.get().reason());
  }

  /** The everyday word for the animal, for the call-log purpose line. */
  private static String petWord(TamableAnimal pet) {
    if (pet instanceof Wolf) {
      return "dog";
    }
    if (pet instanceof Cat) {
      return "cat";
    }
    return "pet";
  }
}
