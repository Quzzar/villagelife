package com.quzzar.villagelife.relationships;

import java.util.List;
import java.util.UUID;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.MarriageStatus;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.village.MarriageProposals;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.buildings.BuildingInfo;
import com.quzzar.villagelife.village.buildings.Buildings;
import com.quzzar.villagelife.village.buildings.UrbanPlanner;
import com.quzzar.villagelife.village.buildings.VillageGoal;

import net.minecraft.server.level.ServerLevel;

/**
 * Marriage: two villagers who have grown close ask the brain to wed them, and
 * the brain decides (docs/marriage.md). It rides rails that already exist, and
 * invents no parallel state.
 *
 * <ul>
 *   <li><b>Proposing</b> is emergent, not scripted. A single villager whose
 *   bond to another single villager has grown strong and mutual files a
 *   proposal naming them ({@link MarriageProposals}). Because a bond is one
 *   shared object, a strong one makes both sides file, which is the mutual ask
 *   the brain is handed.</li>
 *   <li><b>Deciding</b> is the brain's, through the same {@code decide} call the
 *   build planner uses: the facts of the pair go in, the model says yes or not
 *   yet. A wedding sets {@link MarriageStatus#MARRIED} on both, records each as
 *   the other's partner, hyphenates their family name, and flags the
 *   {@link RelationshipPair} married so it is never proposed again.</li>
 *   <li><b>Housing</b> is derived, not stored. A married pair who do not yet
 *   share a home is a couple awaiting one; the village names a couple's cottage
 *   as its saved-for goal ({@link VillageGoal}) once it is not already saving
 *   for something else, and on the cottage's completion both are moved into its
 *   two beds. Their shared chest then follows for free from the personal-chest
 *   rules, since a home's chest belongs to whoever sleeps there.</li>
 * </ul>
 */
public final class MarriageService {

  /**
   * How often a village weighs its marriages, phase-staggered like every other
   * pass. A proposal and a wedding are both rare, so this is unhurried; the
   * decision is an LLM call, so it is no faster than the build cadence.
   */
  public static final int MARRIAGE_INTERVAL_SECONDS = 60;

  /**
   * How fond two villagers must each be of the other before either proposes.
   * Generation caps a first-day opinion at 60 and drift is bounded, so a pair
   * this warm on both sides is among the strongest bonds a village holds: the
   * feelings the model authored with a reason, not the slow pull of familiarity
   * (docs/relationships.md). Tunable; the live web is what settles it.
   */
  private static final int STRONG_BOND = 50;

  private static final String APPROVE = "yes, they should marry";
  private static final String DECLINE = "not yet";

  private MarriageService() {
  }

  /**
   * One village's marriage pass: villagers file the proposals their bonds have
   * earned, the village names a home for a couple that lacks one, and, when two
   * people have each proposed the other, the brain is asked to bless it.
   */
  public static void tick(Village village, ServerLevel level) {
    fileEmergentProposals(village, level);
    ensureCoupleHomeGoal(village, level);
    considerDecision(village, level);
  }

  // --- Proposing -----------------------------------------------------------

  /**
   * Every single villager proposes to the one single villager they are most
   * fond of, when that fondness is strong and returned. Only the strongest
   * eligible bond is proposed to, so no one asks two people at once, and the
   * proposal store's own dedupe keeps a standing wish from piling up.
   */
  private static void fileEmergentProposals(Village village, ServerLevel level) {
    int time = village.getVillageTime();
    for (UUID id : village.getPopulation()) {
      RealPerson person = village.getPerson(level, id);
      if (person == null || person.getMarriageStatus() != MarriageStatus.SINGLE) {
        continue;
      }
      UUID chosen = strongestEligiblePartner(village, level, id);
      if (chosen != null && !MarriageProposals.hasProposed(village, id, chosen)) {
        MarriageProposals.add(village, id, chosen, time);
        Villagelife.LOGGER.info("[marriage] '{}' wishes to marry {} in '{}'",
            person.getFullName(), nameOf(village, level, chosen), village.getName());
      }
    }
  }

