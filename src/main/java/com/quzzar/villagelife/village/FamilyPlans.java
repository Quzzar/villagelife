package com.quzzar.villagelife.village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Persistent scheduling state for each married couple's family decisions. */
public final class FamilyPlans {

  private static final String KEY = "family_plans";
  private static final String FIRST = "first";
  private static final String SECOND = "second";
  private static final String NEXT_TALK_DAY = "next_talk_day";
  private static final String BIRTH_DAY = "birth_day";
  private static final long NO_BIRTH = -1L;

  /** One canonical married pair's next conversation or pending birth. */
  public record Plan(UUID first, UUID second, long nextTalkDay, long birthDay) {

    public Plan {
      if (first.compareTo(second) > 0) {
        UUID swap = first;
        first = second;
        second = swap;
      }
    }

    public String key() {
      return first + ":" + second;
    }

    public boolean hasPendingBirth() {
      return birthDay >= 0;
    }

    private CompoundTag toTag() {
      CompoundTag tag = new CompoundTag();
      tag.putUUID(FIRST, first);
      tag.putUUID(SECOND, second);
      tag.putLong(NEXT_TALK_DAY, nextTalkDay);
      tag.putLong(BIRTH_DAY, birthDay);
      return tag;
    }

    private static Optional<Plan> fromTag(CompoundTag tag) {
      if (!tag.hasUUID(FIRST) || !tag.hasUUID(SECOND)) {
        return Optional.empty();
      }
      return Optional.of(new Plan(
          tag.getUUID(FIRST), tag.getUUID(SECOND),
          tag.getLong(NEXT_TALK_DAY), tag.contains(BIRTH_DAY) ? tag.getLong(BIRTH_DAY) : NO_BIRTH));
    }
  }

  private FamilyPlans() {
  }

  public static List<Plan> current(Village village) {
    return List.copyOf(load(village));
  }

  public static Optional<Plan> get(Village village, UUID first, UUID second) {
    String key = keyOf(first, second);
    return load(village).stream().filter(plan -> plan.key().equals(key)).findFirst();
  }

  /** Starts a newly housed couple's first waiting period without resetting an existing plan. */
  public static Plan ensure(Village village, UUID first, UUID second, long firstTalkDay) {
    Optional<Plan> existing = get(village, first, second);
    if (existing.isPresent()) {
      return existing.get();
    }
    Plan plan = new Plan(first, second, firstTalkDay, NO_BIRTH);
    put(village, plan);
    return plan;
  }

  public static void scheduleTalk(Village village, UUID first, UUID second, long day) {
    put(village, new Plan(first, second, day, NO_BIRTH));
  }

  public static void scheduleBirth(Village village, UUID first, UUID second, long day) {
    put(village, new Plan(first, second, Long.MAX_VALUE, day));
  }

  /** Removes plans whose marriage edge no longer exists in this village. */
  public static void retainMarriages(Village village, Set<String> marriedKeys) {
    List<Plan> plans = load(village);
    if (plans.removeIf(plan -> !marriedKeys.contains(plan.key()))) {
      save(village, plans);
    }
  }

  private static void put(Village village, Plan replacement) {
    List<Plan> plans = load(village);
    plans.removeIf(plan -> plan.key().equals(replacement.key()));
    plans.add(replacement);
    plans.sort((first, second) -> first.key().compareTo(second.key()));
    save(village, plans);
  }

  private static List<Plan> load(Village village) {
    ListTag list = village.getBrain().getStrategy().getList(KEY, Tag.TAG_COMPOUND);
    List<Plan> plans = new ArrayList<>(list.size());
    for (int index = 0; index < list.size(); index++) {
      Plan.fromTag(list.getCompound(index)).ifPresent(plans::add);
    }
    return plans;
  }

  private static void save(Village village, List<Plan> plans) {
    ListTag list = new ListTag();
    for (Plan plan : plans) {
      list.add(plan.toTag());
    }
    village.getBrain().getStrategy().put(KEY, list);
  }

  private static String keyOf(UUID first, UUID second) {
    return first.compareTo(second) <= 0 ? first + ":" + second : second + ":" + first;
  }
}
