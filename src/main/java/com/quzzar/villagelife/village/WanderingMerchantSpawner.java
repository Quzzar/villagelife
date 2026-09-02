package com.quzzar.villagelife.village;

import java.util.ArrayList;
import java.util.List;

import com.quzzar.villagelife.economy.EconomySnapshot;
import com.quzzar.villagelife.economy.Treasury;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.persona.PersonaSpawner;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.horse.TraderLlama;

/**
 * Puts a wandering merchant where Minecraft's wandering trader would have gone
 * (config "Wandering merchant"). The vanilla trader's join is cancelled in
 * {@code CoreEvents}, and this stands in its place: pick a RANDOM village whose
 * economy has been cached ({@link Village#getEconomySnapshot}), seed a merchant
 * from a copy of that snapshot, and escort it with the trader's iconic llamas.
 * The when and the where are still Minecraft's; only who arrives changes.
 *
 * <p>Random rather than nearest on purpose: nearest would make every wandering
 * merchant hail from the one village next to the player.
 */
public final class WanderingMerchantSpawner {

  /** Ticks a merchant lingers before it packs up and leaves: two in-game days, as the vanilla trader. */
  private static final int DESPAWN_TICKS = 48000;

  /** How far a merchant strays from where it arrived before it is drawn back. */
  private static final int ROAM_RADIUS = 48;

  /** Llamas an escort has, matching the vanilla trader's one or two. */
  private static final int MIN_LLAMAS = 1;
  private static final int MAX_LLAMAS = 2;

  /**
   * Marks a llama we spawned ourselves, so the same {@code CoreEvents} handler
   * that cancels the vanilla trader's llamas lets our escort through.
   */
  public static final String MERCHANT_LLAMA_TAG = "villagelife_merchant_llama";

  private WanderingMerchantSpawner() {
  }

  /**
   * Spawns a wandering merchant at the position the vanilla trader was headed
   * for. Does nothing if no village anywhere has a cached economy to send one
   * from, in which case the cycle simply passes with no trader at all.
   */
  public static void spawnAt(ServerLevel level, BlockPos pos) {
    Village source = pickSourceVillage(level);
    if (source == null) {
      return;
    }
    EconomySnapshot ledger = source.getEconomySnapshot().copy();
    String villageId = source.getID();
    String villageName = source.getName();
    PersonaSpawner.trySpawn(level, pos, merchant -> {
      merchant.setWanderingMerchant(true);
      merchant.setOccupation(Occupation.WANDERING_MERCHANT);
      merchant.setSourceVillageUuid(villageId);
      merchant.setVillageName(villageName);
      merchant.setWanderingStock(ledger);
      merchant.setMerchantDespawnCountdown(DESPAWN_TICKS);
      merchant.restrictTo(pos, ROAM_RADIUS);
      // The flag was set after the constructor already registered a wanderer's
      // goals, so rebuild them now for the merchant it has become.
      merchant.reloadState();
    }).thenAccept(attempt -> attempt.spawned().ifPresent(merchant -> spawnLlamas(level, merchant)));
  }

  /** A random village whose economy has been cached and which still has a market, or null. */
  private static Village pickSourceVillage(ServerLevel level) {
    List<Village> eligible = new ArrayList<>();
    for (Village village : VillageManager.get(level).getVillages().values()) {
      EconomySnapshot snapshot = village.getEconomySnapshot();
      if (snapshot != null && !snapshot.isEmpty() && Treasury.market(village).isPresent()) {
        eligible.add(village);
      }
    }
    if (eligible.isEmpty()) {
      return null;
    }
    return eligible.get(level.getRandom().nextInt(eligible.size()));
  }

  /** Escorts the merchant with one or two trader llamas on a lead, as vanilla does. */
  private static void spawnLlamas(ServerLevel level, RealPerson merchant) {
    int count = MIN_LLAMAS + level.getRandom().nextInt(MAX_LLAMAS - MIN_LLAMAS + 1);
    for (int i = 0; i < count; i++) {
      TraderLlama llama = EntityType.TRADER_LLAMA.create(level);
      if (llama == null) {
        continue;
      }
      BlockPos spot = merchant.blockPosition().offset(
          level.getRandom().nextInt(3) - 1, 0, level.getRandom().nextInt(3) - 1);
      llama.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0F, 0F);
      llama.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), MobSpawnType.EVENT, null);
      llama.getPersistentData().putBoolean(MERCHANT_LLAMA_TAG, true);
      llama.setLeashedTo(merchant, true);
      level.addFreshEntity(llama);
    }
  }
}
