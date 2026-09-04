package com.quzzar.kithkyn.village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Marriage proposals villagers put to the village brain (docs/marriage.md). A
 * proposal is one villager naming another they want to marry; the brain still
 * decides, and it only ever weds two people who each proposed the other. This
 * is the marriage twin of {@link VillageRequests}: a villager proposes, the
 * brain disposes.
 *
 * <p>A proposal is not raw feeling. A villager files one when a bond has grown
 * strong and mutual ({@code MarriageService}), so the store records an
 * expressed intent, not a number: two people whose proposals point at each
 * other are the mutual ask the brain is asked to bless.
 *
 * <p>Stored in the brain's {@code strategy} tag beside the goal and the build
 * requests, as a list that persists with the village. Each entry stands until
 * it ages out, the cap is reached, or the two are wed.
 */
public final class MarriageProposals {

  private static final String KEY = "marriage_proposals";
  private static final String T_REQUESTER = "requester";
  private static final String T_TARGET = "target";
  private static final String T_AT = "at";

  /** Most proposals kept at once: past this the oldest is dropped. */
  private static final int MAX_PROPOSALS = 24;
  /** Village seconds a proposal stands before it is forgotten. */
  public static final int PROPOSAL_LIFETIME_SECONDS = 1800;

  /** One villager's standing wish to marry another. */
  public record Proposal(UUID requester, UUID target, int at) {

    private CompoundTag toTag() {
      CompoundTag t = new CompoundTag();
      t.putString(T_REQUESTER, requester.toString());
      t.putString(T_TARGET, target.toString());
      t.putInt(T_AT, at);
      return t;
    }

    private static Optional<Proposal> fromTag(CompoundTag t) {
      try {
        return Optional.of(new Proposal(
            UUID.fromString(t.getString(T_REQUESTER)),
            UUID.fromString(t.getString(T_TARGET)),
            t.getInt(T_AT)));
      } catch (IllegalArgumentException malformed) {
        return Optional.empty();
      }
    }
  }

  private MarriageProposals() {
  }

  private static List<Proposal> load(Village village) {
    ListTag list = village.getBrain().getStrategy().getList(KEY, Tag.TAG_COMPOUND);
    List<Proposal> out = new ArrayList<>(list.size());
    for (int i = 0; i < list.size(); i++) {
      Proposal.fromTag(list.getCompound(i)).ifPresent(out::add);
    }
    return out;
  }

  private static void save(Village village, List<Proposal> proposals) {
    ListTag list = new ListTag();
    for (Proposal proposal : proposals) {
      list.add(proposal.toTag());
    }
    village.getBrain().getStrategy().put(KEY, list);
  }

  /** Drops proposals that have aged out. */
  private static List<Proposal> prune(List<Proposal> proposals, int villageTime) {
    List<Proposal> kept = new ArrayList<>(proposals.size());
    for (Proposal proposal : proposals) {
      if (villageTime - proposal.at() <= PROPOSAL_LIFETIME_SECONDS) {
        kept.add(proposal);
      }
    }
    return kept;
  }

  /**
   * Records that {@code requester} wants to marry {@code target}, pruning stale
   * entries in the same pass. A villager repeating the same wish is one wish,
   * not many, so a proposal from the same requester to the same target is not
   * duplicated; its timestamp is refreshed instead so it does not age out
   * underneath a bond that is still standing.
   */
  public static void add(Village village, UUID requester, UUID target, int villageTime) {
    List<Proposal> proposals = prune(load(village), villageTime);
    proposals.removeIf(p -> p.requester().equals(requester) && p.target().equals(target));
    proposals.add(new Proposal(requester, target, villageTime));
    while (proposals.size() > MAX_PROPOSALS) {
      proposals.remove(0);
    }
    save(village, proposals);
  }

  /** The proposals currently before the brain, oldest first. */
  public static List<Proposal> current(Village village) {
    return prune(load(village), village.getVillageTime());
  }

  /** Whether {@code requester} has an outstanding proposal aimed at {@code target}. */
  public static boolean hasProposed(Village village, UUID requester, UUID target) {
    for (Proposal proposal : current(village)) {
      if (proposal.requester().equals(requester) && proposal.target().equals(target)) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code a} and {@code b} each have a standing proposal aimed at the other. */
  public static boolean mutual(Village village, UUID a, UUID b) {
    return hasProposed(village, a, b) && hasProposed(village, b, a);
  }

  /**
   * Removes every proposal a person is part of, on either end. Called when they
   * wed (their remaining wishes are settled) or leave the village.
   */
  public static void clearInvolving(Village village, UUID person) {
    List<Proposal> proposals = load(village);
    boolean changed = proposals.removeIf(p -> p.requester().equals(person) || p.target().equals(person));
    if (changed) {
      save(village, proposals);
    }
  }
}
