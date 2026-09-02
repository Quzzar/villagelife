package com.quzzar.villagelife.entities;

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
 * calendar time it happened ({@code level.getDayTime()} — the clock sleeping
 * advances). Two kinds of entry so far:
 *
 * - {@code pickup}: an item picked up, with the thrower when a player threw it
 *   — the ground truth for emergent gifting (a diamond is salvage until its
 *   thrower says otherwise in chat).
 * - {@code issue}: a problem the person ran into (lava in the mine, an
 *   attack), emitted by game code at real moments, never LLM-invented — the
 *   foundation of emergent quests: issues surface in conversation and can be
 *   resolved by anyone or no one; there is no quest state machine.
 *
 * Server-side attachment, never synced. Newest entries last; capped per kind.
 */
public record PersonalLogData(List<Entry> entries, long reflectedThrough) {

  public static final String KIND_PICKUP = "pickup";
  public static final String KIND_ISSUE = "issue";

  public static final int MAX_PICKUPS = 32;
  public static final int MAX_ISSUES = 16;

  public static final PersonalLogData EMPTY = new PersonalLogData(List.of(), 0L);

  /**
   * @param kind    {@link #KIND_PICKUP} or {@link #KIND_ISSUE}
   * @param item    pickup only: item id, e.g. "minecraft:diamond"
   * @param count   pickup only: stack count
   * @param text    issue only: what happened, one plain sentence
   * @param dayTime {@code level.getDayTime()} when it happened
   * @param who     pickup: the throwing player; issue: the other party, if any
   */
  public record Entry(String kind, String item, int count, String text, long dayTime, Optional<UUID> who) {
    public static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        Codec.STRING.fieldOf("kind").forGetter(Entry::kind),
        Codec.STRING.optionalFieldOf("item", "").forGetter(Entry::item),
        Codec.INT.optionalFieldOf("count", 0).forGetter(Entry::count),
        Codec.STRING.optionalFieldOf("text", "").forGetter(Entry::text),
        Codec.LONG.fieldOf("day_time").forGetter(Entry::dayTime),
        UUIDUtil.STRING_CODEC.optionalFieldOf("who").forGetter(Entry::who)
    ).apply(inst, Entry::new));
  }

  public static Entry pickup(String item, int count, long dayTime, Optional<UUID> thrower) {
    return new Entry(KIND_PICKUP, item, count, "", dayTime, thrower);
  }

  public static Entry issue(String text, long dayTime, Optional<UUID> who) {
    return new Entry(KIND_ISSUE, "", 0, text, dayTime, who);
  }

  public static final Codec<PersonalLogData> CODEC = RecordCodecBuilder.create(inst -> inst.group(
      Entry.CODEC.listOf().fieldOf("entries").forGetter(PersonalLogData::entries),
      Codec.LONG.optionalFieldOf("reflected_through", 0L).forGetter(PersonalLogData::reflectedThrough)
  ).apply(inst, PersonalLogData::new));

  public PersonalLogData withEntry(Entry entry) {
    List<Entry> updated = new ArrayList<>(entries);
    updated.add(entry);
    trimKind(updated, KIND_PICKUP, MAX_PICKUPS);
    trimKind(updated, KIND_ISSUE, MAX_ISSUES);
    return new PersonalLogData(List.copyOf(updated), reflectedThrough);
  }

  /**
   * Entries the person has not yet made up their mind about, involving someone
   * else. Reflection reads these; everything older has already been felt.
   */
  public List<Entry> unreflected() {
    return entries.stream()
        .filter(entry -> entry.dayTime() > reflectedThrough)
        .filter(entry -> entry.who().isPresent())
        .toList();
  }

  /** Marks everything up to this moment as considered. */
  public PersonalLogData reflectedAt(long dayTime) {
    return new PersonalLogData(entries, dayTime);
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

  /** Newest-first view for prompt assembly. */
  public List<Entry> newestFirst() {
    List<Entry> reversed = new ArrayList<>(entries);
    Collections.reverse(reversed);
    return reversed;
  }

  public List<Entry> pickupsNewestFirst() {
    return newestFirst().stream().filter(e -> KIND_PICKUP.equals(e.kind())).toList();
  }

  public List<Entry> issuesNewestFirst() {
    return newestFirst().stream().filter(e -> KIND_ISSUE.equals(e.kind())).toList();
  }

  /** Pickups thrown by the given player, newest first. */
  public List<Entry> thrownBy(UUID player) {
    return pickupsNewestFirst().stream().filter(e -> e.who().map(player::equals).orElse(false)).toList();
  }

  /** True if an identical issue was already logged within the last day. */
  public boolean hasRecentIssue(String text, long nowDayTime) {
    return issuesNewestFirst().stream()
        .anyMatch(e -> e.text().equals(text) && nowDayTime - e.dayTime() < 24000L);
  }

  /**
   * A stretch of the same trouble: the complaint, when its unbroken run began,
   * and whether the run reaches the oldest issue the capped log still holds, in
   * which case it may have begun earlier than the log can show.
   */
  public record StandingIssue(String text, long since, boolean atLeast) {
  }

  /**
   * How old the newest issue may be and still count as standing: the day within
   * which {@link #hasRecentIssue} holds a repeat back, plus a day of slack for
   * the re-log that follows it.
   */
  private static final long STANDING_WINDOW = 2 * 24000L;

  /**
   * The person's standing trouble, if any: their newest issue while it is still
   * current, with how long the same complaint has stood. A worker who cannot
   * work re-logs the same sentence about once a day, so an unbroken run of it,
   * each entry within {@link #STANDING_WINDOW} of the next, is one stretch of
   * trouble and its first entry is when it began. Read by the build briefing
   * (UrbanPlanner), which states it as a fact for the brain to weigh.
   */
  public Optional<StandingIssue> standingIssue(long nowDayTime) {
    List<Entry> issues = issuesNewestFirst();
    if (issues.isEmpty() || nowDayTime - issues.get(0).dayTime() >= STANDING_WINDOW) {
      return Optional.empty();
    }
    Entry newest = issues.get(0);
    long since = newest.dayTime();
    int run = 1;
    for (Entry earlier : issues.subList(1, issues.size())) {
      if (!earlier.text().equals(newest.text()) || since - earlier.dayTime() >= STANDING_WINDOW) {
        break;
      }
      since = earlier.dayTime();
      run++;
    }
    boolean atLeast = run == issues.size() && issues.size() >= MAX_ISSUES;
    return Optional.of(new StandingIssue(newest.text(), since, atLeast));
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
