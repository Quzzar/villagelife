package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.quzzar.kithkyn.savedata.PlacedBlockStore;
import com.quzzar.kithkyn.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Persisted progress through a committed removal, with surplus salvage held until it is earned. */
public final class RedevelopmentWork {
  public static final Codec<RedevelopmentWork> CODEC = RecordCodecBuilder.create(instance -> instance.group(
      RedevelopmentPlan.CODEC.fieldOf("plan").forGetter(work -> work.plan),
      Codec.intRange(0, Integer.MAX_VALUE).fieldOf("cursor").forGetter(work -> work.cursor),
      MaterialAmount.CODEC.listOf().fieldOf("refund").forGetter(work -> work.refund)
  ).apply(instance, RedevelopmentWork::new));

  private final RedevelopmentPlan plan;
  private int cursor;
  private List<MaterialAmount> refund;

  public RedevelopmentWork(RedevelopmentPlan plan) {
    this(plan, 0, List.of());
  }

  private RedevelopmentWork(RedevelopmentPlan plan, int cursor, List<MaterialAmount> refund) {
    this.plan = plan;
    this.cursor = cursor;
    this.refund = List.copyOf(refund);
  }

  public RedevelopmentPlan plan() {
    return plan;
  }

  public int remainingBlocks() {
    return Math.max(0, plan.blocks().size() - cursor);
  }

  /** Allocates salvage to the secured recipe without ever making anticipated salvage village stock. */
  public List<MaterialAmount> commitCredit() {
    Map<Item, Integer> net = MaterialAmount.tally(plan.netRequired());
    List<ItemStack> usedRecipe = new ArrayList<>();
    for (MaterialAmount amount : plan.required()) {
      int used = amount.count() - net.getOrDefault(amount.item(), 0);
      if (used > 0) {
        usedRecipe.add(new ItemStack(amount.item(), used));
      }
    }
    SimpleContainer recovered = new SimpleContainer(MaterialAmount.stacks(plan.salvage()).toArray(ItemStack[]::new));
    List<MaterialAmount> invested = MaterialAmount.fromStacks(Materials.spend(recovered, usedRecipe));
    List<ItemStack> surplus = new ArrayList<>();
    for (int slot = 0; slot < recovered.getContainerSize(); slot++) {
      surplus.add(recovered.getItem(slot));
    }
    refund = MaterialAmount.fromStacks(surplus);
    return invested;
  }

  /** One builder swing. An edited or refilled block pauses work without consuming its queue entry. */
  public String step(Village village) {
    if (cursor >= plan.blocks().size()) {
      return "";
    }
    RedevelopmentPlan.RemovalBlock next = plan.blocks().get(cursor);
    BlockPos at = BlockPos.of(next.position());
    var level = village.getLevel().getLevel();
    if (!level.hasChunkAt(at)) {
      return "The demolition site is not loaded.";
    }
    PlacedBlockStore ownership = PlacedBlockStore.get(level);
    var current = level.getBlockState(at);
    if (current.isAir() && !com.quzzar.kithkyn.village.BlockOwnership.isPlanted(next.state())) {
      return "A structural block is missing from the demolition site; secured salvage is no longer intact.";
    }
    if (ownership.isPlayerPlaced(at) || !current.isAir() && current.getBlock() != next.state().getBlock()) {
      return "Someone changed a block in the demolition site; work is paused.";
    }
    if (level.getBlockEntity(at) instanceof Container container && !container.isEmpty()) {
      return "A container in the demolition site has been refilled; work is paused.";
    }
    if (!current.isAir()) {
      // Explicit salvage replaces ordinary drops. Suppressing shape propagation also prevents
      // attached torches or plants from breaking into a second, unaccounted material payment.
      if (!level.setBlock(at, Blocks.AIR.defaultBlockState(),
          Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS)) {
        return "A block in the demolition site could not be removed.";
      }
    }
    ownership.clearPlaced(at);
    cursor++;
    return "";
  }

  /** Pays surplus once after all named blocks are removed; full storage leaves a persisted debt. */
  public void finish(Village village) {
    if (remainingBlocks() != 0) {
      return;
    }
    village.queueRedevelopmentRefund(refund);
    refund = List.of();
    village.rebuildBuildingClaims();
  }
}