  /** The single, present villager this person is most fond of, if the bond is strong both ways. */
  private static UUID strongestEligiblePartner(Village village, ServerLevel level, UUID id) {
    UUID best = null;
    int bestValue = Integer.MIN_VALUE;
    for (RelationshipPair pair : village.relationshipsOf(id)) {
      if (pair.married()) {
        continue;
      }
      UUID otherId = pair.other(id);
      if (pair.opinionOf(id) < STRONG_BOND || pair.opinionOf(otherId) < STRONG_BOND) {
        continue;
      }
      RealPerson other = village.getPerson(level, otherId);
      if (other == null || other.getMarriageStatus() != MarriageStatus.SINGLE) {
        continue;
      }
      if (pair.value() > bestValue) {
        bestValue = pair.value();
        best = otherId;
      }
    }
    return best;
  }

  // --- Deciding ------------------------------------------------------------

  /**
   * When two villagers have each proposed the other, the brain is asked whether
   * to wed them. One decision is in flight at a time, like the build planner's,
   * so a slow model cannot stack requests. The answer is applied on the server
   * thread; a failed or absent model simply leaves them betrothed for next pass.
   */
  private static void considerDecision(Village village, ServerLevel level) {
    if (village.isMarriageDecisionPending()) {
      return;
    }
    UUID[] couple = firstMutualProposal(village, level);
    if (couple == null) {
      return;
    }
    RealPerson a = village.getPerson(level, couple[0]);
    RealPerson b = village.getPerson(level, couple[1]);
    if (a == null || b == null) {
      return;
    }
    String situation = decisionSituation(village, a, b, village.getRelationship(couple[0], couple[1]));
    UUID idA = couple[0];
    UUID idB = couple[1];
    village.setMarriageDecisionPending(true);
    LlmService.get().decide("should " + a.getFullName() + " and " + b.getFullName() + " marry",
        situation, List.of(APPROVE, DECLINE)).whenComplete((result, error) -> {
          if (level.getServer() == null) {
            return;
          }
          level.getServer().execute(() -> {
            village.setMarriageDecisionPending(false);
            if (error != null) {
              Villagelife.LOGGER.error("[marriage] '{}' could not decide a marriage", village.getName(), error);
              return;
            }
            if (result != null && result.isPresent() && result.get().choiceIndex() == 0) {
              wed(village, level, idA, idB, result.get().reason());
            } else if (result != null && result.isPresent()) {
              Villagelife.LOGGER.info("[marriage] '{}' set aside a marriage for now: {}",
                  village.getName(), result.get().reason());
            }
          });
        });
  }

  /** The first pair, in canonical order, who have each proposed the other and are both still single here. */
  private static UUID[] firstMutualProposal(Village village, ServerLevel level) {
    for (MarriageProposals.Proposal proposal : MarriageProposals.current(village)) {
      UUID a = proposal.requester();
      UUID b = proposal.target();
      if (!MarriageProposals.mutual(village, a, b)) {
        continue;
      }
      RelationshipPair pair = village.getRelationship(a, b);
      if (pair != null && pair.married()) {
        continue;
      }
      RealPerson pa = village.getPerson(level, a);
      RealPerson pb = village.getPerson(level, b);
      if (pa == null || pb == null
          || pa.getMarriageStatus() != MarriageStatus.SINGLE
          || pb.getMarriageStatus() != MarriageStatus.SINGLE) {
        continue;
      }
      return a.compareTo(b) <= 0 ? new UUID[] {a, b} : new UUID[] {b, a};
    }
    return null;
  }

  /** The facts of a proposed marriage, for the brain to weigh: who they are, how they feel, the village's room. */
  private static String decisionSituation(Village village, RealPerson a, RealPerson b, RelationshipPair pair) {
    StringBuilder situation = new StringBuilder();
    situation.append("You are the collective judgement of ").append(village.getName()).append(". ");
    situation.append(a.getFullName()).append(" (").append(a.getOccupation().name().toLowerCase())
        .append(") and ").append(b.getFullName()).append(" (").append(b.getOccupation().name().toLowerCase())
        .append(") have each asked to marry the other. ");
    if (pair != null) {
      situation.append("Their bond stands at ").append(pair.value()).append(" out of 100");
      if (!pair.flavor().isBlank()) {
        situation.append(": ").append(pair.flavor());
      }
      situation.append(". ");
    }
    situation.append("A married couple is given a home of their own to raise, which the village will save "
        + "toward once it is not already saving for something else. ");
    situation.append("The settlement has ").append(village.getPopulation().size()).append(" people. ");
    situation.append("Bless this marriage, or decide the time is not right.");
    return situation.toString();
  }

