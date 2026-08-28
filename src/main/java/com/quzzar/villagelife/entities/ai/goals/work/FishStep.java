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
 * the daylight last. Fish land in the fisher's pack and a {@link HaulStep} at
 * higher priority carries a full one back, exactly as the miner's ore does.
 */
public final class FishStep implements BlockWorkStep {

  /** How far around the station to look for water to fish. */
  private static final int SEARCH = 4;

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

  /** A catch every twenty seconds -- the waiting is the point, not the throughput. */
  @Override
  public int actEveryTicks() {
    return 20 * 20;
  }

  private boolean waterNear(RealPerson person, BlockPos station) {
    for (BlockPos p : BlockPos.betweenClosed(station.offset(-SEARCH, -1, -SEARCH),
        station.offset(SEARCH, 1, SEARCH))) {
      if (person.level().getBlockState(p).is(Blocks.WATER)) {
        return true;
      }
    }
    return false;
  }
}
