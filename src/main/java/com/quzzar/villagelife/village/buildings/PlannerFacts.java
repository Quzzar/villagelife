package com.quzzar.villagelife.village.buildings;

import java.util.Map;

/** Plain factual lines shared by the build planner and its focused tests. */
final class PlannerFacts {

  private PlannerFacts() {
  }

  static String existingBuildings(Map<String, Integer> counts) {
    return countedLine("Already standing", counts);
  }

  static String openPosts(Map<String, Integer> counts) {
    return countedLine("Open work", counts);
  }

  private static String countedLine(String heading, Map<String, Integer> counts) {
    if (counts.isEmpty()) {
      return "";
    }
    String joined = counts.entrySet().stream()
        .map(entry -> entry.getKey() + " x" + entry.getValue())
        .collect(java.util.stream.Collectors.joining(", "));
    return heading + ": " + joined + ". ";
  }

  /**
   * Why beds constrain labour, stated without choosing a building for the
   * village. A live-in bed can solve the constraint for its own post only.
   */
  static String housingConstraint(int unhousedAdults, int freeGeneralBeds, int openJobs) {
    if (unhousedAdults > 0) {
      String people = unhousedAdults == 1
          ? "1 adult resident is" : unhousedAdults + " adult residents are";
      if (openJobs > 0) {
        return people + " already unhoused. They cannot take open work unless that workplace "
            + "has a free live-in bed; another workshop without beds would not house them, while "
            + "a house would add general beds. ";
      }
      return people + " already unhoused. Another workshop without beds would not house them, "
          + "while a house would add general beds. ";
    }
    if (freeGeneralBeds == 0) {
      if (openJobs > 0) {
        return "No general bed is free. Open work can only be taken by someone already housed or "
            + "at a workplace with its own free live-in bed; another workshop without beds would "
            + "not create housing, while a house would. ";
      }
      return "No general bed is free. A newcomer could only wait unhoused at the campfire if "
          + "population limits allow; a workshop without beds would not add housing, while a "
          + "house would. ";
    }
    return freeGeneralBeds + (freeGeneralBeds == 1
        ? " general bed stands free. " : " general beds stand free. ");
  }
}
