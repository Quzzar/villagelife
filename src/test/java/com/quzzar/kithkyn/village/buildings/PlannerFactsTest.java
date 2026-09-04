package com.quzzar.kithkyn.village.buildings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PlannerFactsTest {

  @Test
  void namesWhatAlreadyStandsAndWhichTradesAreStillOpen() {
    Map<String, Integer> buildings = new LinkedHashMap<>();
    buildings.put("farm", 1);
    buildings.put("fishery", 2);
    Map<String, Integer> posts = new LinkedHashMap<>();
    posts.put("farmer", 1);
    posts.put("fisher", 2);

    assertEquals("Already standing: farm x1, fishery x2. ",
        PlannerFacts.existingBuildings(buildings));
    assertEquals("Open work: farmer x1, fisher x2. ", PlannerFacts.openPosts(posts));
  }

  @Test
  void explainsWhyHomelessResidentsCannotFillAnotherBedlessWorkshop() {
    assertEquals("2 adult residents are already unhoused. They cannot take open work unless that workplace "
            + "has a free live-in bed; another workshop without beds would not house them, while "
            + "a house would add general beds. ",
        PlannerFacts.housingConstraint(2, 0, 3));
  }

  @Test
  void fullBedsDoNotClaimThatPopulationGrowthIsImpossible() {
    assertEquals("No general bed is free. A newcomer could only wait unhoused at the campfire if "
            + "population limits allow; a workshop without beds would not add housing, while a "
            + "house would. ",
        PlannerFacts.housingConstraint(0, 0, 0));
  }
}
