package com.quzzar.kithkyn.entities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * A person's memory, kept like a proper log: every entry carries the Minecraft
 * calendar time it happened and, for operational blockers, the monotonic game
 * time used for recency. Three kinds of entry:
 *
 * - {@code pickup}: an item picked up, with the thrower when a player threw it
 *   — the ground truth for emergent gifting (a diamond is salvage until its
 *   thrower says otherwise in chat).
 * - {@code memory}: a completed or witnessed event, such as an attack or job
 *   change. Legacy {@code issue} entries are read as memories.
 * - {@code blocker}: a current operational impediment. Re-reporting refreshes
 *   it and successful work can resolve it explicitly.
 *
 * Server-side attachment, never synced. Newest entries last; capped per kind.
 */
public record PersonalLogData(List<Entry> entries, long reflectedThrough, long reflectedThroughGameTime) {

  public static final String KIND_PICKUP = "pickup";
  public static final String KIND_MEMORY = "memory";
  public static final String KIND_ISSUE = "issue";
  public static final String KIND_BLOCKER = "blocker";

  public static final int MAX_PICKUPS = 32;
  public static final int MAX_MEMORIES = 16;
  public static final int MAX_BLOCKERS = 8;

  public static final PersonalLogData EMPTY = new PersonalLogData(List.of(), 0L, -1L);

