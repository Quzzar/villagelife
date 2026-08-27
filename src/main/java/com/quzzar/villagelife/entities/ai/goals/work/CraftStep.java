package com.quzzar.villagelife.entities.ai.goals.work;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
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
 * <b>The worker now goes to their workstation.</b> The old goal never moved:
 * it pulled the input out of village storage from wherever the villager was
 * standing and pushed the output back the same way, so a mason turning
 * cobblestone into stone was doing it in a field on the other side of the
 * village. It also had no {@code canContinueToUse}, so it fell back to
 * {@code canUse} and ran on as long as the village held the input - and it
 * tried to stop by calling {@code stop()} from inside {@code tick()}, which the
 * engine ignores.
 */
public final class CraftStep implements WorkStep {

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
    if (village == null || !village.hasItemStackInVillage(this.input)) {
      return null;
    }
    // Resolved per scan. The old goal read this once in its constructor, so a
    // worker given their station afterwards carried a zeroed one for life.
    BlockPos station = LocationManager.getJobLocation(person);
    return station == BlockPos.ZERO ? null : station;
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    Village village = person.getVillage();
    if (village == null || !village.hasItemStackInVillage(this.input)) {
      return false;
    }
    person.setPose(Pose.CROUCHING);
    person.level().playSound((Player) null, target.getX(), target.getY(), target.getZ(),
        this.sound, SoundSource.PLAYERS, 0.5F, person.getRandom().nextFloat() * 0.4F + 0.6F);

    village.gatherItemStackFromVillage(this.input);
    // Somewhere to put it is part of the job: when the village has no room the
    // work stops rather than the output vanishing.
    return village.placeItemStackIntoVillage(this.output, person, target);
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

}
