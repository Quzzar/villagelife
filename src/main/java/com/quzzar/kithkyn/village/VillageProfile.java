package com.quzzar.kithkyn.village;

import java.util.LinkedHashMap;
import java.util.Map;

import com.quzzar.kithkyn.Kithkyn;

/**
 * Where a village's tick actually goes ([#65](https://github.com/Quzzar/kithkyn/issues/65)).
 *
 * Villages tick whether or not anyone is near them, so their cost scales with
 * villages EXISTING rather than villages being visited — which makes the
 * per-village second the one piece of this mod that has to stay cheap forever.
 * The benchmark that opened #65 could see the spike but not its owner: 0.4 ms
 * per village on average and a P99 near the whole tick budget, with no way to
 * tell which pass was responsible.
 *
 * This is deliberately the cheapest thing that answers that: a nanosecond
 * timestamp either side of each pass, totals accumulated per name, and a
 * summary at DEBUG every minute naming the worst single call. It costs about
 * 50ns per measured segment, which is invisible against passes measured in
 * microseconds, and it is worth keeping so the next regression is attributable
 * rather than merely visible.
 */
public final class VillageProfile {

  /** Off unless someone is looking: /kkdev village profile turns it on. */
  private static boolean enabled = false;

  private static final Map<String, long[]> SEGMENTS = new LinkedHashMap<>();
  private static long lastReport = 0L;
  private static long ticks = 0L;

  /** Village seconds between summaries, once profiling is on. */
  private static final long REPORT_INTERVAL_SECONDS = 60L;

  private VillageProfile() {
  }

  public static void setEnabled(boolean on) {
    enabled = on;
    SEGMENTS.clear();
    ticks = 0L;
    // Village time is already large when profiling is switched on, so a zero
    // here fires the first summary immediately: an 18-tick window that reads
    // like a full minute and understates every total by two orders of
    // magnitude. Wait for a real window before reporting anything.
    lastReport = Long.MIN_VALUE;
  }

  public static boolean isEnabled() {
    return enabled;
  }

  /** Nanosecond stamp, or 0 when profiling is off so callers stay branch-cheap. */
  public static long start() {
    return enabled ? System.nanoTime() : 0L;
  }

  /** Records one call of a named pass. A zero start means profiling was off. */
  public static void end(String segment, long started) {
    if (!enabled || started == 0L) {
      return;
    }
    long taken = System.nanoTime() - started;
    long[] slot = SEGMENTS.computeIfAbsent(segment, key -> new long[3]);
    slot[0] += taken;         // total nanos
    slot[1]++;                // calls
    slot[2] = Math.max(slot[2], taken); // worst single call
  }

  /** Called once per village tick so the summary knows how many ticks it covers. */
  public static void tickCounted(int villageTime) {
    if (!enabled) {
      return;
    }
    ticks++;
    if (lastReport == Long.MIN_VALUE) {
      lastReport = villageTime;
      return;
    }
    if (villageTime - lastReport < REPORT_INTERVAL_SECONDS) {
      return;
    }
    lastReport = villageTime;
    report();
  }

  private static void report() {
    if (SEGMENTS.isEmpty()) {
      return;
    }
    StringBuilder out = new StringBuilder("Village tick profile over " + ticks + " village-ticks:");
    SEGMENTS.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
        .forEach(entry -> {
          long[] v = entry.getValue();
          out.append(String.format("%n  %-22s total %7.1f ms over %5d calls, worst single %6.2f ms",
              entry.getKey(), v[0] / 1_000_000.0D, v[1], v[2] / 1_000_000.0D));
        });
    Kithkyn.LOGGER.info(out.toString());
    SEGMENTS.clear();
    ticks = 0L;
  }

}
