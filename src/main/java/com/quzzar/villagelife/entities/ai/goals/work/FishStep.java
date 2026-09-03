package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * The fisher's wait: stand at the fishery and draw a fish from adjacent water on
 * a slow cadence.
 *
 * docs/worker-loops.md singles the fisher out as the one job whose real content
 * -- waiting -- does not fit a target-and-verb like the others. So the target is
 * simply the fishing spot (the station, once there is water beside it) and the
 * "act" is time passing: one catch per interval, held as long as the water and
 * the daylight last. Fish land in the fisher's pack; a {@link FishCookStep} at
 * higher priority roasts a batch of the catch at the village fire, and a
 * {@link HaulStep} carries a full pack back, exactly as the miner's ore does.
 * The fisher works with a fishing rod (kit and {@link com.quzzar.villagelife.entities.JobTool}),
 * though the catch does not depend on the rod: it is the mark of the trade and
 * the thing a better or enchanted pole upgrades, as the hunter's bow is.
 */
public final class FishStep implements BlockWorkStep {

  /**
   * How far around the station, horizontally, to look for water to fish. The
   * fishery's own pool sits a few blocks off the station, so this comfortably
   * covers it with room to spare, and a fisher whose pool is dry works any
   * water source near the post too (Aaron: his pool, or any water nearby).
   */
  private static final int SEARCH = 6;

  /** How far above and below the station water counts: the pool sits a step down. */
  private static final int DEPTH = 2;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    BlockPos station = LocationManager.getJobLocation(person);
    if (station == BlockPos.ZERO || !waterNear(person, station)) {
      return null;
    }
    return station;
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    if (!waterNear(person, target)) {
      return false; // the water dried up or was built over: look again
    }
    person.swing(person.getUsedItemHand());
    person.level().playSound((Player) null, target.getX(), target.getY(), target.getZ(),
        SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.PLAYERS, 0.5F,
        0.8F + person.getRandom().nextFloat() * 0.4F);
    ItemStack caught = new ItemStack(person.getRandom().nextInt(4) == 0 ? Items.SALMON : Items.COD);
    person.addItems(List.of(caught));
    return true; // keep fishing; the loop's interrupts and its night check end it
  }

  @Override
  public String describe() {
    return "the water's edge";
  }

  @Override
  public String activity() {
    return "fishing at the water's edge";
  }

  /** A catch every twenty seconds -- the waiting is the point, not the throughput. */
  @Override
  public int actEveryTicks() {
    return 20 * 20;
  }

  private boolean waterNear(RealPerson person, BlockPos station) {
    for (BlockPos p : BlockPos.betweenClosed(station.offset(-SEARCH, -DEPTH, -SEARCH),
        station.offset(SEARCH, DEPTH, SEARCH))) {
      if (person.level().getBlockState(p).is(Blocks.WATER)) {
        return true;
      }
    }
    return false;
  }
}
