package com.quzzar.villagelife.entities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.networking.GuardOpenInventoryPacket;
import com.quzzar.villagelife.networking.VillagelifePacketHandler;
import com.quzzar.villagelife.other.PersonLootTables;
import com.quzzar.villagelife.other.VillagelifeItems;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.TimeUtil;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerListener;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.network.PacketDistributor;

public class Person extends PathfinderMob implements CrossbowAttackMob, NeutralMob, ContainerListener {

  private static final UUID MODIFIER_UUID = UUID.fromString("5CD17E52-A79A-43D3-A529-90FDE04B181E");
  private static final AttributeModifier USE_ITEM_SPEED_PENALTY = new AttributeModifier(MODIFIER_UUID,
      "Use item speed penalty", -0.25D, AttributeModifier.Operation.ADDITION);

  private static final EntityDataAccessor<Optional<BlockPos>> GUARD_POS = SynchedEntityData.defineId(Person.class,
      EntityDataSerializers.OPTIONAL_BLOCK_POS);
  private static final EntityDataAccessor<Boolean> PATROLLING = SynchedEntityData.defineId(Person.class,
      EntityDataSerializers.BOOLEAN);
  private static final EntityDataAccessor<Integer> GUARD_VARIANT = SynchedEntityData.defineId(Person.class,
      EntityDataSerializers.INT);
  private static final EntityDataAccessor<Boolean> RUNNING_TO_EAT = SynchedEntityData.defineId(Person.class,
      EntityDataSerializers.BOOLEAN);
  private static final EntityDataAccessor<Boolean> INTERRUPTED = SynchedEntityData.defineId(Person.class,
      EntityDataSerializers.BOOLEAN);
  private static final EntityDataAccessor<Integer> DAYS_SINCE_SLEEP = SynchedEntityData.defineId(Person.class,
      EntityDataSerializers.INT);
  private static final EntityDataAccessor<Boolean> DATA_CHARGING_STATE = SynchedEntityData.defineId(Person.class,
      EntityDataSerializers.BOOLEAN);
  private static final EntityDataAccessor<Boolean> EATING = SynchedEntityData.defineId(Person.class,
      EntityDataSerializers.BOOLEAN);
  private static final EntityDataAccessor<Boolean> FOLLOWING = SynchedEntityData.defineId(Person.class,
      EntityDataSerializers.BOOLEAN);
  protected static final EntityDataAccessor<Optional<UUID>> FOLLOW_LEADER_UUID = SynchedEntityData.defineId(
      Person.class,
      EntityDataSerializers.OPTIONAL_UUID);

  private static final Map<Pose, EntityDimensions> SIZE_BY_POSE = ImmutableMap.<Pose, EntityDimensions>builder()
      .put(Pose.STANDING, EntityDimensions.scalable(0.6F, 1.95F)).put(Pose.SLEEPING, SLEEPING_DIMENSIONS)
      .put(Pose.FALL_FLYING, EntityDimensions.scalable(0.6F, 0.6F))
      .put(Pose.SWIMMING, EntityDimensions.scalable(0.6F, 0.6F))
      .put(Pose.SPIN_ATTACK, EntityDimensions.scalable(0.6F, 0.6F))
      .put(Pose.CROUCHING, EntityDimensions.scalable(0.6F, 1.75F))
      .put(Pose.DYING, EntityDimensions.fixed(0.2F, 0.2F)).build();

  public SimpleContainer personEquipInv = new SimpleContainer(6);
  public SimpleContainer personMainInv = new SimpleContainer(9 * 4);

  public int shieldCoolDown;
  private boolean guiOpen;
  private boolean immobile;

  private int remainingPersistentAngerTime;
  protected UniformInt angerTime;
  private UUID persistentAngerTarget;

