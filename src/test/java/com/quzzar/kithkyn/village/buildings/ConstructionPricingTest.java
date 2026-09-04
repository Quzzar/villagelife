package com.quzzar.kithkyn.village.buildings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConstructionPricingTest {

  @Test
  void freshHigherLevelCombinesTheBaseAndEveryUpgradeIncrease() {
    Map<String, Integer> combined = CostSequence.combine(List.of(
        Map.of("logs", 8, "stone", 10),
        Map.of("logs", CostSequence.increase(8, 16),
            "stone", CostSequence.increase(10, 20)),
        Map.of("logs", CostSequence.increase(16, 30),
            "stone", CostSequence.increase(20, 35))));

    assertEquals(30, combined.get("logs"));
    assertEquals(35, combined.get("stone"));
  }

  @Test
  void anUpgradePaysOnlyPositiveIncreases() {
    assertEquals(8, CostSequence.increase(8, 16));
    assertEquals(0, CostSequence.increase(28, 18));

    Map<String, Integer> fresh = CostSequence.combine(List.of(
        Map.of("logs", 28),
        Map.of("logs", CostSequence.increase(28, 18))));
    assertEquals(28, fresh.get("logs"));
  }
}
