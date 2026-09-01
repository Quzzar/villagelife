package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.List;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Turning one thing into another at a workstation: a CONVERT step.
 *
 * The only verb that is already pure data. The goal this replaces was one class
 * instantiated seven times with nothing but different arguments - cobblestone
 * to stone, stone to bricks, sand to sandstone, logs to planks, melon to seeds,
 * pumpkin to seeds, seeds to bone meal. Those seven are recipes living in Java
 * that want to be JSON, and they are the obvious first content to move once a
 * job-definition loader exists.
 *
 * <b>Nothing teleports.</b> The worker fetches the input from a real chest into
 * their pack, converts it at their station batch by batch, and carries the
 * output back to a chest by hand (docs/worker-loops.md). The step alternates
 * its target the way {@link GatherStep} does: a chest while the pack is short
 * or holds finished goods, the station while there is a batch in hand. One
 * chest visit does both directions - the output is set down first, which is
 * exactly what frees the space the next input comes out of.
 */
public final class CraftStep implements BlockWorkStep {

  /** Batches carried per fetch trip, so the walk amortizes over several converts. */
  private static final int BATCHES_PER_TRIP = 2;

  private final ItemStack input;
  private final ItemStack output;
  private final int seconds;
  private final SoundEvent sound;

  public CraftStep(ItemStack input, ItemStack output, int seconds, SoundEvent sound) {
    this.input = input;
    this.output = output;
    this.seconds = seconds;
    this.sound = sound;
  }

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    Village village = person.getVillage();
    if (village == null) {
      return null;
    }
    // A batch in hand: to the station. Resolved per scan - the old goal read
    // this once in its constructor, so a worker given their station afterwards
    // carried a zeroed one for life.
    if (PackLogistics.carried(person, this.input.getItem()) >= this.input.getCount()) {
      BlockPos station = LocationManager.getJobLocation(person);
      return station == BlockPos.ZERO ? null : station;
    }
    // Otherwise a chest: one that holds more input, or failing that, one with
    // room for the finished goods still on the worker's back.
    BlockPos source = PackLogistics.chestHolding(person, village, List.of(fetchTarget()));
    if (source != null) {
      return source;
    }
    if (PackLogistics.carried(person, this.output.getItem()) > 0) {
      return PackLogistics.chestWithRoomFor(person, village, this.output);
    }
    return null;
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    Village village = person.getVillage();
    if (village == null) {
      return false;
    }
    Container chest = PackLogistics.containerAt(person, target);
    if (chest != null) {
      // Set down what is finished, then load up: the set-down is what makes
      // the room the next input comes out of.
      PackLogistics.depositCarried(person, chest, this.output.getItem(), role(person));
      PackLogistics.pullWanted(person, chest, List.of(fetchTarget()), role(person));
      return false; // re-select: the station if loaded, another chest if not
    }
    // At the station with less than a batch: the pack was emptied or robbed on
    // the way. Back to select for a chest.
    if (PackLogistics.carried(person, this.input.getItem()) < this.input.getCount()) {
      return false;
    }
    person.setPose(Pose.CROUCHING);
    person.level().playSound((Player) null, target.getX(), target.getY(), target.getZ(),
        this.sound, SoundSource.PLAYERS, 0.5F, person.getRandom().nextFloat() * 0.4F + 0.6F);
    Utils.removeItem(person.personMainInv, this.input.getItem(), this.input.getCount());
    Utils.insertItems(person.personMainInv, List.of(this.output.copy()), person);
    // Another batch in hand keeps them at the bench; otherwise re-select walks
    // the output to a chest and fetches the next load in the same trip.
    return PackLogistics.carried(person, this.input.getItem()) >= this.input.getCount();
  }

  @Override
  public void released(RealPerson person, BlockPos target) {
    person.setPose(Pose.STANDING);
  }

  @Override
  public String describe() {
    return "my workbench";
  }

  /** One batch per configured interval. */
  @Override
  public int actEveryTicks() {
    return 20 * this.seconds;
  }

  /** What a fetch trip tries to bring home: a few batches' worth of input. */
  private ItemStack fetchTarget() {
    return new ItemStack(this.input.getItem(), this.input.getCount() * BATCHES_PER_TRIP);
  }

  private String role(RealPerson person) {
    return person.getOccupation().name();
  }
}