  private static final Map<EquipmentSlot, ResourceLocation> EQUIPMENT_SLOT_ITEMS = Util.make(Maps.newHashMap(),
      (slotItems) -> {
        slotItems.put(EquipmentSlot.MAINHAND, PersonLootTables.GUARD_MAIN_HAND);
        slotItems.put(EquipmentSlot.OFFHAND, PersonLootTables.GUARD_OFF_HAND);
        slotItems.put(EquipmentSlot.HEAD, PersonLootTables.GUARD_HELMET);
        slotItems.put(EquipmentSlot.CHEST, PersonLootTables.GUARD_CHEST);
        slotItems.put(EquipmentSlot.LEGS, PersonLootTables.GUARD_LEGGINGS);
        slotItems.put(EquipmentSlot.FEET, PersonLootTables.GUARD_FEET);
      });

  public Person(EntityType<? extends Person> type, Level world) {
    super(type, world);

    this.personMainInv.addListener(this);
    this.personEquipInv.addListener(this);

    this.itemHandler = net.minecraftforge.common.util.LazyOptional
        .of(() -> new net.minecraftforge.items.wrapper.InvWrapper(this.personEquipInv));

    this.setPersistenceRequired();

    ((GroundPathNavigation) this.getNavigation()).setCanOpenDoors(true);
  }

  @Override
  public SpawnGroupData finalizeSpawn(ServerLevelAccessor worldIn, DifficultyInstance difficultyIn,
      MobSpawnType reason, @Nullable SpawnGroupData spawnDataIn, @Nullable CompoundTag dataTag) {
    this.setPersistenceRequired();
    // this.populateDefaultEquipmentSlots(difficultyIn);
    return super.finalizeSpawn(worldIn, difficultyIn, reason, spawnDataIn, dataTag);
  }

  @Override
  protected void doPush(Entity entityIn) {
    if (entityIn instanceof PathfinderMob) {
      PathfinderMob living = (PathfinderMob) entityIn;
      boolean attackTargets = living.getTarget() instanceof Villager || living.getTarget() instanceof IronGolem
          || living.getTarget() instanceof Person;
      if (attackTargets)
        this.setTarget(living);
    }
    super.doPush(entityIn);
  }

  @Nullable
  public void setPatrolPos(BlockPos position) {
    this.entityData.set(GUARD_POS, Optional.ofNullable(position));
  }

  @Nullable
  public BlockPos getPatrolPos() {
    return this.entityData.get(GUARD_POS).orElse((BlockPos) null);
  }

  @Override
  protected SoundEvent getAmbientSound() {
    return null;
  }

  @Override
  protected SoundEvent getHurtSound(DamageSource damageSourceIn) {
    if (this.isBlocking()) {
      return SoundEvents.SHIELD_BLOCK;
    } else {
      return SoundEvents.PLAYER_HURT;
    }
  }

  @Override
  protected SoundEvent getDeathSound() {
    return SoundEvents.PLAYER_DEATH;
  }

  public static int slotToInventoryIndex(EquipmentSlot slot) {
    switch (slot) {
      case CHEST:
        return 1;
      case FEET:
        return 3;
      case HEAD:
        return 0;
      case LEGS:
        return 2;
      default:
        break;
    }
    return 0;
  }

  @Override
  protected void dropCustomDeathLoot(DamageSource source, int looting, boolean recentlyHitIn) {
    for (int i = 0; i < this.personEquipInv.getContainerSize(); ++i) {
      ItemStack itemstack = this.personEquipInv.getItem(i);
      if (!itemstack.isEmpty() && !EnchantmentHelper.hasVanishingCurse(itemstack))
        this.spawnAtLocation(itemstack);
    }

    for (int i = 0; i < this.personMainInv.getContainerSize(); ++i) {
      ItemStack itemstack = this.personMainInv.getItem(i);
      if (!itemstack.isEmpty() && !EnchantmentHelper.hasVanishingCurse(itemstack))
        this.spawnAtLocation(itemstack);
    }
  }

