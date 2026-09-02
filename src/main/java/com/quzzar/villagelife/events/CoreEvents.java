package com.quzzar.villagelife.events;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.FarmedStock;
import com.quzzar.villagelife.village.VillageManager;
import com.quzzar.villagelife.village.VillageGeneration;
import com.quzzar.villagelife.village.bookkeeping.DeathBookkeepingEvent;
import com.quzzar.villagelife.village.bookkeeping.HurtByPlayerBookkeepingEvent;
import com.quzzar.villagelife.village.buildings.Building;
import com.quzzar.villagelife.village.buildings.BuildingInfo;
import com.quzzar.villagelife.village.buildings.Buildings;
import com.quzzar.villagelife.village.buildings.InstantBuildStructure;
import com.quzzar.villagelife.wrongdoing.Witnesses;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = Villagelife.MODID)
public class CoreEvents {

  @SubscribeEvent
  public static void onLivingSpawned(EntityJoinLevelEvent event) {
    // Config "Wandering merchant": stand our own merchant in for Minecraft's
    // wandering trader. Cancel the vanilla trader's join and spawn ours where it
    // was headed, reusing vanilla's spawn timing and placement; cancel the
    // trader's llamas too, since our merchant brings its own escort. Our escort
    // llamas carry a tag so this same handler lets them through.
    if (com.quzzar.villagelife.configuration.VillagelifeConfig.WanderingMerchant
        && event.getLevel() instanceof ServerLevel merchantLevel) {
      if (event.getEntity() instanceof net.minecraft.world.entity.npc.WanderingTrader) {
        event.setCanceled(true);
        com.quzzar.villagelife.village.WanderingMerchantSpawner.spawnAt(merchantLevel,
            event.getEntity().blockPosition());
        return;
      }
      if (event.getEntity() instanceof net.minecraft.world.entity.animal.horse.TraderLlama llama
          && !llama.getPersistentData()
              .getBoolean(com.quzzar.villagelife.village.WanderingMerchantSpawner.MERCHANT_LLAMA_TAG)) {
        event.setCanceled(true);
        return;
      }
    }

    if (event.getEntity() instanceof Enemy
        && !(event.getEntity() instanceof EnderMan)
        && !(event.getEntity() instanceof Creeper)) {
      Mob mob = (Mob) event.getEntity();
      mob.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(mob, Person.class, false));
    }
  }

  /**
   * Farmed is hereditary: the pen's calves are the pen's. Either parent
   * carrying the mark is enough, so a wild partner does not launder the
   * newborn back into game for the hunter (FarmedStock).
   */
  @SubscribeEvent
  public static void onBabyBorn(BabyEntitySpawnEvent event) {
    if (event.getChild() == null) {
      return;
    }
    if (FarmedStock.isFarmed(event.getParentA()) || FarmedStock.isFarmed(event.getParentB())) {
      FarmedStock.mark(event.getChild());
    }
  }

  @SubscribeEvent
  public static void onLivingDamaged(LivingDamageEvent.Post event) {

    if (event.getEntity().level().isClientSide()) {
      return;
    }

    if (event.getEntity() instanceof RealPerson person) {

      // Being attacked is worth remembering: it lands in the personal log with
      // the calendar time and surfaces in conversation.
      if (event.getSource().getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker
          && attacker != person) {
        person.logIssue("I was attacked by " + attacker.getName().getString(),
            java.util.Optional.of(attacker.getUUID()));

        // Pain needs no interpretation (docs/relationships.md): a blow from a
        // player moves the victim's own opinion of them at once, scaled by how
        // hard it landed. Witnesses still reach their judgements through
        // reflection; this is only what the victim feels in their own skin.
        if (attacker instanceof Player player) {
          com.quzzar.villagelife.relationships.OpinionService.apply(person, player.getUUID(),
              -(com.quzzar.villagelife.configuration.VillagelifeConfig.AssaultOpinionHit
                  + Math.round(event.getNewDamage())),
              "struck me");
        }
      }

      if (event.getSource().is(DamageTypes.CRAMMING)
          || event.getSource().is(DamageTypes.DROWN)
          || event.getSource().is(DamageTypes.FREEZE)
          || event.getSource().is(DamageTypes.IN_WALL)
          || event.getSource().is(DamageTypes.LAVA)
          || event.getSource().is(DamageTypes.FELL_OUT_OF_WORLD)) {

        person.tpToHome();

      }

    }

  }

  @SubscribeEvent
  public static void onLivingHurt(LivingIncomingDamageEvent event) {

    if (event.getEntity().level().isClientSide()) {
      return;
    }

    if (event.getEntity() == null || event.getSource().getEntity() == null) {
      return;
    }

    if (event.getEntity() instanceof RealPerson person) {

      if (event.getSource().getEntity() instanceof Player && person.getVillage() != null
          && person.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
        UUID damagerUUID = ((Player) event.getSource().getEntity()).getUUID();
        // Blame needs a witness (#64), and the victim is not one: what the
        // books record is what the SETTLEMENT knows. A villager set upon alone
        // in a wood leaves the village none the wiser about who did it, so an
        // unseen report gates both the mood entry and the witnesses' memories.
        if (com.quzzar.villagelife.wrongdoing.Wrongdoing.report(serverLevel, person.getVillage(),
            damagerUUID, com.quzzar.villagelife.wrongdoing.Wrongdoing.Offence.ASSAULT,
            person.position(),
            "I saw " + person.getFullName() + " attacked", person)) {
          person.getVillage().logEvent(
              new HurtByPlayerBookkeepingEvent(
                  person.getUUID(),
                  BlockPos.containing(person.getEyePosition()).asLong(),
                  person.getOccupation(),
                  person.getMarriageStatus(),
                  event.getSource().getMsgId(),
                  damagerUUID));
        }
      }

    }
  }

  @SubscribeEvent
  public static void onLivingDeath(LivingDeathEvent event) {

    if (event.getEntity().level().isClientSide()) {
      return;
    }

    // A villager who kills something that was hunting a neighbour is remembered
    // for it by the neighbour, and only by them.
    if (event.getEntity() instanceof net.minecraft.world.entity.monster.Enemy
        && event.getEntity() instanceof net.minecraft.world.entity.Mob hunter
        && hunter.getTarget() instanceof RealPerson rescued
        && event.getSource().getEntity() instanceof RealPerson defender
        && !defender.getUUID().equals(rescued.getUUID())) {
      rescued.logIssue(defender.getFullName() + " killed the "
          + event.getEntity().getName().getString() + " that was coming for me.",
          java.util.Optional.of(defender.getUUID()));
    }

    if (event.getEntity() instanceof RealPerson person) {

      if (person.getVillage() != null) {

        UUID killerUUID = null;
        if (person.getKillCredit() instanceof Player) {
          killerUUID = ((Player) person.getKillCredit()).getUUID();
        }

        person.getVillage().logEvent(
            new DeathBookkeepingEvent(
                person.getUUID(),
                BlockPos.containing(person.getEyePosition()).asLong(),
                person.getOccupation(),
                person.getMarriageStatus(),
                event.getSource().getMsgId(),
                killerUUID));

      }

      // A death dealt directly by a player is a murder, the worst offence a
      // village knows (#64). The victim is no witness to it; whoever else saw
      // it writes it down with the killer's name attached, and the village's
      // mood already grieves through the death entry above.
      if (event.getSource().getEntity() instanceof Player killer
          && person.level() instanceof ServerLevel murderScene) {
        com.quzzar.villagelife.wrongdoing.Wrongdoing.report(murderScene, person.getVillage(),
            killer.getUUID(), com.quzzar.villagelife.wrongdoing.Wrongdoing.Offence.MURDER,
            person.position(),
            "I saw " + person.getFullName() + " murdered", person);
      }

      // A villager's neighbours remember seeing them die, so they can speak of
      // it afterwards - the chat briefing reads the same issue log. Only what
      // was actually witnessed is recorded, the line-of-sight rule wrongdoing
      // uses. A death dealt by a player is a murder, which the wrongdoing system
      // witnesses on its own terms, so it is left out of this path.
      if (person.level() instanceof ServerLevel serverLevel
          && !(event.getSource().getEntity() instanceof Player)) {
        String memory = deathMemory(person, event.getSource().getEntity());
        for (RealPerson witness : Witnesses.around(serverLevel, person.getEyePosition(), person)) {
          witness.logIssue(memory, java.util.Optional.empty());
        }
      }

    }

  }

  /** How a witness remembers seeing a villager die, in their own voice. */
  private static String deathMemory(RealPerson person, Entity killer) {
    String name = person.getFullName();
    if (killer instanceof RealPerson villager) {
      return "I saw " + name + " killed by " + villager.getFullName() + ".";
    }
    if (killer != null) {
      return "I saw " + name + " killed by a " + killer.getName().getString() + ".";
    }
    return "I saw " + name + " die.";
  }

  @SubscribeEvent
  public static void onBellInteract(PlayerInteractEvent.RightClickBlock event) {
    if (!event.isCanceled() && !event.getLevel().isClientSide
        && event.getLevel().getBlockState(event.getPos()).getBlock().equals(Blocks.BELL)) {

      AABB aabb = (new AABB(event.getPos())).inflate(48.0D);
      List<RealPerson> nearbyPeople = event.getLevel().getEntitiesOfClass(RealPerson.class, aabb);

      for (RealPerson person : nearbyPeople) {
        if (person.isAlive() && !person.isRemoved()) {
          if (person.getOccupation().sleepsAtNight()) {
            person.goToBed(0.7D);
          } else {
            // The watch does not bed down on a bell: no walk to the bed and no
            // interrupting whatever they are doing, but the stow-and-restock
            // half of bedtime still applies.
            person.restockForNightWatch();
          }
        }
      }

    }
  }

  @SubscribeEvent
  public static void onServerWorldTick(LevelTickEvent.Post event) {
    if (!(event.getLevel() instanceof ServerLevel serverLevel) || serverLevel.dimension() != Level.OVERWORLD) {
      return;
    }

    // Every 1 second
    if (serverLevel.getGameTime() % 20 == 0) {
      VillageManager.get(serverLevel).tick(serverLevel);
      VillageGeneration.tick(serverLevel);
    }
  }

}
