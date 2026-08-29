package com.quzzar.villagelife.village;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import com.quzzar.villagelife.Villagelife;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Requests villagers put to the village brain: "we should build walls", "let us
 * save for a farm", and the reason why. A villager is NOT the brain and never
 * decides for it; a request only puts a subject and an argument in front of the
 * next decision (see {@link com.quzzar.villagelife.village.buildings.UrbanPlanner}),
 * which still makes its own choice. This is how a villager's local knowledge -
 * a raid on the road, a hungry winter - reaches the collective judgement.
 *
 * <p>Stored in the brain's {@code strategy} tag beside the goal (VillageGoal),
 * as a list that persists with the village. The queue holds pending requests and
 * the recently-resolved ones the requester has not yet been told about, so the
 * loop can close in a later conversation.
 */
public final class VillageRequests {

  private static final String KEY = "villager_requests";
  private static final String T_REQUESTER = "requester";
  private static final String T_NAME = "requester_name";
  private static final String T_SUBJECT = "subject";
  private static final String T_REASON = "reason";
  private static final String T_AT = "at";
  private static final String T_STATUS = "status";
  private static final String T_SEEN = "seen";

  /** Most requests kept at once: past this the oldest pending one is dropped. */
  private static final int MAX_REQUESTS = 16;
  /** Village seconds a pending request stands before it is forgotten. */
  public static final int REQUEST_LIFETIME_SECONDS = 1800;

  public enum Status { PENDING, ACCEPTED, REJECTED }

  /** One villager's standing ask, with who made it and how it was answered. */
  public record Request(UUID requester, String requesterName, String subject, String reason,
      int at, Status status, boolean seen) {

    private CompoundTag toTag() {
      CompoundTag t = new CompoundTag();
      t.putString(T_REQUESTER, requester.toString());
      t.putString(T_NAME, requesterName);
      t.putString(T_SUBJECT, subject);
      t.putString(T_REASON, reason);
      t.putInt(T_AT, at);
      t.putString(T_STATUS, status.name());
      t.putBoolean(T_SEEN, seen);
      return t;
    }

    private static Optional<Request> fromTag(CompoundTag t) {
      try {
        return Optional.of(new Request(
            UUID.fromString(t.getString(T_REQUESTER)),
            t.getString(T_NAME),
            t.getString(T_SUBJECT),
            t.getString(T_REASON),
            t.getInt(T_AT),
            Status.valueOf(t.getString(T_STATUS)),
            t.getBoolean(T_SEEN)));
      } catch (IllegalArgumentException malformed) {
        return Optional.empty();
      }
    }

    private Request withStatus(Status newStatus) {
      return new Request(requester, requesterName, subject, reason, at, newStatus, false);
    }

    private Request markSeen() {
      return new Request(requester, requesterName, subject, reason, at, status, true);
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
   * Records a villager's request, unless they already have the same one pending.
   * Prunes stale and already-heard entries in the same pass so the queue cannot
   * grow without bound.
   */
  public static void add(Village village, UUID requester, String requesterName,
      String subject, String reason, int villageTime) {
    String cleanSubject = subject == null ? "" : subject.strip();
    if (cleanSubject.isEmpty()) {
      return;
    }
    List<Request> requests = prune(load(village), villageTime);
    for (Request existing : requests) {
      if (existing.status() == Status.PENDING && existing.requester().equals(requester)
          && normalize(existing.subject()).equals(normalize(cleanSubject))) {
        return;
      }
    }
    requests.add(new Request(requester, requesterName == null ? "A villager" : requesterName,
        cleanSubject, reason == null ? "" : reason.strip(), villageTime, Status.PENDING, false));
    // If still over the cap after pruning, drop the oldest pending request.
    while (requests.size() > MAX_REQUESTS) {
      int oldestPending = -1;
      for (int i = 0; i < requests.size(); i++) {
        if (requests.get(i).status() == Status.PENDING) {
          oldestPending = i;
          break;
        }
      }
      requests.remove(oldestPending >= 0 ? oldestPending : 0);
    }
    save(village, requests);
    Villagelife.LOGGER.info("Village '{}': {} asks to {} ({})",
        village.getName(), requesterName, cleanSubject, reason);
  }

  /** The requests still awaiting the brain's judgement. */
  public static List<Request> pending(Village village) {
    List<Request> out = new ArrayList<>();
    for (Request request : load(village)) {
      if (request.status() == Status.PENDING) {
        out.add(request);
      }
    }
    return out;
  }

  /**
   * Pending requests grouped by subject, newest-argued first within each group,
   * so the brain sees "walls (3 people)" once rather than three separate lines -
   * many villagers asking for the same thing is a stronger signal, not noise.
   */
  public static Map<String, List<Request>> pendingBySubject(Village village) {
    Map<String, List<Request>> grouped = new LinkedHashMap<>();
    for (Request request : pending(village)) {
      grouped.computeIfAbsent(normalize(request.subject()), key -> new ArrayList<>()).add(request);
    }
    return grouped;
  }

  /**
   * Settles every pending request the brain just weighed: accepted when its
   * subject was the one chosen, rejected otherwise. Resolved requests stay in the
   * queue, unseen, until the requester is next spoken to and hears the outcome.
   */
  public static void resolvePending(Village village, Predicate<String> wasChosen) {
    List<Request> requests = load(village);
    boolean changed = false;
    for (int i = 0; i < requests.size(); i++) {
      Request request = requests.get(i);
      if (request.status() != Status.PENDING) {
        continue;
      }
      requests.set(i, request.withStatus(wasChosen.test(request.subject())
          ? Status.ACCEPTED : Status.REJECTED));
      changed = true;
    }
    if (changed) {
      save(village, requests);
    }
  }

  /**
   * The most recent decided request this villager has not yet been told about,
   * marking it heard so it is reported only once. Returns empty when they have
   * nothing outstanding.
   */
  public static Optional<Request> takeUnheard(Village village, UUID requester) {
    List<Request> requests = load(village);
    for (int i = requests.size() - 1; i >= 0; i--) {
      Request request = requests.get(i);
      if (request.requester().equals(requester) && request.status() != Status.PENDING
          && !request.seen()) {
        requests.set(i, request.markSeen());
        save(village, requests);
        return Optional.of(request);
      }
    }
    return Optional.empty();
  }

  /** Lowercased, article-stripped subject, for matching and grouping. */
  public static String normalize(String subject) {
    String s = subject.toLowerCase(java.util.Locale.ROOT).strip();
    for (String article : new String[] {"a ", "an ", "the "}) {
      if (s.startsWith(article)) {
        s = s.substring(article.length());
        break;
      }
    }
    return s.endsWith("s") ? s.substring(0, s.length() - 1) : s;
  }

  /** Drops requests that have aged out (pending) or been heard (resolved + seen). */
  private static List<Request> prune(List<Request> requests, int villageTime) {
    List<Request> kept = new ArrayList<>(requests.size());
    for (Request request : requests) {
      boolean agedOut = request.status() == Status.PENDING
          && villageTime - request.at() > REQUEST_LIFETIME_SECONDS;
      boolean alreadyHeard = request.status() != Status.PENDING && request.seen();
      if (!agedOut && !alreadyHeard) {
        kept.add(request);
      }
    }
    return kept;
  }
}