  @Override
  public void readAdditionalSaveData(CompoundTag compound) {
    super.readAdditionalSaveData(compound);
    UUID uuid = compound.hasUUID("Owner") ? compound.getUUID("Owner") : null;
    if (uuid != null) {
      try {
        this.setFollowLeaderUUID(uuid);
      } catch (Throwable throwable) {
        this.setFollowLeaderUUID(null);
      }
    }
    this.setFollowing(compound.getBoolean("Following"));
    this.guiOpen = compound.getBoolean("GuiOpen");
    this.immobile = compound.getBoolean("Immobile");
    this.setEating(compound.getBoolean("Eating"));
    this.setPatrolling(compound.getBoolean("Patrolling"));
    this.setRunningToEat(compound.getBoolean("RunningToEat"));
    this.setInterrupted(compound.getBoolean("Interrupted"));
    this.shieldCoolDown = compound.getInt("ShieldCooldown");
    this.setDaysSinceSleep(compound.getInt("DaysSinceSleep"));
    if (compound.contains("PatrolPosX")) {
      int x = compound.getInt("PatrolPosX");
      int y = compound.getInt("PatrolPosY");
      int z = compound.getInt("PatrolPosZ");
      this.entityData.set(GUARD_POS, Optional.ofNullable(new BlockPos(x, y, z)));
    }

    ListTag listnbt = compound.getList("Inventory", 10);
    for (int i = 0; i < listnbt.size(); ++i) {
      CompoundTag compoundnbt = listnbt.getCompound(i);
      int j = compoundnbt.getByte("Slot") & 255;
      this.personEquipInv.setItem(j, ItemStack.of(compoundnbt));
    }
    if (compound.contains("ArmorItems", 9)) {
      ListTag armorItems = compound.getList("ArmorItems", 10);
      for (int i = 0; i < 4/* this.armor.size() */; ++i) {
        int index = Person
            .slotToInventoryIndex(Mob.getEquipmentSlotForItem(ItemStack.of(armorItems.getCompound(i))));
        this.personEquipInv.setItem(index, ItemStack.of(armorItems.getCompound(i)));
      }
    }
    if (compound.contains("HandItems", 9)) {
      ListTag handItems = compound.getList("HandItems", 10);
      for (int i = 0; i < 2/* this.handItems.size() */; ++i) {
        int handSlot = i == 0 ? 5 : 4;
        this.personEquipInv.setItem(handSlot, ItemStack.of(handItems.getCompound(i)));
      }
    }

    ListTag mainlistnbt = compound.getList("MainInventory", 10);
    for (int i = 0; i < mainlistnbt.size(); ++i) {
      CompoundTag compoundnbt = mainlistnbt.getCompound(i);
      int j = compoundnbt.getByte("Slot") & 255;
      this.personMainInv.setItem(j, ItemStack.of(compoundnbt));
    }

    if (!level.isClientSide) {
      this.readPersistentAngerSaveData((ServerLevel) this.level, compound);
    }
    this.stopSleeping();
  }

  @Override
  public void addAdditionalSaveData(CompoundTag compound) {
    super.addAdditionalSaveData(compound);
    compound.putInt("Type", this.getGuardVariant());
    compound.putInt("ShieldCooldown", this.shieldCoolDown);
    compound.putBoolean("Following", this.isFollowing());
    compound.putBoolean("GuiOpen", this.guiOpen);
    compound.putBoolean("Immobile", this.immobile);
    compound.putBoolean("Eating", this.isEating());
    compound.putBoolean("Patrolling", this.isPatrolling());
    compound.putBoolean("RunningToEat", this.isRunningToEat());
    compound.putBoolean("Interrupted", this.isInterrupted());
    compound.putInt("DaysSinceSleep", this.getDaysSinceSleep());
    if (this.getFollowLeaderUUID() != null) {
      compound.putUUID("FollowLeader", this.getFollowLeaderUUID());
    }

    ListTag listnbt = new ListTag();
    for (int i = 0; i < this.personEquipInv.getContainerSize(); ++i) {
      ItemStack itemstack = this.personEquipInv.getItem(i);
      if (!itemstack.isEmpty()) {
        CompoundTag compoundnbt = new CompoundTag();
        compoundnbt.putByte("Slot", (byte) i);
        itemstack.save(compoundnbt);
        listnbt.add(compoundnbt);
      }
    }
    compound.put("Inventory", listnbt);

    ListTag mainlistnbt = new ListTag();
    for (int i = 0; i < this.personMainInv.getContainerSize(); ++i) {
      ItemStack itemstack = this.personMainInv.getItem(i);
      if (!itemstack.isEmpty()) {
        CompoundTag compoundnbt = new CompoundTag();
        compoundnbt.putByte("Slot", (byte) i);
        itemstack.save(compoundnbt);
        mainlistnbt.add(compoundnbt);
      }
    }
    compound.put("MainInventory", mainlistnbt);

    if (this.getPatrolPos() != null) {
      compound.putInt("PatrolPosX", this.getPatrolPos().getX());
      compound.putInt("PatrolPosY", this.getPatrolPos().getY());
      compound.putInt("PatrolPosZ", this.getPatrolPos().getZ());
    }
    this.addPersistentAngerSaveData(compound);
  }

