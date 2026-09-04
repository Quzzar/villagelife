package com.quzzar.kithkyn.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgeStageTest {

  @Test
  void progressesThroughThreeDistinctPreAdultStages() {
    assertEquals(AgeStage.KID, AgeStage.TODDLER.next());
    assertEquals(AgeStage.TEENAGER, AgeStage.KID.next());
    assertEquals(AgeStage.ADULT, AgeStage.TEENAGER.next());
    assertEquals(AgeStage.ADULT, AgeStage.ADULT.next());
  }

  @Test
  void onlyTeenagersAndAdultsCanWork() {
    assertFalse(AgeStage.TODDLER.canWork());
    assertFalse(AgeStage.KID.canWork());
    assertTrue(AgeStage.TEENAGER.canWork());
    assertTrue(AgeStage.ADULT.canWork());
  }

  @Test
  void anIdleTeenagerKeepsCommonwearUntilTheyTakeAJob() {
    assertTrue(AgeStage.TODDLER.usesChildWardrobe(false));
    assertTrue(AgeStage.KID.usesChildWardrobe(false));
    assertTrue(AgeStage.TEENAGER.usesChildWardrobe(false));
    assertFalse(AgeStage.TEENAGER.usesChildWardrobe(true));
    assertFalse(AgeStage.ADULT.usesChildWardrobe(false));
  }

  @Test
  void toddlerAndKidUseYoungProportionsWhileTeenagerUsesASmallerAdultModel() {
    assertEquals(2.05F, 1.95F * AgeStage.TODDLER.scale(), 0.01F);
    assertEquals(2.60F, 1.95F * AgeStage.KID.scale(), 0.01F);
    assertEquals(1.0F, 0.75F * AgeStage.KID.scale(), 0.01F);
    assertTrue(AgeStage.TODDLER.usesYoungModel());
    assertTrue(AgeStage.KID.usesYoungModel());
    assertFalse(AgeStage.TEENAGER.usesYoungModel());
    assertFalse(AgeStage.ADULT.usesYoungModel());
    assertEquals(1.74F, 1.876F * AgeStage.TEENAGER.scale(), 0.01F);
  }

  @Test
  void nameplateAttachmentsFollowEachStagesRenderedHeight() {
    assertEquals(1.13F, 1.95F * AgeStage.TODDLER.dimensionsScale(), 0.01F);
    assertEquals(1.41F, 1.95F * AgeStage.KID.dimensionsScale(), 0.01F);
    assertEquals(1.81F, 1.95F * AgeStage.TEENAGER.dimensionsScale(), 0.01F);
    assertEquals(1.95F, 1.95F * AgeStage.ADULT.dimensionsScale(), 0.01F);
  }

  @Test
  void preAdultNameplatesReceiveTheSameSmallLift() {
    assertEquals(0.10F, AgeStage.TODDLER.nameplateLift(), 0.001F);
    assertEquals(0.10F, AgeStage.KID.nameplateLift(), 0.001F);
    assertEquals(0.10F, AgeStage.TEENAGER.nameplateLift(), 0.001F);
    assertEquals(0.0F, AgeStage.ADULT.nameplateLift(), 0.001F);
  }
}
