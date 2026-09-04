package com.quzzar.villagelife.village;

/** The real population action available after mood, floors, caps, and ledger readiness. */
public enum PopulationOutlook {
  FORCED_REFILL,
  WAITING_FOR_STORES,
  CAN_GROW,
  HOUSING_CAP,
  IDLE_CAP,
  HOLDING,
  DECLINING,
  HELD_AT_FLOOR;

  public enum Trend {
    GROWING,
    HOLDING,
    DECLINING
  }

  /** Mirrors {@link Village}'s population gate without performing its random arrival roll. */
  public static PopulationOutlook evaluate(int population, int pendingArrivals, int floor,
      boolean storesReady, Trend trend, int housingDemand, int totalBeds, int idle, int idleCap) {
    if (population + pendingArrivals < floor) {
      return FORCED_REFILL;
    }
    if (!storesReady) {
      return WAITING_FOR_STORES;
    }
    if (trend == Trend.DECLINING) {
      return population <= floor ? HELD_AT_FLOOR : DECLINING;
    }
    if (trend == Trend.HOLDING) {
      return HOLDING;
    }
    if (housingDemand + pendingArrivals >= totalBeds + idleCap) {
      return HOUSING_CAP;
    }
    if (idle + pendingArrivals >= idleCap) {
      return IDLE_CAP;
    }
    return CAN_GROW;
  }

  public String describe() {
    return switch (this) {
      case FORCED_REFILL -> "The village is below its population floor and will call in newcomers.";
      case WAITING_FOR_STORES -> "Population change is paused until the village can read its stores.";
      case CAN_GROW -> "Conditions and capacity allow newcomers, though each arrival is still a chance.";
      case HOUSING_CAP -> "Attractiveness is high, but housing capacity currently blocks another newcomer.";
      case IDLE_CAP -> "Attractiveness is high, but the campfire's idle-labour capacity is full.";
      case HOLDING -> "The population is holding steady.";
      case DECLINING -> "Conditions are poor enough that a resident may leave.";
      case HELD_AT_FLOOR -> "Conditions are poor, but the population floor prevents anyone leaving.";
    };
  }
}
