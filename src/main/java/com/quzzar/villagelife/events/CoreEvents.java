package com.quzzar.villagelife.events;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.quzzar.villagelife.PersonEntityType;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.entities.Person;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.other.YearManager;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.Occupation;
import com.quzzar.villagelife.village.VillageManager;
import com.quzzar.villagelife.village.bookkeeping.DeathBkEvent;
import com.quzzar.villagelife.village.bookkeeping.HurtByPlayerBkEvent;
import com.quzzar.villagelife.village.buildings.Building;
import com.quzzar.villagelife.village.buildings.BuildingInfo;
import com.quzzar.villagelife.village.buildings.Buildings;
import com.quzzar.villagelife.village.buildings.InstantBuildStructure;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.jfr.event.ChunkGenerationEvent;
import net.minecraft.util.profiling.jfr.event.WorldLoadFinishedEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.feature.StructureFeature;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingSetAttackTargetEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.Event.Result;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Villagelife.MODID)
public class CoreEvents {

  /*
   * @SubscribeEvent
   * public static void onEntityTarget(LivingSetAttackTargetEvent event) {
   * LivingEntity entity = (LivingEntity) event.getEntity();
   * LivingEntity target = event.getTarget();
   * if (target == null || entity.getType() == PersonEntityType.PERSON.get())
   * return;
   * boolean isVillager = target.getType() == EntityType.VILLAGER ||
   * target.getType() == PersonEntityType.PERSON.get();
   * if (isVillager) {
   * List<Mob> list = entity.level.getEntitiesOfClass(Mob.class,
   * entity.getBoundingBox()
   * .inflate(VillagelifeConfig.GuardVillagerHelpRange, 5.0D,
   * VillagelifeConfig.GuardVillagerHelpRange));
   * for (Mob mob : list) {
   * if ((mob.getType() == PersonEntityType.PERSON.get() || mob.getType() ==
   * EntityType.IRON_GOLEM)
   * && mob.getTarget() == null) {
   * mob.setTarget(entity);
   * }
   * }
   * }
   * 
   * if (entity instanceof IronGolem golem && target instanceof Person)
   * golem.setTarget(null);
   * }
   * 
   * @SubscribeEvent
   * public static void onEntityHurt(LivingHurtEvent event) {
   * LivingEntity entity = (LivingEntity) event.getEntity();
   * Entity trueSource = (Entity) event.getSource().getEntity();
   * if (entity == null || trueSource == null)
   * return;
   * boolean isVillager = entity.getType() == EntityType.VILLAGER ||
   * entity.getType() == PersonEntityType.PERSON.get();
   * boolean isGolem = isVillager || entity.getType() == EntityType.IRON_GOLEM;
   * if (isVillager && event.getSource().getEntity() instanceof Mob) {
   * List<Mob> list = trueSource.level.getEntitiesOfClass(Mob.class,
   * trueSource.getBoundingBox()
   * .inflate(VillagelifeConfig.GuardVillagerHelpRange, 5.0D,
   * VillagelifeConfig.GuardVillagerHelpRange));
   * for (Mob mob : list) {
   * boolean type = mob.getType() == PersonEntityType.PERSON.get() ||
   * mob.getType() == EntityType.IRON_GOLEM;
   * boolean trueSourceGolem = trueSource.getType() ==
   * PersonEntityType.PERSON.get()
   * || trueSource.getType() == EntityType.IRON_GOLEM;
   * if (!trueSourceGolem && type && mob.getTarget() == null)
   * mob.setTarget((Mob) event.getSource().getEntity());
   * }
   * }
   * }
   */

