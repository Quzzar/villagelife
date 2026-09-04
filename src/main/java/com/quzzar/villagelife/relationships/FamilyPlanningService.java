package com.quzzar.villagelife.relationships;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.entities.AgeStage;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.persona.PersonaSpawner;
import com.quzzar.villagelife.village.FamilyPlans;
import com.quzzar.villagelife.village.Village;

import net.minecraft.server.level.ServerLevel;

/** Schedules married couples' conversations and turns mutual agreement into births. */
public final class FamilyPlanningService {

  public static final int FAMILY_INTERVAL_SECONDS = 60;

  private FamilyPlanningService() {
  }

  /** One slow family pass: reconcile plans, deliver due births, then convene one due couple. */
  public static void tick(Village village, ServerLevel level) {
    reconcilePlans(village, level);
    if (village.isFamilyDecisionPending()) {
      return;
    }
    long today = day(level);
    Optional<FamilyPlans.Plan> birth = FamilyPlans.current(village).stream()
        .filter(FamilyPlans.Plan::hasPendingBirth)
        .filter(plan -> plan.birthDay() <= today)
        .filter(plan -> eligible(village, level, plan.first(), plan.second()))
        .min(Comparator.comparingLong(FamilyPlans.Plan::birthDay).thenComparing(FamilyPlans.Plan::key));
    if (birth.isPresent() && level.isDay()) {
      deliver(village, level, birth.get());
      return;
    }
    FamilyPlans.current(village).stream()
        .filter(plan -> !plan.hasPendingBirth())
        .filter(plan -> plan.nextTalkDay() <= today)
        .filter(plan -> eligible(village, level, plan.first(), plan.second()))
        .min(Comparator.comparingLong(FamilyPlans.Plan::nextTalkDay).thenComparing(FamilyPlans.Plan::key))
        .ifPresent(plan -> convene(village, level, plan));
  }

