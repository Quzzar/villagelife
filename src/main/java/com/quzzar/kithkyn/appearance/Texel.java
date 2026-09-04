package com.quzzar.kithkyn.appearance;

/** One exact texel coordinate on a 64 by 64 player texture. */
public record Texel(int x, int y) {

  public Texel {
    if (x < 0 || x >= 64 || y < 0 || y >= 64) {
      throw new IllegalArgumentException("Texel is outside the 64x64 texture: " + x + "," + y);
    }
  }

  public int packedIndex() {
    return y * 64 + x;
  }
}