  @SubscribeEvent
  public static void onLivingSpawned(EntityJoinWorldEvent event) {
    if (event.getEntity() instanceof Enemy
        && !(event.getEntity() instanceof EnderMan)
        && !(event.getEntity() instanceof Creeper)) {
      Mob mob = (Mob) event.getEntity();
      mob.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(mob, Person.class, false));
    }
  }

  @SubscribeEvent
  public static void onLivingDamaged(LivingDamageEvent event) {

    if (event.getEntity().level.isClientSide()) {
      return;
    }

    if (event.getEntityLiving() instanceof RealPerson) {

      RealPerson person = (RealPerson) event.getEntityLiving();

      if (event.getSource() == DamageSource.CRAMMING
          || event.getSource() == DamageSource.DROWN
          || event.getSource() == DamageSource.FREEZE
          || event.getSource() == DamageSource.IN_WALL
          || event.getSource() == DamageSource.LAVA
          || event.getSource() == DamageSource.OUT_OF_WORLD) {

        person.tpToHome();

      }

    }

  }

  @SubscribeEvent
  public static void onLivingHurt(LivingHurtEvent event) {

    if (event.getEntity().level.isClientSide()) {
      return;
    }

    if (event.getEntity() == null || event.getSource().getEntity() == null) {
      return;
    }

    if (event.getEntity() instanceof RealPerson) {

      RealPerson person = (RealPerson) event.getEntity();

      if (event.getSource().getEntity() instanceof Player) {
        UUID damagerUUID = ((Player) event.getSource().getEntity()).getUUID();
        person.getVillage().logEvent(
            new HurtByPlayerBkEvent(
                person.getUUID(),
                person.eyeBlockPosition().asLong(),
                person.getOccupation(),
                person.getMarriageStatus(),
                event.getSource().getMsgId(),
                damagerUUID));
      }

    }
  }

  @SubscribeEvent
  public static void onLivingDeath(LivingDeathEvent event) {

    if (event.getEntity().level.isClientSide()) {
      return;
    }

    if (event.getEntityLiving() instanceof RealPerson) {

      RealPerson person = (RealPerson) event.getEntityLiving();
      if (person.getVillage() != null) {

        UUID killerUUID = null;
        if (person.getKillCredit() instanceof Player) {
          killerUUID = ((Player) person.getKillCredit()).getUUID();
        }

        person.getVillage().logEvent(
            new DeathBkEvent(
                person.getUUID(),
                person.eyeBlockPosition().asLong(),
                person.getOccupation(),
                person.getMarriageStatus(),
                event.getSource().getMsgId(),
                killerUUID));

      }

    }

  }

  @SubscribeEvent
  public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {

    if (event.getWorld() instanceof ServerLevelAccessor) {

      if (event.getPlacedBlock().getBlock().equals(Blocks.DIAMOND_BLOCK)) {

        ServerLevelAccessor levelAccess = (ServerLevelAccessor) event.getWorld();

        VillageManager.registerVillage(levelAccess, event.getPos());

      }

      if (event.getPlacedBlock().getBlock().equals(Blocks.EMERALD_BLOCK)) {

        ///

        BuildingInfo BUILDING = Buildings.register(new BuildingInfo("well_1")
            .setMaterialCost(Arrays.asList(
                new ItemStack(Items.OAK_LOG, 4),
                new ItemStack(Items.OAK_PLANKS, 16))));

        ///

        ServerLevelAccessor levelAccess = (ServerLevelAccessor) event.getWorld();

        Random random = new Random();
        Building building = new Building(BUILDING.getName(), Rotation.getRandom(random));
        InstantBuildStructure buildingStruct = new InstantBuildStructure(building, random)
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
    if (!event.isCanceled() && !event.getWorld().isClientSide
        && event.getWorld().getBlockState(event.getPos()).getBlock().equals(Blocks.BELL)) {

      AABB aabb = (new AABB(event.getPos())).inflate(48.0D);
      List<RealPerson> nearbyPeople = event.getWorld().getEntitiesOfClass(RealPerson.class, aabb);

      for (RealPerson person : nearbyPeople) {
        if (person.isAlive() && !person.isRemoved()) {
          person.goToBed(0.7D);
        }
      }

    }
  }

  @SubscribeEvent
  public static void onWorldLoad(WorldEvent.Load event) {
    if (!event.getWorld().isClientSide()) {
      Villagelife.LOGGER.debug("WORLD LOADED HERE");
      ServerLevelAccessor levelAccessor = (ServerLevelAccessor) event.getWorld();
      if (levelAccessor.getLevel().dimension().equals(Level.OVERWORLD)) {
        VillageManager.init(levelAccessor.getLevel());
      }
    }
  }

  @SubscribeEvent
  public static void onWorldSave(WorldEvent.Save event) {
    if (!event.getWorld().isClientSide()) {
      ServerLevelAccessor levelAccessor = (ServerLevelAccessor) event.getWorld();
      if (levelAccessor.getLevel().dimension().equals(Level.OVERWORLD)) {
        VillageManager.saveVillages();
      }
    }
  }

  private static long prevGameTime = 0;
  @SubscribeEvent
  public static void onServerWorldTick(TickEvent.WorldTickEvent event) {
    if (event.world.isClientSide) {
      return;
    }

    // Every 1 second
    if (event.world.getGameTime() % 20 == 0 && event.world.getGameTime() != prevGameTime) {

      YearManager.update(event.world.getGameTime());
      VillageManager.callUpdate();

      prevGameTime = event.world.getGameTime();
    }
  }

}