  /** Developer seam: run the real conversation now and apply its scheduling result. */
  public static CompletableFuture<Optional<FamilyPlanningDialogue.Decision>> considerNow(
      Village village, ServerLevel level, RealPerson first, RealPerson second) {
    String problem = eligibilityProblem(village, first, second);
    if (problem != null || village.isFamilyDecisionPending()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    FamilyPlans.Plan plan = FamilyPlans.ensure(village, first.getUUID(), second.getUUID(), day(level));
    return convene(village, level, plan);
  }

  /** Developer seam: create an explicitly sized birth without a family-planning roll. */
  public static CompletableFuture<List<PersonaSpawner.SpawnAttempt>> birthNow(
      ServerLevel level, RealPerson first, RealPerson second, BirthMultiplicity multiplicity) {
    return ChildCreationService.createBirth(level, first, second, multiplicity.children());
  }

  /** Why this pair cannot consider a child in ordinary gameplay, or null when eligible. */
  public static String eligibilityProblem(Village village, RealPerson first, RealPerson second) {
    if (first == second) {
      return "a family needs two different parents";
    }
    if (first.getLifeStage() != AgeStage.ADULT || second.getLifeStage() != AgeStage.ADULT) {
      return "both parents must be adults";
    }
    if (!village.hasResident(first.getUUID()) || !village.hasResident(second.getUUID())) {
      return "both parents must live in the same village";
    }
    if (FamilyKinship.areCloseFamily(first, second)) {
      return "close relatives cannot form a family together";
    }
    RelationshipPair pair = village.getRelationship(first.getUUID(), second.getUUID());
    if (pair == null || !pair.married()
        || !first.getUUID().equals(second.getSpouseId())
        || !second.getUUID().equals(first.getSpouseId())) {
      return "the parents must be married to each other";
    }
    if (!sharesHome(village, first.getUUID(), second.getUUID())) {
      return "the married couple must share a completed home";
    }
    return null;
  }

  private static void reconcilePlans(Village village, ServerLevel level) {
    Set<String> married = new HashSet<>();
    long firstTalk = day(level) + Math.max(0, VillagelifeConfig.FamilyFirstTalkDelayDays);
    for (RelationshipPair pair : village.marriedPairs()) {
      married.add(pair.key());
      RealPerson first = village.getPerson(level, pair.personA());
      RealPerson second = village.getPerson(level, pair.personB());
      if (first != null && second != null && eligibilityProblem(village, first, second) == null) {
        FamilyPlans.ensure(village, pair.personA(), pair.personB(), firstTalk);
      }
    }
    FamilyPlans.retainMarriages(village, married);
  }

  private static boolean eligible(
      Village village, ServerLevel level, UUID firstId, UUID secondId) {
    RealPerson first = village.getPerson(level, firstId);
    RealPerson second = village.getPerson(level, secondId);
    return first != null && second != null && eligibilityProblem(village, first, second) == null;
  }

  private static CompletableFuture<Optional<FamilyPlanningDialogue.Decision>> convene(
      Village village, ServerLevel level, FamilyPlans.Plan plan) {
    RealPerson first = village.getPerson(level, plan.first());
    RealPerson second = village.getPerson(level, plan.second());
    if (first == null || second == null || village.isFamilyDecisionPending()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    village.setFamilyDecisionPending(true);
    Villagelife.LOGGER.info("[family] '{}' and '{}' discuss having a child in '{}'",
        first.getFullName(), second.getFullName(), village.getName());
    CompletableFuture<Optional<FamilyPlanningDialogue.Decision>> completed = new CompletableFuture<>();
    FamilyPlanningDialogue.decide(village, first, second).whenComplete((decision, error) ->
        level.getServer().execute(() -> {
          village.setFamilyDecisionPending(false);
          long today = day(level);
          if (error != null || decision == null || decision.isEmpty()) {
            FamilyPlans.scheduleTalk(village, plan.first(), plan.second(),
                today + Math.max(1, VillagelifeConfig.FamilyTalkRetryDays));
            if (error != null) {
              Villagelife.LOGGER.error("[family] family-planning conversation failed", error);
            }
            completed.complete(Optional.empty());
            return;
          }
          if (decision.get() == FamilyPlanningDialogue.Decision.WANT_CHILD) {
            FamilyPlans.scheduleBirth(village, plan.first(), plan.second(), today + 1L);
            Villagelife.LOGGER.info("[family] '{}' and '{}' want a child; the birth is due next morning",
                first.getFullName(), second.getFullName());
          } else {
            FamilyPlans.scheduleTalk(village, plan.first(), plan.second(),
                today + Math.max(1, VillagelifeConfig.FamilyTalkRetryDays));
            Villagelife.LOGGER.info("[family] '{}' and '{}' decide not to have a child yet",
                first.getFullName(), second.getFullName());
          }
          completed.complete(decision);
        }));
    return completed;
  }

  private static void deliver(Village village, ServerLevel level, FamilyPlans.Plan plan) {
    RealPerson first = village.getPerson(level, plan.first());
    RealPerson second = village.getPerson(level, plan.second());
    if (first == null || second == null) {
      return;
    }
    BirthMultiplicity multiplicity = BirthMultiplicity.roll(
        level.random::nextLong, VillagelifeConfig.TwinBirthChance, VillagelifeConfig.TripletBirthChance);
    village.setFamilyDecisionPending(true);
    ChildCreationService.createBirth(level, first, second, multiplicity.children())
        .whenComplete((attempts, error) -> level.getServer().execute(() -> {
          village.setFamilyDecisionPending(false);
          long today = day(level);
          long retry = today + 1L;
          if (error != null || attempts == null) {
            FamilyPlans.scheduleBirth(village, plan.first(), plan.second(), retry);
            Villagelife.LOGGER.error("[family] birth failed for '{}' and '{}'",
                first.getFullName(), second.getFullName(), error);
            return;
          }
          long born = attempts.stream().filter(attempt -> attempt.spawned().isPresent()).count();
          if (born == 0) {
            FamilyPlans.scheduleBirth(village, plan.first(), plan.second(), retry);
            Villagelife.LOGGER.warn("[family] no child persona completed; the birth will retry tomorrow");
            return;
          }
          FamilyPlans.scheduleTalk(village, plan.first(), plan.second(),
              today + Math.max(1, VillagelifeConfig.FamilyBirthCooldownDays));
          Villagelife.LOGGER.info("[family] '{}' and '{}' welcomed {} {} in '{}'",
              first.getFullName(), second.getFullName(), born,
              born == 1 ? "child" : "identical siblings", village.getName());
        }));
  }

  private static boolean sharesHome(Village village, UUID first, UUID second) {
    var firstBed = village.getBedAssignment(first);
    var secondBed = village.getBedAssignment(second);
    return firstBed != null && secondBed != null
        && firstBed.getBuildingUUID().equals(secondBed.getBuildingUUID());
  }

  private static long day(ServerLevel level) {
    return Math.floorDiv(level.getDayTime(), 24_000L);
  }
}