  @Nullable
  public LivingEntity getFollowLeader() {
    try {
      UUID uuid = this.getFollowLeaderUUID();
      return (uuid == null || uuid != null && this.level.getPlayerByUUID(uuid) != null) ? null
          : this.level.getPlayerByUUID(uuid);
    } catch (IllegalArgumentException illegalargumentexception) {
      return null;
    }
  }

  public boolean isFollowLeader(LivingEntity entityIn) {
    return entityIn == this.getFollowLeader();
  }

  @Nullable
  public UUID getFollowLeaderUUID() {
    return this.entityData.get(FOLLOW_LEADER_UUID).orElse(null);
  }

  public void setFollowLeaderUUID(@Nullable UUID leaderUUID) {
    this.entityData.set(FOLLOW_LEADER_UUID, Optional.ofNullable(leaderUUID));
  }

  @Override
  public boolean doHurtTarget(Entity entityIn) {
    ItemStack hand = this.getMainHandItem();
    hand.hurtAndBreak(1, this, (entity) -> entity.broadcastBreakEvent(EquipmentSlot.MAINHAND));
    return super.doHurtTarget(entityIn);
  }

  @Override
  public boolean isInvulnerable() {
    return super.isInvulnerable() || this.isSleeping();
  }

  @Override
  public boolean isImmobile() {
    return hasGuiOpen()
        || this.immobile
        || super.isImmobile()
        || (this.isSleeping() && this.getLevel().isNight());
  }

  @Override
  public void die(DamageSource source) {
    /*
     * if ((this.level.getDifficulty() == Difficulty.NORMAL ||
     * this.level.getDifficulty() == Difficulty.HARD)
     * && source.getEntity() instanceof Zombie) {
     * if (this.level.getDifficulty() != Difficulty.HARD &&
     * this.random.nextBoolean()) {
     * return;
     * }
     * ZombieVillager zombieguard = this.convertTo(EntityType.ZOMBIE_VILLAGER,
     * true);
     * zombieguard.finalizeSpawn((ServerLevelAccessor) this.level,
     * this.level.getCurrentDifficultyAt(zombieguard.blockPosition()),
     * MobSpawnType.CONVERSION,
     * new Zombie.ZombieGroupData(false, true), (CompoundTag) null);
     * if (!this.isSilent())
     * this.level.levelEvent((Player) null, 1026, this.blockPosition(), 0);
     * this.discard();
     * }
     */
    super.die(source);
  }

  @Override
  protected void completeUsingItem() {
    InteractionHand interactionhand = this.getUsedItemHand();
    if (!this.useItem.equals(this.getItemInHand(interactionhand))) {
      this.releaseUsingItem();
    } else {
      if (!this.useItem.isEmpty() && this.isUsingItem()) {
        this.triggerItemUseEffects(this.useItem, 16);
        ItemStack copy = this.useItem.copy();
        ItemStack itemstack = net.minecraftforge.event.ForgeEventFactory.onItemUseFinish(this, copy,
            getUseItemRemainingTicks(), this.useItem.finishUsingItem(this.level, this));
        if (itemstack != this.useItem) {
          this.setItemInHand(interactionhand, itemstack);
        }
        if (!this.useItem.isEdible())
          this.useItem.shrink(1);
        this.stopUsingItem();
      }
    }
  }

