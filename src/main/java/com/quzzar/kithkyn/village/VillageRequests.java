package com.quzzar.kithkyn.village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.quzzar.kithkyn.Kithkyn;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Requests villagers put to the village brain: "we should build walls", "let us
 * save for a farm", and the reason why. A villager is NOT the brain and never
 * decides for it; a request only puts a subject and an argument in front of the
 * next decision (see {@link com.quzzar.kithkyn.village.buildings.UrbanPlanner}),
 * which still makes its own choice. This is how a villager's local knowledge -
 * a raid on the road, a hungry winter - reaches the collective judgement.
 *
 * <p>A request is plain free text and need not name a building at all: the brain
 * reads the raw list as context and maps it to its own options itself. There is
 * deliberately no grouping, matching, or ranking here - many villagers asking the
 * same thing simply means many lines, and that volume IS the emphasis.
 *
 * <p>Stored in the brain's {@code strategy} tag beside the goal (VillageGoal), as
 * a list that persists with the village. Each entry stands until it ages out or
 * the queue is full, whichever comes first.
 */
public final class VillageRequests {

  private static final String KEY = "villager_requests";
  private static final String T_REQUESTER = "requester";
  private static final String T_NAME = "requester_name";
  private static final String T_SUBJECT = "subject";
  private static final String T_REASON = "reason";
  private static final String T_AT = "at";

  /** Most requests kept at once: past this the oldest is dropped. */
  private static final int MAX_REQUESTS = 16;
  /** Village seconds a request stands before it is forgotten. */
  public static final int REQUEST_LIFETIME_SECONDS = 1800;

  /** One villager's standing ask: who made it, what they want, and why. */
  public record Request(UUID requester, String requesterName, String subject, String reason, int at) {

    private CompoundTag toTag() {
      CompoundTag t = new CompoundTag();
      t.putString(T_REQUESTER, requester.toString());
      t.putString(T_NAME, requesterName);
      t.putString(T_SUBJECT, subject);
      t.putString(T_REASON, reason);
      t.putInt(T_AT, at);
      return t;
    }

    private static Optional<Request> fromTag(CompoundTag t) {
      try {
        return Optional.of(new Request(
            UUID.fromString(t.getString(T_REQUESTER)),
            t.getString(T_NAME),
            t.getString(T_SUBJECT),
            t.getString(T_REASON),
            t.getInt(T_AT)));
      } catch (IllegalArgumentException malformed) {
        return Optional.empty();
      }
    }
  }

  private VillageRequests() {
  }

  private static List<Request> load(Village village) {
    ListTag list = village.getBrain().getStrategy().getList(KEY, Tag.TAG_COMPOUND);
    List<Request> out = new ArrayList<>(list.size());
    for (int i = 0; i < list.size(); i++) {
      Request.fromTag(list.getCompound(i)).ifPresent(out::add);
    }
    return out;
  }

  private static void save(Village village, List<Request> requests) {
    ListTag list = new ListTag();
    for (Request request : requests) {
      list.add(request.toTag());
    }
    village.getBrain().getStrategy().put(KEY, list);
  }

  /**
   * Records a villager's request, pruning stale entries in the same pass so the
   * queue cannot grow without bound. A villager repeating itself is one opinion,
   * not many, so an identical subject from the SAME requester is dropped; the same
   * subject from DIFFERENT villagers is kept, because that is the consensus the
   * raw volume is meant to show.
   */
  public static void add(Village village, UUID requester, String requesterName,
      String subject, String reason, int villageTime) {
    String cleanSubject = subject == null ? "" : subject.strip();
    if (cleanSubject.isEmpty()) {
      return;
    }
    List<Request> requests = prune(load(village), villageTime);
    for (Request existing : requests) {
      if (existing.requester().equals(requester)
          && existing.subject().equalsIgnoreCase(cleanSubject)) {
        return;
      }
    }
    requests.add(new Request(requester, requesterName == null ? "A villager" : requesterName,
        cleanSubject, reason == null ? "" : reason.strip(), villageTime));
    // If still over the cap after pruning, drop the oldest.
    while (requests.size() > MAX_REQUESTS) {
      requests.remove(0);
    }
    save(village, requests);
    Kithkyn.LOGGER.info("Village '{}': {} asks to {} ({})",
        village.getName(), requesterName, cleanSubject, reason);
  }

  /** The requests currently before the brain, oldest first. */
  public static List<Request> current(Village village) {
    return prune(load(village), village.getVillageTime());
  }

  /** Drops requests that have aged out. */
  private static List<Request> prune(List<Request> requests, int villageTime) {
    List<Request> kept = new ArrayList<>(requests.size());
    for (Request request : requests) {
      if (villageTime - request.at() <= REQUEST_LIFETIME_SECONDS) {
        kept.add(request);
      }
    }
    return kept;
  }
}
