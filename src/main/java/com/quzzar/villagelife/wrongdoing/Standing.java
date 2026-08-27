package com.quzzar.villagelife.wrongdoing;

import java.util.UUID;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.relationships.OpinionService;
import com.quzzar.villagelife.village.Village;

import net.minecraft.server.level.ServerLevel;

/**
 * Where a person stands with a village (docs/economy.md, decided on #64).
 *
 * Derived from what its residents actually think of you, never stored, so it
 * cannot drift out of sync with the opinions underneath it: rob a village in
 * front of three people and it is three people who think less of you, which is
 * what standing then reads.
 *
 * The tiers are a ladder down and the same ladder back up. Nothing here is
 * permanent — opinions of outsiders fade toward indifference on their own — so
 * every consequence below is escapable by staying away and behaving, which is
 * what makes it a punishment rather than a tax.
 */
public final class Standing {

  /**
   * What a village will do about you, worst first. Each tier keeps everything
   * the tiers above it do: an outlaw is also refused trade, and also charged
   * more when trade is somehow possible.
   */
  public enum Tier {
    /** Guards attack on sight. */
    HOSTILE,
    /** Nobody will approach you or talk to you. */
    SHUNNED,
    /** The market is closed to you. */
    UNWELCOME,
    /** You pay over the odds. */
    DISLIKED,
    /** Business as usual. */
    NEUTRAL,
    /** Nothing yet: liking someone costs a village nothing it has. */
    TRUSTED;

    public boolean atLeastAsBadAs(Tier other) {
      return ordinal() <= other.ordinal();
    }
  }

  private Standing() {
  }

  /**
   * The average of every resident's opinion, from -100 to 100. An average
   * rather than a sum, so a large village is not automatically more hostile
   * than a hamlet over the same offence, and one furious neighbour does not
   * speak for everybody.
   */
  public static int of(Village village, ServerLevel level, UUID player) {
    int total = 0;
    int counted = 0;
    for (UUID residentId : village.getPopulation()) {
      RealPerson resident = village.getPerson(level, residentId);
      if (resident == null) {
        continue;
      }
      total += OpinionService.opinionOf(resident, player);
      counted++;
    }
    return counted == 0 ? 0 : total / counted;
  }

  public static Tier tierOf(Village village, ServerLevel level, UUID player) {
    return tierFor(of(village, level, player));
  }

  /** The ladder itself, in one place, so every consequence reads the same rungs. */
  public static Tier tierFor(int standing) {
    if (standing <= com.quzzar.villagelife.configuration.VillagelifeConfig.StandingHostileBelow) {
      return Tier.HOSTILE;
    }
    if (standing <= com.quzzar.villagelife.configuration.VillagelifeConfig.StandingShunnedBelow) {
      return Tier.SHUNNED;
    }
    if (standing <= com.quzzar.villagelife.configuration.VillagelifeConfig.StandingUnwelcomeBelow) {
      return Tier.UNWELCOME;
    }
    if (standing <= com.quzzar.villagelife.configuration.VillagelifeConfig.StandingDislikedBelow) {
      return Tier.DISLIKED;
    }
    return standing >= com.quzzar.villagelife.configuration.VillagelifeConfig.StandingTrustedAbove
        ? Tier.TRUSTED
        : Tier.NEUTRAL;
  }

  /**
   * What the village adds to its asking price for you: nothing until it
   * dislikes you, then rising with how far below neutral you have fallen. A
   * village that will not trade at all never gets here, so this is the cost of
   * being merely unpopular.
   */
  public static double priceMultiplier(int standing) {
    if (standing > com.quzzar.villagelife.configuration.VillagelifeConfig.StandingDislikedBelow) {
      return 1.0D;
    }
    double depth = com.quzzar.villagelife.configuration.VillagelifeConfig.StandingDislikedBelow - standing;
    double span = Math.max(1.0D,
        com.quzzar.villagelife.configuration.VillagelifeConfig.StandingDislikedBelow
            - com.quzzar.villagelife.configuration.VillagelifeConfig.StandingUnwelcomeBelow);
    double worst = com.quzzar.villagelife.configuration.VillagelifeConfig.StandingWorstMarkup;
    return 1.0D + Math.min(1.0D, depth / span) * (worst - 1.0D);
  }

  /** Why a village will not deal with you, in words it would use. */
  public static String refusalReason(Tier tier) {
    return switch (tier) {
      case HOSTILE -> "they want you gone, not paid";
      case SHUNNED -> "nobody here will deal with you";
      case UNWELCOME -> "the market is closed to you";
      default -> "";
    };
  }

}
