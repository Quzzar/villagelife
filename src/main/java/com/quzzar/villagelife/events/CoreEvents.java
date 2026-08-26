package com.quzzar.villagelife.events;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.VillageManager;
import com.quzzar.villagelife.village.bookkeeping.DeathBookkeepingEvent;
import com.quzzar.villagelife.village.bookkeeping.HurtByPlayerBookkeepingEvent;
import com.quzzar.villagelife.village.buildings.Building;
import com.quzzar.villagelife.village.buildings.BuildingInfo;
import com.quzzar.villagelife.village.buildings.Buildings;
import com.quzzar.villagelife.village.buildings.InstantBuildStructure;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageTypes;
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
    if (event.getEntity() instanceof Enemy
        && !(event.getEntity() instanceof EnderMan)
        && !(event.getEntity() instanceof Creeper)) {
      Mob mob = (Mob) event.getEntity();
      mob.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(mob, Person.class, false));
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

      if (event.getSource().getEntity() instanceof Player && person.getVillage() != null) {
        UUID damagerUUID = ((Player) event.getSource().getEntity()).getUUID();
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

  @SubscribeEvent
  public static void onLivingDeath(LivingDeathEvent event) {

    if (event.getEntity().level().isClientSide()) {
      return;
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

    }

  }

  @SubscribeEvent
  public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {

    if (event.getLevel() instanceof ServerLevelAccessor) {

      if (event.getPlacedBlock().getBlock().equals(Blocks.DIAMOND_BLOCK)) {

        ServerLevelAccessor levelAccess = (ServerLevelAccessor) event.getLevel();

        VillageManager.get(levelAccess.getLevel()).registerVillage(levelAccess, event.getPos());

      }

      if (event.getPlacedBlock().getBlock().equals(Blocks.EMERALD_BLOCK)) {

        BuildingInfo BUILDING = Buildings.getByName("well_plains_1");
        if (BUILDING == null) {
          Villagelife.LOGGER.error("No 'well_1' building definition loaded; is the datapack intact?");
          return;
        }

        ServerLevelAccessor levelAccess = (ServerLevelAccessor) event.getLevel();

        Random random = new Random();
        Building building = new Building(BUILDING.getName(), Rotation.values()[random.nextInt(Rotation.values().length)]);
        InstantBuildStructure buildingStruct = new InstantBuildStructure(building, random, levelAccess)
            .setOriginLocation(event.getPos(), new HashSet<Long>());
        buildingStruct.buildInstantly();

        for (long loc : buildingStruct.getBuilding().getInfo().getBedLocations()) {
          levelAccess.setBlock(
              BlockPos.of(buildingStruct.getBuilding().getOriginLocation())
                  .offset(BlockPos.of(loc).rotate(buildingStruct.getBuilding().getRotation())),
              Blocks.REDSTONE_BLOCK.defaultBlockState(), 2);
        }
        for (long loc : buildingStruct.getBuilding().getInfo().getWorkLocations().keySet()) {
          levelAccess.setBlock(
              BlockPos.of(buildingStruct.getBuilding().getOriginLocation())
                  .offset(BlockPos.of(loc).rotate(buildingStruct.getBuilding().getRotation())),
              Blocks.IRON_BLOCK.defaultBlockState(), 2);
        }
        for (long loc : buildingStruct.getBuilding().getInfo().getContainerLocations()) {
          levelAccess.setBlock(
              BlockPos.of(buildingStruct.getBuilding().getOriginLocation())
                  .offset(BlockPos.of(loc).rotate(buildingStruct.getBuilding().getRotation())),
              Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        }

      }

    }

  }

  @SubscribeEvent
  public static void onBellInteract(PlayerInteractEvent.RightClickBlock event) {
    if (!event.isCanceled() && !event.getLevel().isClientSide
        && event.getLevel().getBlockState(event.getPos()).getBlock().equals(Blocks.BELL)) {

      AABB aabb = (new AABB(event.getPos())).inflate(48.0D);
      List<RealPerson> nearbyPeople = event.getLevel().getEntitiesOfClass(RealPerson.class, aabb);

      for (RealPerson person : nearbyPeople) {
        if (person.isAlive() && !person.isRemoved()) {
          person.goToBed(0.7D);
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
    }
  }

}
