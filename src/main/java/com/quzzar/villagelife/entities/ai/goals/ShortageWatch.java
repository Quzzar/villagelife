package com.quzzar.villagelife.entities.ai.goals;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.bookkeeping.NoResourceBookkeepingEvent;

import net.minecraft.world.item.Item;

/**
 * Whether a gatherer has genuinely run dry, and saying so exactly once when it
 * has (docs/worker-loops.md, "When there is nothing to work on").
 *
 * A worker that finds nothing to gather returns null from its select every
 * scan, which is how it yields and falls back to wandering. Most of those nulls
 * are NORMAL, though: a felled tree waiting on its sapling, a field of crops not
 * yet ripe. Reporting a shortage on every dry scan would fire constantly and
 * bury the village's attractiveness under false famines. So a lull counts as a
 * shortage only once the worker has been dry for a sustained stretch, and it is
 * reported once per dry spell: the {@link NoResourceBookkeepingEvent} feeds
 * attractiveness and the planner, the personal-log issue surfaces in
 * conversation, and both decay and forgive on their own. Finding work again
 * ends the spell and rearms the report.
 *
 * This is the run-dry counterpart to {@link ApproachWatch}, which handles the
 * other way a work loop stalls: work that exists but cannot be walked to.
 */
public final class ShortageWatch {

  /**
   * Consecutive dry scans before a lull is treated as a genuine shortage rather
   * than the ordinary wait between finds. select scans about once a second, so
   * this is roughly a minute of finding nothing at all: long past the gap
   * between two ripe crops, short enough that a truly barren radius is noticed.
   */
  private static final int DRY_SCANS_BEFORE_SHORTAGE = 60;

  private int dryScans;
  private boolean reported;
  private String activeBlocker;

  /** The worker found something to do: the spell is over and the report rearms. */
  public void foundWork(RealPerson person) {
    if (activeBlocker != null) {
      person.clearBlocker(activeBlocker);
      activeBlocker = null;
    }
    this.dryScans = 0;
    this.reported = false;
  }

  /**
   * The worker found nothing to gather this scan. Once the dry stretch is long
   * enough, and once only, records the shortage against the village and in the
   * worker's own log; further dry scans in the same spell are silent.
   *
   * @param person  the worker who has run dry
   * @param missing a representative item they cannot currently supply
   * @param count   how much of it the shortage is framed as
   * @param issue   what the worker says about it, in their own words
   */
  public void wentDry(RealPerson person, Item missing, int count, String issue) {
    if (this.reported || ++this.dryScans < DRY_SCANS_BEFORE_SHORTAGE) {
      return;
    }
    this.reported = true;
    if (person.getVillage() != null) {
      person.getVillage().logEvent(new NoResourceBookkeepingEvent(missing, count));
    }
    this.activeBlocker = issue;
    person.logBlocker(issue);
  }
}
