package com.quzzar.kithkyn.entities.ai.goals.work;

import java.util.Arrays;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.village.LocationManager;
import com.quzzar.kithkyn.village.WorkArea;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Feeding the farm's composter and taking what it makes: a CONVERT step, and
 * the second link in the farmer's idle chain (brush in, bone meal out).
 *
 * Physical the whole way (docs/worker-loops.md): the composter is a real block
 * every farm structure ships, each fill spends one plant out of the pack rolled
 * at the block's own compost chance, and READY is vanilla's own 7-to-8 cure.
 * The one liberty is at the end - vanilla tosses the bone meal on the ground,
 * and this hands it straight into the pack so the loop cannot lose its own
 * produce to whatever wanders past.
 *
 * It eats two things: the brush the clearing step gathered, and surplus sowing
 * seeds. The seed half replaced an abstract seeds-to-bone-meal CraftStep that
 * converted village stores at the workstation; the farmer has a real composter
 * at the station, so it uses that instead.
 */
public final class CompostStep implements BlockWorkStep {

  /** The composter sits inside the farm, so the station scan stays tight. */
  private static final int STATION_RADIUS = 8;

  /**
   * Seeds kept back for sowing, per type; only what the pack holds past this is
   * surplus the composter may eat. Matches the helping the bedtime restock
   * takes (RealPerson.goToBed), so a night's restock is never composted whole.
   */
  private static final int SEEDS_KEPT_FOR_SOWING = 8;

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    BlockPos station = LocationManager.getJobLocation(person);
    if (station.equals(BlockPos.ZERO)) {
      return null;
    }
    WorkArea area = LocationManager.getJobWorkArea(person);
    if (area == null) {
      return null;
    }
    BlockPos composter = findComposter(person, station, area);
    if (composter == null) {
      return null;
    }
    int fill = person.level().getBlockState(composter).getValue(ComposterBlock.LEVEL);
    if (fill >= ComposterBlock.MAX_LEVEL) {
      return composter; // a batch is curing or done; worth the walk either way
    }
    return findFeedItem(person) == null ? null : composter;
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    BlockState state = person.level().getBlockState(target);
    if (!state.is(Blocks.COMPOSTER)) {
      return false; // the composter was taken out from under them
    }
    int fill = state.getValue(ComposterBlock.LEVEL);

    if (fill == ComposterBlock.READY) {
      person.level().setBlock(target, state.setValue(ComposterBlock.LEVEL, 0), 3);
      person.level().playSound((Player) null, target, SoundEvents.COMPOSTER_EMPTY,
          SoundSource.BLOCKS, 1.0F, 1.0F);
      person.addItems(Arrays.asList(new ItemStack(Items.BONE_MEAL)));
      return findFeedItem(person) != null; // done, unless there is more to feed
    }
    if (fill == ComposterBlock.MAX_LEVEL) {
      return true; // full and curing; the block's own tick rings it READY shortly
    }

    Item feed = findFeedItem(person);
    if (feed == null) {
      return false; // fed everything spare; back to select
    }
    ItemStack next = person.removeItem(feed, 1);
    if (next.isEmpty()) {
      return false;
    }
    BlockState after = ComposterBlock.insertItem(person, state, (ServerLevel) person.level(), next, target);
    // 1500 is the composter fill event: the right sound and rot particles on
    // every client, at the success pitch when the level actually rose.
    person.level().levelEvent(1500, target, after.getValue(ComposterBlock.LEVEL) > fill ? 1 : 0);
    return true;
  }

  @Override
  public String describe() {
    return "the farm's composter";
  }

  @Override
  public String activity() {
    return "feeding the farm's composter";
  }

  /** A fill every second or so reads as work without eating the whole day. */
  @Override
  public int actEveryTicks() {
    return 30;
  }

  /**
   * What to spend next: cleared brush first, then any sowing seed the pack
   * holds past its keep. The keep is what makes the seed half safe - the
   * farmer never composts what they still need to plant.
   */
  @Nullable
  private Item findFeedItem(RealPerson person) {
    for (int slot = 0; slot < person.personMainInv.getContainerSize(); slot++) {
      ItemStack stack = person.personMainInv.getItem(slot);
      if (ClearBrushStep.brushItem(stack)) {
        return stack.getItem();
      }
    }
    for (Item seed : TillStep.PLANTABLES.keySet()) {
      if (person.personMainInv.countItem(seed) > SEEDS_KEPT_FOR_SOWING) {
        return seed;
      }
    }
    return null;
  }

  /** The farm's own composter, wherever the structure put it. */
  @Nullable
  private BlockPos findComposter(RealPerson person, BlockPos around, WorkArea area) {
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int x = -STATION_RADIUS; x <= STATION_RADIUS; ++x) {
      for (int y = -2; y <= 2; ++y) {
        for (int z = -STATION_RADIUS; z <= STATION_RADIUS; ++z) {
          cursor.setWithOffset(around, x, y, z);
          if (!area.contains(cursor.getX(), cursor.getY(), cursor.getZ())) {
            continue;
          }
          if (person.level().getBlockState(cursor).is(Blocks.COMPOSTER)) {
            return cursor.immutable();
          }
        }
      }
    }
    return null;
  }

}