  @Override
  public ItemStack eat(Level world, ItemStack stack) {
    if (stack.isEdible()) {
      this.heal(stack.getItem().getFoodProperties().getNutrition() * 2);
    }
    super.eat(world, stack);
    world.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.PLAYER_BURP, SoundSource.PLAYERS, 0.5F,
        world.random.nextFloat() * 0.1F + 0.9F);
    this.setEating(false);
    return stack;
  }

  @Override
  public void aiStep() {
    if (this.shieldCoolDown > 0) {
      --this.shieldCoolDown;
    }
    if (this.getHealth() < this.getMaxHealth() && this.tickCount % 200 == 0) {
      this.heal(2);
    }
    if (this.isInterrupted() && this.tickCount % 200 == 0) {
      this.setInterrupted(false);
    }
    if (this.isEating() && this.tickCount % 20 == 0) {
      this.eat(this.level, this.getOffhandItem());
    }
    if (!this.level.isClientSide) {
      this.updatePersistentAnger((ServerLevel) this.level, true);
    }
    this.updateSwingTime();
    super.aiStep();
  }

  @Override
  public EntityDimensions getDimensions(Pose poseIn) {
    return SIZE_BY_POSE.getOrDefault(poseIn, EntityDimensions.scalable(0.6F, 1.95F));
  }

  @Override
  public float getStandingEyeHeight(Pose poseIn, EntityDimensions sizeIn) {
    if (poseIn == Pose.CROUCHING) {
      return 1.40F;
    }
    return super.getStandingEyeHeight(poseIn, sizeIn);
  }

  @Override
  protected void blockUsingShield(LivingEntity entityIn) {
    super.blockUsingShield(entityIn);
    if (entityIn.getMainHandItem().canDisableShield(this.useItem, this, entityIn))
      this.disableShield(true);
  }

  @Override
  protected void hurtCurrentlyUsedShield(float damage) {
    if (this.useItem.canPerformAction(net.minecraftforge.common.ToolActions.SHIELD_BLOCK)) {
      if (damage >= 3.0F) {
        int i = 1 + Mth.floor(damage);
        InteractionHand hand = this.getUsedItemHand();
        this.useItem.hurtAndBreak(i, this, (entity) -> entity.broadcastBreakEvent(hand));
        if (this.useItem.isEmpty()) {
          if (hand == InteractionHand.MAIN_HAND) {
            this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
          } else {
            this.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
          }
          this.useItem = ItemStack.EMPTY;
          this.playSound(SoundEvents.SHIELD_BREAK, 0.8F, 0.8F + this.level.random.nextFloat() * 0.4F);
        }
      }
    }
  }

  @Override
  public void startUsingItem(InteractionHand hand) {
    ItemStack itemstack = this.getItemInHand(hand);
    if (itemstack.canPerformAction(net.minecraftforge.common.ToolActions.SHIELD_BLOCK)) {
      AttributeInstance modifiableattributeinstance = this.getAttribute(Attributes.MOVEMENT_SPEED);
      modifiableattributeinstance.removeModifier(USE_ITEM_SPEED_PENALTY);
      modifiableattributeinstance.addTransientModifier(USE_ITEM_SPEED_PENALTY);
    }
    super.startUsingItem(hand);
  }

  @Override
  public void stopUsingItem() {
    if (this.getAttribute(Attributes.MOVEMENT_SPEED).hasModifier(USE_ITEM_SPEED_PENALTY))
      this.getAttribute(Attributes.MOVEMENT_SPEED).removeModifier(USE_ITEM_SPEED_PENALTY);
    super.stopUsingItem();
  }

  public void disableShield(boolean increase) {
    float chance = 0.25F + (float) EnchantmentHelper.getBlockEfficiency(this) * 0.05F;
    if (increase)
      chance += 0.75;
    if (this.random.nextFloat() < chance) {
      this.shieldCoolDown = 100;
      this.stopUsingItem();
      this.level.broadcastEntityEvent(this, (byte) 30);
    }
  }

  @Override
  protected void defineSynchedData() {
    super.defineSynchedData();
    this.entityData.define(GUARD_VARIANT, 0);
    this.entityData.define(DATA_CHARGING_STATE, false);
    this.entityData.define(FOLLOW_LEADER_UUID, Optional.empty());
    this.entityData.define(EATING, false);
    this.entityData.define(FOLLOWING, false);
    this.entityData.define(GUARD_POS, Optional.empty());
    this.entityData.define(PATROLLING, false);
    this.entityData.define(RUNNING_TO_EAT, false);
    this.entityData.define(INTERRUPTED, false);
    this.entityData.define(DAYS_SINCE_SLEEP, 0);
  }

  public boolean isCharging() {
    return this.entityData.get(DATA_CHARGING_STATE);
  }

  public void setChargingCrossbow(boolean charging) {
    this.entityData.set(DATA_CHARGING_STATE, charging);
  }

  @Override
  protected void populateDefaultEquipmentSlots(DifficultyInstance difficulty) {
    for (EquipmentSlot equipmentslottype : EquipmentSlot.values()) {
      for (ItemStack stack : this.getItemsFromLootTable(equipmentslottype)) {
        this.setItemSlot(equipmentslottype, stack);
      }
    }
    this.handDropChances[EquipmentSlot.MAINHAND.getIndex()] = 100.0F;
    this.handDropChances[EquipmentSlot.OFFHAND.getIndex()] = 100.0F;
  }

  public List<ItemStack> getItemsFromLootTable(EquipmentSlot slot) {
    if (EQUIPMENT_SLOT_ITEMS.containsKey(slot)) {
      LootTable loot = this.level.getServer().getLootTables().get(EQUIPMENT_SLOT_ITEMS.get(slot));
      LootContext.Builder lootcontext$builder = (new LootContext.Builder((ServerLevel) this.level))
          .withParameter(LootContextParams.THIS_ENTITY, this).withRandom(this.getRandom());
      return loot.getRandomItems(lootcontext$builder.create(PersonLootTables.SLOT));
    }
    return null;
  }

  public int getGuardVariant() {
    return this.entityData.get(GUARD_VARIANT);
  }

  public ItemStack getPickedResult(HitResult target) {
    return new ItemStack(VillagelifeItems.PERSON_SPAWN_EGG.get());
  }

  @Override
  public boolean canBeLeashed(Player player) {
    return true; // TODO, change back to false
  }

  @Override
  public boolean canPickUpLoot() {
    return true;
  }

  @Override
  protected void pickUpItem(ItemEntity itemEntity) {
    HopperBlockEntity.addItem(this.personMainInv, itemEntity);
  }

  @Override
  public void setItemSlot(EquipmentSlot slotIn, ItemStack stack) {
    super.setItemSlot(slotIn, stack);

    ArrayList<ItemStack> armorItems = Lists.newArrayList(this.getArmorSlots());

    switch (slotIn) {
      case CHEST:
        this.personEquipInv.setItem(1, armorItems.get(slotIn.getIndex()));
        break;
      case FEET:
        this.personEquipInv.setItem(3, armorItems.get(slotIn.getIndex()));
        break;
      case HEAD:
        this.personEquipInv.setItem(0, armorItems.get(slotIn.getIndex()));
        break;
      case LEGS:
        this.personEquipInv.setItem(2, armorItems.get(slotIn.getIndex()));
        break;
      case MAINHAND:
        this.personEquipInv.setItem(5, this.getMainHandItem());
        break;
      case OFFHAND:
        this.personEquipInv.setItem(4, this.getOffhandItem());
        break;
    }
  }

  public void addItems(List<ItemStack> items) {
    Utils.insertItems(this.personMainInv, items, this);
  }

  public ItemStack removeItem(Item item, int amount) {
    if (!this.hasItem(item)) {
      return new ItemStack(item, 0);
    }
    return Utils.removeItem(this.personMainInv, item, amount);
  }

  public boolean hasItem(Item item) {
    return this.personMainInv.hasAnyOf(ImmutableSet.of(item));
  }

  public List<ItemStack> clearMainInventory() {
    return this.personMainInv.removeAllItems();
  }

  public boolean isInventoryFull() {
    return Utils.isFullContainer(this.personMainInv);
  }

  public boolean isFollowing() {
    return this.entityData.get(FOLLOWING);
  }

  public void setFollowing(boolean following) {
    this.entityData.set(FOLLOWING, following);
  }

  @Override
  public boolean canAttack(LivingEntity target) {
    return !this.isFollowLeader(target)
        && !(target instanceof Person)
        && super.canAttack(target);
  }

  @Override
  public void rideTick() {
    super.rideTick();
    if (this.getVehicle() instanceof PathfinderMob) {
      PathfinderMob creatureentity = (PathfinderMob) this.getVehicle();
      this.yBodyRot = creatureentity.yBodyRot;
    }
  }

  @Override
  public void setTarget(LivingEntity entity) {
    if (entity instanceof Person || this.isRunningToEat())
      return;
    super.setTarget(entity);
  }

  @Override
  protected InteractionResult mobInteract(Player player, InteractionHand hand) {
    boolean inventoryRequirements = !player.isSecondaryUseActive() && this.onGround;
    if (inventoryRequirements) {
      if (this.getTarget() != player && this.isEffectiveAi()) {
        if (player instanceof ServerPlayer) {
          this.openGui((ServerPlayer) player);
          return InteractionResult.SUCCESS;
        }
      }
      return InteractionResult.CONSUME;
    }
    return super.mobInteract(player, hand);
  }

  @Override
  public void containerChanged(Container invBasic) {
  }

  @Override
  protected void hurtArmor(DamageSource damageSource, float damage) {
    if (damage >= 0.0F) {
      damage = damage / 4.0F;
      if (damage < 1.0F) {
        damage = 1.0F;
      }
      for (int i = 0; i < this.personEquipInv.getContainerSize(); ++i) {
        ItemStack itemstack = this.personEquipInv.getItem(i);
        if ((!damageSource.isFire() || !itemstack.getItem().isFireResistant())
            && itemstack.getItem() instanceof ArmorItem) {
          int j = i;
          itemstack.hurtAndBreak((int) damage, this, (p_214023_1_) -> {
            p_214023_1_.broadcastBreakEvent(EquipmentSlot.byTypeAndIndex(EquipmentSlot.Type.ARMOR, j));
          });
        }
      }
    }
  }

  public void openGui(ServerPlayer player) {
    if (player.containerMenu != player.inventoryMenu) {
      player.closeContainer();
    }
    setGuiOpen(true);

    // TODO, remove
    Villagelife.LOGGER.info(this.personMainInv.toString());
    Villagelife.LOGGER.info(this.personEquipInv.toString());

    player.nextContainerCounter();
    VillagelifePacketHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new GuardOpenInventoryPacket(
        player.containerCounter, this.personEquipInv.getContainerSize(), this.getId()));
    player.containerMenu = new PersonContainer(player.containerCounter, player.getInventory(), this.personEquipInv,
        this);
    player.initMenu(player.containerMenu);
    MinecraftForge.EVENT_BUS.post(new PlayerContainerEvent.Open(player, player.containerMenu));
  }

  public static AttributeSupplier.Builder createAttributes() {
    return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 40.0D).add(Attributes.MOVEMENT_SPEED, 0.5D)
        .add(Attributes.ATTACK_DAMAGE, 1.0D).add(Attributes.FOLLOW_RANGE, 20.0D);
  }

  private net.minecraftforge.common.util.LazyOptional<?> itemHandler;

  @Override
  public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(
      net.minecraftforge.common.capabilities.Capability<T> capability,
      @Nullable net.minecraft.core.Direction facing) {
    if (this.isAlive() && capability == net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY
        && itemHandler != null)
      return itemHandler.cast();
    return super.getCapability(capability, facing);
  }

  public boolean isEating() {
    return this.entityData.get(EATING);
  }

  public void setEating(boolean eating) {
    this.entityData.set(EATING, eating);
  }

  public boolean isPatrolling() {
    return this.entityData.get(PATROLLING);
  }

  public void setPatrolling(boolean patrolling) {
    this.entityData.set(PATROLLING, patrolling);
  }

  public boolean isRunningToEat() {
    return this.entityData.get(RUNNING_TO_EAT);
  }

  public void setRunningToEat(boolean running) {
    this.entityData.set(RUNNING_TO_EAT, running);
  }

  public boolean isInterrupted() {
    return this.entityData.get(INTERRUPTED);
  }

  public void setInterrupted(boolean interrupted) {
    this.entityData.set(INTERRUPTED, interrupted);
  }

  public int getDaysSinceSleep() {
    return this.entityData.get(DAYS_SINCE_SLEEP);
  }

  public void setDaysSinceSleep(int days) {
    this.entityData.set(DAYS_SINCE_SLEEP, days);
  }

  public boolean hasGuiOpen() {
    return this.guiOpen;
  }

  public void setGuiOpen(boolean guiOpen) {
    this.guiOpen = guiOpen;
    // this.setCustomNameVisible(!guiOpen);
  }

  public void setImmobile(boolean immobile) {
    this.immobile = immobile;
  }

  @Override
  public UUID getPersistentAngerTarget() {
    return this.persistentAngerTarget;
  }

  @Override
  public int getRemainingPersistentAngerTime() {
    return this.remainingPersistentAngerTime;
  }

  @Override
  public void setPersistentAngerTarget(UUID arg0) {
    this.persistentAngerTarget = arg0;
  }

  @Override
  public void setRemainingPersistentAngerTime(int arg0) {
    this.remainingPersistentAngerTime = arg0;
  }

  @Override
  public void startPersistentAngerTimer() {
    this.setRemainingPersistentAngerTime(angerTime.sample(this.random));
  }

  @Override
  public void performRangedAttack(LivingEntity target, float distanceFactor) {
    this.shieldCoolDown = 8;
    if (this.getMainHandItem().getItem() instanceof CrossbowItem)
      this.performCrossbowAttack(this, 6.0F);
    if (this.getMainHandItem().getItem() instanceof BowItem) {
      ItemStack itemstack = this
          .getProjectile(this.getItemInHand(Utils.getHandWith(this, item -> item instanceof BowItem)));
      ItemStack hand = this.getMainHandItem();
      AbstractArrow abstractarrowentity = ProjectileUtil.getMobArrow(this, itemstack, distanceFactor);
      abstractarrowentity = ((net.minecraft.world.item.BowItem) this.getMainHandItem().getItem())
          .customArrow(abstractarrowentity);
      int powerLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.POWER_ARROWS, itemstack);
      if (powerLevel > 0)
        abstractarrowentity
            .setBaseDamage(abstractarrowentity.getBaseDamage() + (double) powerLevel * 0.5D + 0.5D);
      int punchLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.PUNCH_ARROWS, itemstack);
      if (punchLevel > 0)
        abstractarrowentity.setKnockback(punchLevel);
      if (EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FLAMING_ARROWS, itemstack) > 0)
        abstractarrowentity.setSecondsOnFire(100);
      double d0 = target.getX() - this.getX();
      double d1 = target.getY(0.3333333333333333D) - abstractarrowentity.getY();
      double d2 = target.getZ() - this.getZ();
      double d3 = Mth.sqrt((float) (d0 * d0 + d2 * d2));
      abstractarrowentity.shoot(d0, d1 + d3 * (double) 0.2F, d2, 1.6F,
          (float) (14 - this.level.getDifficulty().getId() * 4));
      this.playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
      this.level.addFreshEntity(abstractarrowentity);
      hand.hurtAndBreak(1, this, (entity) -> entity.broadcastBreakEvent(EquipmentSlot.MAINHAND));
    }
  }

  @Override
  public ItemStack getProjectile(ItemStack shootable) {
    if (shootable.getItem() instanceof ProjectileWeaponItem) {
      Predicate<ItemStack> predicate = ((ProjectileWeaponItem) shootable.getItem()).getSupportedHeldProjectiles();
      ItemStack itemstack = ProjectileWeaponItem.getHeldProjectile(this, predicate);
      return itemstack.isEmpty() ? new ItemStack(Items.ARROW) : itemstack;
    } else {
      return ItemStack.EMPTY;
    }
  }

  @Override
  public void shootCrossbowProjectile(LivingEntity arg0, ItemStack arg1, Projectile arg2, float arg3) {
    this.shootCrossbowProjectile(this, arg0, arg2, arg3, 1.6F);
  }

  @Override
  public void onCrossbowAttackPerformed() {
    this.noActionTime = 0;
  }

}