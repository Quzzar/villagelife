package com.quzzar.villagelife.village;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.entities.AgeStage;
import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * The people on the road beyond the horizon: everyone who left a village and
 * walked out of the loaded world (docs/population-and-labor.md). The world only
 * exists near players, so "travelling the world" cannot be an entity walking
 * across unloaded chunks; it is this list. A wanderer crossing the horizon is
 * saved here whole, entity tag and all (persona, memories, pack, tools), and a
 * growing village with nobody roaming nearby draws the longest-gone of them
 * back into the world at its edge before it conjures anyone new. The same souls
 * circulate between settlements however far apart they stand.
 *
 * <p>Lives in the village registry's save data, so it is one list for the whole
 * server. Bounded by config: past the cap the longest-gone is forgotten, and a
 * cap of zero forgets everyone the moment they cross.
 */
public final class WandererPool {

  /** One person on the road: who, since when (game time), and everything they were. */
  public record Entry(String name, long since, CompoundTag person) {
    public static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        Codec.STRING.fieldOf("name").forGetter(Entry::name),
        Codec.LONG.fieldOf("since").forGetter(Entry::since),
        CompoundTag.CODEC.fieldOf("person").forGetter(Entry::person)
    ).apply(inst, Entry::new));
  }

  public static final Codec<List<Entry>> CODEC = Entry.CODEC.listOf();

  private final ArrayList<Entry> entries = new ArrayList<>();

  /** Marks the owning save data dirty; every change to the list runs it. */
  private final Runnable onChange;

  public WandererPool(Runnable onChange) {
    this.onChange = onChange;
  }

  /** Replaces the list with what was saved. Load only. */
  public void load(List<Entry> saved) {
    entries.clear();
    entries.addAll(saved);
  }

  /**
   * Remembers a loaded wanderer so they can come back somewhere else. The
   * caller discards the entity afterwards; this only copies it. Returns false
   * when the world keeps no such memory (a cap of zero) or the entity would not
   * serialize, in which case the person is simply gone.
   */
  public boolean bank(RealPerson person, long now) {
    int cap = VillagelifeConfig.WandererPoolCap;
    if (cap <= 0) {
      return false;
    }
    CompoundTag tag = new CompoundTag();
    if (!person.save(tag)) {
      Villagelife.LOGGER.warn("'{}' could not be remembered beyond the horizon and is gone",
          person.getFullName());
      return false;
    }
    entries.add(new Entry(person.getFullName(), now, tag));
    while (entries.size() > cap) {
      Entry forgotten = entries.remove(0);
      Villagelife.LOGGER.info("'{}' is not coming back: the world remembers only so many wanderers",
          forgotten.name());
    }
    onChange.run();
    return true;
  }

  /**
   * Brings the person longest on the road back into the world, standing at
   * {@code pos} but not yet added to the level, or null when nobody is on the
   * road. An entry that no longer restores (a build change since it was saved)
   * is dropped with an error rather than tried again forever: saves under old
   * names do not load, by decision, and the pool is no exception.
   */
  @Nullable
  public RealPerson draw(ServerLevel level, BlockPos pos) {
    while (!entries.isEmpty()) {
      Entry entry = entries.remove(0);
      onChange.run();
      RealPerson restored = restore(level, pos, entry);
      if (restored != null) {
        return restored;
      }
    }
    return null;
  }

  /** Draws every banked spouse, parent, dependent child, and dependent sibling of an anchor. */
  public List<RealPerson> drawFamilyMembers(ServerLevel level, BlockPos pos, RealPerson anchor) {
    ArrayList<RealPerson> family = new ArrayList<>();
    family.add(anchor);
    boolean changed;
    do {
      changed = false;
      for (int index = 0; index < entries.size(); index++) {
        Entry entry = entries.get(index);
        if (!relatesToFamily(entry.person(), family)) {
          continue;
        }
        entries.remove(index--);
        onChange.run();
        RealPerson restored = restore(level, pos, entry);
        if (restored != null) {
          family.add(restored);
          changed = true;
        }
      }
    } while (changed);
    return List.copyOf(family.subList(1, family.size()));
  }

  private static boolean relatesToFamily(CompoundTag tag, List<RealPerson> family) {
    if (!tag.hasUUID("UUID")) {
      return false;
    }
    UUID candidateId = tag.getUUID("UUID");
    UUID candidateSpouse = parseUuid(tag.getString("SpouseUUID"));
    List<UUID> candidateParents = parentIds(tag);
    AgeStage candidateStage = tag.contains("AgeStage")
        ? AgeStage.byName(tag.getString("AgeStage"))
        : tag.getBoolean("IsBaby") ? AgeStage.KID : AgeStage.ADULT;
    for (RealPerson member : family) {
      if (candidateId.equals(member.getSpouseId())
          || member.getUUID().equals(candidateSpouse)) {
        return true;
      }
      if (candidateStage.isDependentlyHoused() && candidateParents.contains(member.getUUID())) {
        return true;
      }
      if (member.getLifeStage().isDependentlyHoused()
          && member.getParentIds().contains(candidateId)) {
        return true;
      }
      if (candidateStage.isDependentlyHoused() && member.getLifeStage().isDependentlyHoused()
          && candidateParents.stream().anyMatch(member.getParentIds()::contains)) {
        return true;
      }
    }
    return false;
  }

  private static List<UUID> parentIds(CompoundTag tag) {
    ArrayList<UUID> parents = new ArrayList<>(2);
    UUID first = parseUuid(tag.getString("FirstParentUUID"));
    UUID second = parseUuid(tag.getString("SecondParentUUID"));
    if (first != null) {
      parents.add(first);
    }
    if (second != null) {
      parents.add(second);
    }
    return parents;
  }

  @Nullable
  private static UUID parseUuid(String value) {
    try {
      return value == null || value.isBlank() ? null : UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  @Nullable
  private static RealPerson restore(ServerLevel level, BlockPos pos, Entry entry) {
    try {
      Entity entity = EntityType.create(entry.person(), level).orElse(null);
      if (entity instanceof RealPerson person) {
        person.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
            level.random.nextFloat() * 360F, 0F);
        return person;
      }
      Villagelife.LOGGER.error("'{}' could not be restored from beyond the horizon and is lost",
          entry.name());
    } catch (RuntimeException error) {
      Villagelife.LOGGER.error("'{}' could not be restored from beyond the horizon and is lost",
          entry.name(), error);
    }
    return null;
  }

  /** Everyone on the road, longest gone first. */
  public List<Entry> entries() {
    return Collections.unmodifiableList(entries);
  }

  public int size() {
    return entries.size();
  }
}
