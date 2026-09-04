package com.quzzar.kithkyn.village.buildings;

import java.util.Objects;

/** A target definition together with the way the village intends to build it. */
public record ConstructionChoice(BuildingInfo info, ConstructionMode mode,
    @javax.annotation.Nullable RedevelopmentPlan redevelopment) {

  public ConstructionChoice(BuildingInfo info, ConstructionMode mode) {
    this(info, mode, null);
  }

  public ConstructionChoice {
    Objects.requireNonNull(info);
    Objects.requireNonNull(mode);
    if (redevelopment != null && (!info.getName().equals(redevelopment.target())
        || mode != redevelopment.mode())) {
      throw new IllegalArgumentException("Redevelopment proposal does not match construction choice");
    }
  }
}
