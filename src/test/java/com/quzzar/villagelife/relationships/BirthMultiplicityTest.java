package com.quzzar.villagelife.relationships;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BirthMultiplicityTest {

  @Test
  void rarestSliceProducesTripletsBeforeTwins() {
    assertEquals(BirthMultiplicity.TRIPLETS,
        BirthMultiplicity.fromRoll(0.001D, 0.03D, 0.0025D));
    assertEquals(BirthMultiplicity.TWINS,
        BirthMultiplicity.fromRoll(0.01D, 0.03D, 0.0025D));
    assertEquals(BirthMultiplicity.SINGLETON,
        BirthMultiplicity.fromRoll(0.5D, 0.03D, 0.0025D));
  }

  @Test
  void invalidChancesAreClamped() {
    assertEquals(BirthMultiplicity.SINGLETON,
        BirthMultiplicity.fromRoll(0.5D, -1.0D, -1.0D));
    assertEquals(BirthMultiplicity.TRIPLETS,
        BirthMultiplicity.fromRoll(0.5D, 1.0D, 2.0D));
  }
}
