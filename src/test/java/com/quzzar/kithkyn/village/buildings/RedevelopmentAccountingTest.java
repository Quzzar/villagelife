package com.quzzar.kithkyn.village.buildings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;

class RedevelopmentAccountingTest {
  @Test
  void salvageIsHalfOfActualAggregatedInvestmentRoundedDownPerBuilding() {
    List<MaterialAmount> first = MaterialAmount.fromStacks(List.of(
        new ItemStack(Items.COBBLESTONE, 2), new ItemStack(Items.COBBLESTONE, 3)));
    assertEquals(List.of(new MaterialAmount(Items.COBBLESTONE, 2)), MaterialAmount.salvage(first));
    assertEquals(List.of(new MaterialAmount(Items.COBBLESTONE, 4)),
        MaterialAmount.combine(MaterialAmount.salvage(first), MaterialAmount.salvage(first)));
  }

  @Test
  void freeAndLegacyBuildingsHaveNoInventedRefund() {
    Building building = new Building("house_plains_1", Rotation.NONE);
    assertTrue(MaterialAmount.salvage(building.getInvestment()).isEmpty());
  }

  @Test
  void anUpgradeKeepsPreviouslyPaidMaterialsAndAddsItsActualNewPayment() {
    Building first = new Building("house_plains_1", Rotation.NONE);
    first.recordInvestment(List.of(new MaterialAmount(Items.DARK_OAK_LOG, 15)));
    Building upgraded = Building.upgradeOf(first, "house_plains_2", net.minecraft.core.BlockPos.ZERO, Rotation.NONE);
    upgraded.recordInvestment(List.of(new MaterialAmount(Items.BIRCH_LOG, 9)));
    assertEquals(List.of(new MaterialAmount(Items.DARK_OAK_LOG, 7), new MaterialAmount(Items.BIRCH_LOG, 4)),
        MaterialAmount.salvage(upgraded.getInvestment()));
    assertEquals(1, first.getInvestment().size());
  }

  @Test
  void aSavedPlanKeepsItsExactRecipeAndRefundEvenAboveOneStack() {
    RedevelopmentPlan plan = plan(200, 80);
    var encoded = RedevelopmentPlan.CODEC.encodeStart(NbtOps.INSTANCE, plan).getOrThrow();
    RedevelopmentPlan decoded = RedevelopmentPlan.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
    assertEquals(plan, decoded);
    assertEquals(List.of(new MaterialAmount(Items.COBBLESTONE, 120)), decoded.netRequired());
  }

  @Test
  void onlySalvageUsedByTheNewRecipeBecomesNewInvestment() {
    RedevelopmentWork work = new RedevelopmentWork(plan(40, 60));
    assertEquals(List.of(new MaterialAmount(Items.COBBLESTONE, 40)), work.commitCredit());
    var saved = RedevelopmentWork.CODEC.encodeStart(NbtOps.INSTANCE, work).getOrThrow();
    RedevelopmentWork restored = RedevelopmentWork.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
    assertEquals(saved, RedevelopmentWork.CODEC.encodeStart(NbtOps.INSTANCE, restored).getOrThrow());
  }

  private static RedevelopmentPlan plan(int cost, int salvage) {
    return new RedevelopmentPlan(UUID.randomUUID(), "house_plains_2", ConstructionMode.FRESH,
        Optional.empty(), 0L, Rotation.NONE, 0, List.of(), List.of(),
        List.of(new MaterialAmount(Items.COBBLESTONE, cost)),
        List.of(new MaterialAmount(Items.COBBLESTONE, salvage)), List.of(), List.of());
  }
}
