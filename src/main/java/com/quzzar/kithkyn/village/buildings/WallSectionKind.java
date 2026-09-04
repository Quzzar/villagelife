package com.quzzar.kithkyn.village.buildings;

import com.mojang.serialization.Codec;

/** The vocabulary a wall catalog uses to describe independently built pieces. */
public enum WallSectionKind {
  STRAIGHT,
  DIAGONAL,
  TERRACE,
  CORNER_TOWER,
  GATEHOUSE;

  public static final Codec<WallSectionKind> CODEC = Codec.STRING.xmap(
      name -> WallSectionKind.valueOf(name.toUpperCase()),
      kind -> kind.name().toLowerCase());
}
