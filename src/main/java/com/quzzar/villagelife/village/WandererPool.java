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
      try {
        Entity entity = EntityType.create(entry.person(), level).orElse(null);
        if (entity instanceof RealPerson person) {
          person.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
              level.random.nextFloat() * 360F, 0F);
          return person;
        }
        Villagelife.LOGGER.error("'{}' could not be restored from beyond the horizon and is lost",
            entry.name());
      } catch (RuntimeException e) {
        Villagelife.LOGGER.error("'{}' could not be restored from beyond the horizon and is lost",
            entry.name(), e);
      }
    }
    return null;
  }

  /**
   * Draws a specific banked wanderer back by their UUID: the partner of a married
   * wanderer already drawn, so a couple that crossed the horizon together returns
   * together (docs/marriage.md, the family unit). Null when no entry with that id
   * is on the road, in which case the caller keeps the pair together some other
   * way rather than bringing one half in alone.
   */
  @Nullable
  public RealPerson drawPartner(ServerLevel level, BlockPos pos, @Nullable UUID partnerId) {
    if (partnerId == null) {
      return null;
    }
    for (int i = 0; i < entries.size(); i++) {
      CompoundTag tag = entries.get(i).person();
      if (!tag.hasUUID("UUID") || !partnerId.equals(tag.getUUID("UUID"))) {
        continue;
      }
      Entry entry = entries.remove(i);
      onChange.run();
      try {
        Entity entity = EntityType.create(entry.person(), level).orElse(null);
        if (entity instanceof RealPerson person) {
          person.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
              level.random.nextFloat() * 360F, 0F);
          return person;
        }
        Villagelife.LOGGER.error("'{}' could not be restored from beyond the horizon and is lost",
            entry.name());
      } catch (RuntimeException e) {
        Villagelife.LOGGER.error("'{}' could not be restored from beyond the horizon and is lost",
            entry.name(), e);
      }
      return null;
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