  /**
   * @param kind    pickup, memory, legacy issue, or blocker
   * @param item    pickup only: item id, e.g. "minecraft:diamond"
   * @param count   pickup only: stack count
   * @param text    memory/blocker only: what happened, one plain sentence
   * @param dayTime {@code level.getDayTime()} when it happened
   * @param who     pickup: the throwing player; memory: the other party, if any
   */
  public record Entry(String kind, String item, int count, String text, long dayTime,
      long gameTime, long lastSeenGameTime, Optional<UUID> who) {
    public static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        Codec.STRING.fieldOf("kind").forGetter(Entry::kind),
        Codec.STRING.optionalFieldOf("item", "").forGetter(Entry::item),
        Codec.INT.optionalFieldOf("count", 0).forGetter(Entry::count),
        Codec.STRING.optionalFieldOf("text", "").forGetter(Entry::text),
        Codec.LONG.fieldOf("day_time").forGetter(Entry::dayTime),
        Codec.LONG.optionalFieldOf("game_time", -1L).forGetter(Entry::gameTime),
        Codec.LONG.optionalFieldOf("last_seen_game_time", -1L).forGetter(Entry::lastSeenGameTime),
        UUIDUtil.STRING_CODEC.optionalFieldOf("who").forGetter(Entry::who)
    ).apply(inst, Entry::new));

    Entry seenAgain(long nowGameTime) {
      return new Entry(kind, item, count, text, dayTime, gameTime, nowGameTime, who);
    }
  }

  public static Entry pickup(String item, int count, long dayTime, long gameTime, Optional<UUID> thrower) {
    return new Entry(KIND_PICKUP, item, count, "", dayTime, gameTime, gameTime, thrower);
  }

  public static Entry memory(String text, long dayTime, long gameTime, Optional<UUID> who) {
    return new Entry(KIND_MEMORY, "", 0, text, dayTime, gameTime, gameTime, who);
  }

  public static Entry blocker(String text, long dayTime, long gameTime) {
    return new Entry(KIND_BLOCKER, "", 0, text, dayTime, gameTime, gameTime, Optional.empty());
  }

  public static final Codec<PersonalLogData> CODEC = RecordCodecBuilder.create(inst -> inst.group(
      Entry.CODEC.listOf().fieldOf("entries").forGetter(PersonalLogData::entries),
      Codec.LONG.optionalFieldOf("reflected_through", 0L).forGetter(PersonalLogData::reflectedThrough),
      Codec.LONG.optionalFieldOf("reflected_through_game_time", -1L)
          .forGetter(PersonalLogData::reflectedThroughGameTime)
  ).apply(inst, PersonalLogData::new));

  public PersonalLogData withEntry(Entry entry) {
    List<Entry> updated = new ArrayList<>(entries);
    updated.add(entry);
    trimKind(updated, KIND_PICKUP, MAX_PICKUPS);
    trimMemories(updated);
    trimKind(updated, KIND_BLOCKER, MAX_BLOCKERS);
    return new PersonalLogData(List.copyOf(updated), reflectedThrough, reflectedThroughGameTime);
  }

  /** Adds or refreshes one active blocker while preserving when it began. */
  public PersonalLogData withBlocker(String text, long dayTime, long gameTime) {
    List<Entry> updated = new ArrayList<>(entries);
    for (int i = 0; i < updated.size(); i++) {
      Entry entry = updated.get(i);
      if (KIND_BLOCKER.equals(entry.kind()) && entry.text().equals(text)) {
        if (!SimulationClock.isRecent(gameTime, entry.lastSeenGameTime(), STANDING_WINDOW)) {
          return withoutBlocker(text).withEntry(blocker(text, dayTime, gameTime));
        }
        updated.remove(i);
        updated.add(entry.seenAgain(gameTime));
        return new PersonalLogData(List.copyOf(updated), reflectedThrough, reflectedThroughGameTime);
      }
    }
    return withEntry(blocker(text, dayTime, gameTime));
  }

  /** Resolves a blocker by its stable text key. */
  public PersonalLogData withoutBlocker(String text) {
    List<Entry> updated = entries.stream()
        .filter(entry -> !KIND_BLOCKER.equals(entry.kind()) || !entry.text().equals(text))
        .toList();
    return updated.size() == entries.size() ? this
        : new PersonalLogData(updated, reflectedThrough, reflectedThroughGameTime);
  }

  /**
   * Entries the person has not yet made up their mind about, involving someone
   * else. Reflection reads these; everything older has already been felt.
   */
  public List<Entry> unreflected() {
    return entries.stream()
        .filter(this::isUnreflected)
        .filter(entry -> entry.who().isPresent())
        .toList();
  }

  private boolean isUnreflected(Entry entry) {
    if (entry.gameTime() >= 0L) {
      return reflectedThroughGameTime < 0L || entry.gameTime() > reflectedThroughGameTime;
    }
    return entry.dayTime() > reflectedThrough;
  }

  /** Marks everything up to this moment as considered. */
  public PersonalLogData reflectedAt(long dayTime, long gameTime) {
    return new PersonalLogData(entries, dayTime, gameTime);
  }

  private static void trimKind(List<Entry> entries, String kind, int max) {
    long count = entries.stream().filter(e -> kind.equals(e.kind())).count();
    for (var iterator = entries.iterator(); count > max && iterator.hasNext();) {
      if (kind.equals(iterator.next().kind())) {
        iterator.remove();
        count--;
      }
    }
  }

  private static void trimMemories(List<Entry> entries) {
    long count = entries.stream().filter(PersonalLogData::isMemory).count();
    for (var iterator = entries.iterator(); count > MAX_MEMORIES && iterator.hasNext();) {
      if (isMemory(iterator.next())) {
        iterator.remove();
        count--;
      }
    }
  }

  private static boolean isMemory(Entry entry) {
    return KIND_MEMORY.equals(entry.kind()) || KIND_ISSUE.equals(entry.kind());
  }

  /** Newest-first view for prompt assembly. */
  public List<Entry> newestFirst() {
    List<Entry> reversed = new ArrayList<>(entries);
    Collections.reverse(reversed);
    return reversed;
  }

  public List<Entry> pickupsNewestFirst() {
    return newestFirst().stream().filter(e -> KIND_PICKUP.equals(e.kind())).toList();
  }

  public List<Entry> memoriesNewestFirst() {
    return newestFirst().stream().filter(PersonalLogData::isMemory).toList();
  }

  public List<Entry> blockersNewestFirst() {
    return newestFirst().stream().filter(e -> KIND_BLOCKER.equals(e.kind())).toList();
  }

  /** Pickups thrown by the given player, newest first. */
  public List<Entry> thrownBy(UUID player) {
    return pickupsNewestFirst().stream().filter(e -> e.who().map(player::equals).orElse(false)).toList();
  }

  /** True if an identical event memory was already logged within the last day. */
  public boolean hasRecentMemory(String text, long nowDayTime, long nowGameTime) {
    return memoriesNewestFirst().stream()
        .anyMatch(e -> e.text().equals(text) && (e.gameTime() >= 0L
            ? SimulationClock.isRecent(nowGameTime, e.gameTime(), 24000L)
            : SimulationClock.isRecent(nowDayTime, e.dayTime(), 24000L)));
  }

  /** A typed current impediment and the monotonic time when it began. */
  public record ActiveBlocker(String text, long since) {
  }

  /**
   * How long a blocker remains standing without being refreshed. Most blockers
   * refresh on every failed attempt and clear explicitly when work succeeds.
   */
  private static final long STANDING_WINDOW = 2 * 24000L;

  /** A currently refreshed operational blocker, never a completed memory. */
  public Optional<ActiveBlocker> standingBlocker(long nowGameTime) {
    for (Entry blocker : blockersNewestFirst()) {
      if (!SimulationClock.isRecent(nowGameTime, blocker.lastSeenGameTime(), STANDING_WINDOW)) {
        continue;
      }
      return Optional.of(new ActiveBlocker(blocker.text(), blocker.gameTime()));
    }
    return Optional.empty();
  }

  /** "Day 14, morning" — the proper-log rendering of a dayTime stamp. */
  public static String formatDay(long dayTime) {
    long day = dayTime / 24000L + 1;
    long timeOfDay = dayTime % 24000L;
    String part;
    if (timeOfDay < 6000) {
      part = "morning";
    } else if (timeOfDay < 12000) {
      part = "afternoon";
    } else if (timeOfDay < 13000) {
      part = "evening";
    } else {
      part = "night";
    }
    return "Day " + day + ", " + part;
  }

}
