package com.quzzar.kithkyn.village.buildings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.quzzar.kithkyn.village.Occupation;

class RedevelopmentDemandTest {
  @Test
  void excessHousingAndStorageCannotAuthorizeRemovalJustForAHigherLevel() {
    var needs = new RedevelopmentDemand.Needs(0, false, false, 0, Set.of(), Set.of("STORAGE"));
    assertTrue(RedevelopmentDemand.reason(needs,
        new RedevelopmentDemand.Change(1, 0, Map.of(), Set.of())).isEmpty());
    assertTrue(RedevelopmentDemand.reason(needs,
        new RedevelopmentDemand.Change(-1, 4, Map.of(Occupation.QUARTERMASTER, 1), Set.of("STORAGE"))).isEmpty());
  }

  @Test
  void actualHousingAndStorageShortagesRemainEligible() {
    var housing = new RedevelopmentDemand.Needs(2, false, false, 0, Set.of(), Set.of("STORAGE"));
    assertFalse(RedevelopmentDemand.reason(housing,
        new RedevelopmentDemand.Change(1, -2, Map.of(), Set.of())).isEmpty());
    var storage = new RedevelopmentDemand.Needs(0, true, false, 0, Set.of(), Set.of("STORAGE"));
    assertFalse(RedevelopmentDemand.reason(storage,
        new RedevelopmentDemand.Change(0, 3, Map.of(), Set.of("STORAGE"))).isEmpty());
  }

  @Test
  void existingVacanciesMustBeUsedBeforeRemovingBuildingsForMoreOfTheSameJobs() {
    var needs = new RedevelopmentDemand.Needs(0, false, false, 2,
        Set.of(Occupation.QUARTERMASTER), Set.of("STORAGE"));
    assertTrue(RedevelopmentDemand.reason(needs,
        new RedevelopmentDemand.Change(0, 4, Map.of(Occupation.QUARTERMASTER, 1), Set.of("STORAGE"))).isEmpty());
  }

  @Test
  void additionalFoodPostsNeedLowFoodAndNoExistingVacancy() {
    var change = new RedevelopmentDemand.Change(0, 0, Map.of(Occupation.FARMER, 1), Set.of("GRAIN"));
    assertTrue(RedevelopmentDemand.reason(new RedevelopmentDemand.Needs(0, false, false, 2,
        Set.of(), Set.of("GRAIN")), change).isEmpty());
    assertTrue(RedevelopmentDemand.reason(new RedevelopmentDemand.Needs(0, false, true, 2,
        Set.of(Occupation.FARMER), Set.of("GRAIN")), change).isEmpty());
    assertFalse(RedevelopmentDemand.reason(new RedevelopmentDemand.Needs(0, false, true, 2,
        Set.of(), Set.of("GRAIN")), change).isEmpty());
  }

  @Test
  void anImplementedMissingCapabilityCanStillJustifyDevelopment() {
    var needs = new RedevelopmentDemand.Needs(0, false, false, 0, Set.of(), Set.of());
    assertFalse(RedevelopmentDemand.reason(needs,
        new RedevelopmentDemand.Change(0, 0, Map.of(), Set.of("WATER"))).isEmpty());
    assertTrue(RedevelopmentDemand.reason(needs,
        new RedevelopmentDemand.Change(0, 4, Map.of(), Set.of("STORAGE"))).isEmpty());
  }
  @Test
  void moreCropAreaCanMeetLowFoodEvenWithoutAddingFarmerPosts() {
    var change = new RedevelopmentDemand.Change(0, 0, Map.of(Occupation.FARMER, 0), Set.of("GRAIN"), 40);
    assertFalse(RedevelopmentDemand.reason(new RedevelopmentDemand.Needs(0, false, true, 0,
        Set.of(), Set.of("GRAIN")), change).isEmpty());
    assertTrue(RedevelopmentDemand.reason(new RedevelopmentDemand.Needs(0, false, false, 0,
        Set.of(), Set.of("GRAIN")), change).isEmpty());
  }
}