  /**
   * Weds two villagers: both become {@link MarriageStatus#MARRIED}, the
   * {@link RelationshipPair} between them is flagged married so it is never
   * proposed again, and their standing proposals are cleared. Guards that
   * neither wed someone else while the decision was in flight.
   */
  private static void wed(Village village, ServerLevel level, UUID idA, UUID idB, String reason) {
    RealPerson a = village.getPerson(level, idA);
    RealPerson b = village.getPerson(level, idB);
    if (a == null || b == null
        || a.getMarriageStatus() != MarriageStatus.SINGLE
        || b.getMarriageStatus() != MarriageStatus.SINGLE) {
      return;
    }
    a.marry(b);
    RelationshipPair pair = village.getRelationship(idA, idB);
    village.putRelationship(pair != null
        ? pair.withMarried(true)
        : RelationshipPair.create(idA, idB, STRONG_BOND, 0, 0, false, "", true));
    MarriageProposals.clearInvolving(village, idA);
    MarriageProposals.clearInvolving(village, idB);
    Villagelife.LOGGER.info("[marriage] '{}' and '{}' are wed in '{}': {}",
        a.getFullName(), b.getFullName(), village.getName(), reason);
  }

  // --- Housing -------------------------------------------------------------

  /**
   * Names a couple's cottage as the village's saved-for goal when a married pair
   * has no shared home and the village is not already saving for something else
   * (the "queues next" rule). A home the economy cannot reach stalls through the
   * ordinary goal machinery, and while it sits out the village is left to build
   * other things rather than hammering an unaffordable cottage forever.
   */
  private static void ensureCoupleHomeGoal(Village village, ServerLevel level) {
    if (VillageGoal.current(village) != null) {
      return;
    }
    UUID[] couple = firstCoupleAwaitingHome(village, level);
    if (couple == null) {
      return;
    }
    BuildingInfo cottage = Buildings.resolve(Buildings.COUPLE_COTTAGE_CATEGORY, 1, village.getStyle());
    if (cottage == null) {
      return;
    }
    if (cottage.getName().equals(VillageGoal.stalled(village, village.getVillageTime()))) {
      return;
    }
    String reason = "the newlyweds " + nameOf(village, level, couple[0]) + " and "
        + nameOf(village, level, couple[1]) + " need a home of their own";
    VillageGoal.set(village, cottage.getName(), reason,
        UrbanPlanner.shortfallFor(village, cottage), village.getVillageTime());
  }

  /**
   * Called as a building is added: when it is a couple's cottage, the couple it
   * was raised for move into its two beds, freeing whatever single beds they
   * held. Their shared chest then follows from the personal-chest rules with no
   * further wiring. A no-op for every other building.
   */
  public static void onHomeBuilt(Village village, ServerLevel level, UUID buildingUUID, BuildingInfo info) {
    if (info == null || !Buildings.COUPLE_COTTAGE_CATEGORY.equals(info.getCategory())) {
      return;
    }
    UUID[] couple = firstCoupleAwaitingHome(village, level);
    if (couple == null) {
      return;
    }
    village.houseCouple(couple[0], couple[1], buildingUUID);
    Villagelife.LOGGER.info("[marriage] '{}' and '{}' move into their new home in '{}'",
        nameOf(village, level, couple[0]), nameOf(village, level, couple[1]), village.getName());
  }

  /**
   * The first married couple, in canonical order, who are both present and do
   * not yet share a home. Deterministic so the couple a cottage is named for is
   * the same couple moved into it when it finishes.
   */
  private static UUID[] firstCoupleAwaitingHome(Village village, ServerLevel level) {
    UUID[] best = null;
    for (RelationshipPair pair : village.marriedPairs()) {
      UUID a = pair.personA();
      UUID b = pair.personB();
      if (village.getPerson(level, a) == null || village.getPerson(level, b) == null) {
        continue;
      }
      if (sharesHome(village, a, b)) {
        continue;
      }
      if (best == null || pair.key().compareTo(RelationshipPair.keyOf(best[0], best[1])) < 0) {
        best = new UUID[] {a, b};
      }
    }
    return best;
  }

  /** Whether two villagers are assigned beds in the same building. */
  private static boolean sharesHome(Village village, UUID a, UUID b) {
    var bedA = village.getBedAssignment(a);
    var bedB = village.getBedAssignment(b);
    return bedA != null && bedB != null && bedA.getBuildingUUID().equals(bedB.getBuildingUUID());
  }

  private static String nameOf(Village village, ServerLevel level, UUID id) {
    RealPerson person = village.getPerson(level, id);
    return person != null ? person.getFullName() : "a villager";
  }
}
