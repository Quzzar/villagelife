package com.quzzar.villagelife.entities;

import java.util.ArrayList;
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
import com.quzzar.villagelife.compat.AccessoryCompat;
import com.quzzar.villagelife.entities.genetics.StatBlock;
import com.quzzar.villagelife.entities.genetics.StatProjection;
import com.quzzar.villagelife.other.PersonLootTables;
import com.quzzar.villagelife.other.VillagelifeItems;
import com.quzzar.villagelife.other.VillagelifeMenus;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerListener;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
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
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.EventHooks;

public class Person extends PathfinderMob implements CrossbowAttackMob, NeutralMob, ContainerListener {

  private static final ResourceLocation SPEED_MODIFIER_ID = ResourceLocation
      .fromNamespaceAndPath(Villagelife.MODID, "use_item_speed_penalty");
  private static final AttributeModifier USE_ITEM_SPEED_PENALTY = new AttributeModifier(SPEED_MODIFIER_ID,
      -0.25D, AttributeModifier.Operation.ADD_VALUE);

  /**
   * Skins are gender-specific pools of content-hash-named textures, listed per gender in
   * {@link PersonSkins}. A villager draws from the pool matching its gender, so a man
   * never wears a dress nor a woman a beard. The pools and their {@code <hash>.png} files
   * are regenerated together by {@code scratchpad/build_skins.py}.
   */
  public static int skinCountFor(Gender gender) {
    return PersonSkins.forGender(gender).size();
  }

  /**
   * The skin variant is rolled at spawn BEFORE the gender is known (finalizeSpawn sets
   * the skin; the identity roll sets gender afterward), so it is stored as a wide
   * gender-agnostic index and the renderer maps it into the villager's own-gender pool
   * as {@code index % poolSize}. A large prime range keeps that modulo close to even.
   */
  public static final int SKIN_INDEX_RANGE = 100_003;

  private static final EntityDataAccessor<Integer> SKIN_VARIANT = SynchedEntityData.defineId(Person.class,
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

  private static final Map<Pose, EntityDimensions> SIZE_BY_POSE = ImmutableMap.<Pose, EntityDimensions>builder()
      .put(Pose.STANDING, EntityDimensions.scalable(0.6F, 1.95F).withEyeHeight(1.62F))
      .put(Pose.SLEEPING, SLEEPING_DIMENSIONS)
      .put(Pose.FALL_FLYING, EntityDimensions.scalable(0.6F, 0.6F).withEyeHeight(0.4F))
      .put(Pose.SWIMMING, EntityDimensions.scalable(0.6F, 0.6F).withEyeHeight(0.4F))
      .put(Pose.SPIN_ATTACK, EntityDimensions.scalable(0.6F, 0.6F).withEyeHeight(0.4F))
      .put(Pose.CROUCHING, EntityDimensions.scalable(0.6F, 1.75F).withEyeHeight(1.40F))
      .put(Pose.DYING, EntityDimensions.fixed(0.2F, 0.2F).withEyeHeight(1.62F)).build();

  public SimpleContainer personEquipInv = new SimpleContainer(6);
  public SimpleContainer personMainInv = new SimpleContainer(9 * 4);

  public int shieldCoolDown;
  private boolean guiOpen;
  private boolean immobile;

  private int remainingPersistentAngerTime;
  protected UniformInt angerTime;
  private UUID persistentAngerTarget;

  private static final Map<EquipmentSlot, ResourceKey<LootTable>> EQUIPMENT_SLOT_ITEMS = Util.make(Maps.newHashMap(),
      (slotItems) -> {
        slotItems.put(EquipmentSlot.MAINHAND, PersonLootTables.GUARD_MAIN_HAND);
        slotItems.put(EquipmentSlot.OFFHAND, PersonLootTables.GUARD_OFF_HAND);
        slotItems.put(EquipmentSlot.HEAD, PersonLootTables.GUARD_HELMET);
        slotItems.put(EquipmentSlot.CHEST, PersonLootTables.GUARD_CHEST);
        slotItems.put(EquipmentSlot.LEGS, PersonLootTables.GUARD_LEGGINGS);
        slotItems.put(EquipmentSlot.FEET, PersonLootTables.GUARD_FEET);
      });

  /**
   * The person's rolled genetics. Rolled fresh in the constructor server-side
   * and replaced by the saved block in {@link #readAdditionalSaveData} when
   * loading an existing person; null on the client, which only sees the
   * resulting (synced) attribute values.
   */
  private StatBlock statBlock;

  public Person(EntityType<? extends Person> type, Level world) {
    super(type, world);

    this.personMainInv.addListener(this);
    this.personEquipInv.addListener(this);

    this.setPersistenceRequired();

    if (!world.isClientSide) {
      this.statBlock = StatBlock.roll(this.getRandom());
      StatProjection.apply(this, this.statBlock);
      this.setHealth(this.getMaxHealth());
    }

    ((GroundPathNavigation) this.getNavigation()).setCanOpenDoors(true);
    // Four times the pathfinder's node budget. A villager's follow range of 20
    // caps a search at 320 nodes, under half a vanilla villager's, and that ran
    // out on open ground before it found the way round a house to its door
    // (the level-3 house at Wildflower Downs, door away from the village: its
    // people stopped against the back wall). A search is made once per leg and
    // throttled by the navigation, so this costs per path, not per tick.
    this.getNavigation().setMaxVisitedNodesMultiplier(4.0F);
  }

  /**
   * Walking that plans through closed fence gates and up and down ladders
   * (PersonPathNavigation); opening a gate is a goal on RealPerson.
   */
  @Override
  protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
    return new com.quzzar.villagelife.entities.ai.PersonPathNavigation(this, level);
  }

  public StatBlock getStatBlock() {
    return statBlock;
  }

  @Override
  public SpawnGroupData finalizeSpawn(ServerLevelAccessor worldIn, DifficultyInstance difficultyIn,
      MobSpawnType reason, @Nullable SpawnGroupData spawnDataIn) {
    this.setPersistenceRequired();
    this.setSkinVariant(this.random.nextInt(SKIN_INDEX_RANGE));
    return super.finalizeSpawn(worldIn, difficultyIn, reason, spawnDataIn);
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
  protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHitIn) {
    for (int i = 0; i < this.personEquipInv.getContainerSize(); ++i) {
      ItemStack itemstack = this.personEquipInv.getItem(i);
      if (!itemstack.isEmpty() && !EnchantmentHelper.has(itemstack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP))
        this.spawnAtLocation(itemstack);
    }

    for (int i = 0; i < this.personMainInv.getContainerSize(); ++i) {
      ItemStack itemstack = this.personMainInv.getItem(i);
      if (!itemstack.isEmpty() && !EnchantmentHelper.has(itemstack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP))
        this.spawnAtLocation(itemstack);
    }
  }

  @Override
  public void readAdditionalSaveData(CompoundTag compound) {
    super.readAdditionalSaveData(compound);
    if (compound.contains("SkinVariant")) {
      this.setSkinVariant(compound.getInt("SkinVariant"));
    } else {
      // Saved before skins were rolled at all. Give them a face now rather than leaving
      // every pre-existing villager wearing skin 0.
      this.setSkinVariant(this.random.nextInt(SKIN_INDEX_RANGE));
    }
    this.guiOpen = compound.getBoolean("GuiOpen");
    this.immobile = compound.getBoolean("Immobile");
    this.setEating(compound.getBoolean("Eating"));
    this.setRunningToEat(compound.getBoolean("RunningToEat"));
    this.setInterrupted(compound.getBoolean("Interrupted"));
    this.shieldCoolDown = compound.getInt("ShieldCooldown");
    this.setDaysSinceSleep(compound.getInt("DaysSinceSleep"));
    ListTag listnbt = compound.getList("Inventory", 10);
    for (int i = 0; i < listnbt.size(); ++i) {
      CompoundTag compoundnbt = listnbt.getCompound(i);
      int j = compoundnbt.getByte("Slot") & 255;
      this.personEquipInv.setItem(j, ItemStack.parseOptional(this.registryAccess(), compoundnbt.getCompound("Item")));
    }
    if (compound.contains("ArmorItems", 9)) {
      ListTag armorItems = compound.getList("ArmorItems", 10);
      for (int i = 0; i < 4/* this.armor.size() */; ++i) {
        ItemStack armorStack = ItemStack.parseOptional(this.registryAccess(), armorItems.getCompound(i));
        int index = Person.slotToInventoryIndex(this.getEquipmentSlotForItem(armorStack));
        this.personEquipInv.setItem(index, armorStack);
      }
    }
    if (compound.contains("HandItems", 9)) {
      ListTag handItems = compound.getList("HandItems", 10);
      for (int i = 0; i < 2/* this.handItems.size() */; ++i) {
        int handSlot = i == 0 ? 5 : 4;
        this.personEquipInv.setItem(handSlot, ItemStack.parseOptional(this.registryAccess(), handItems.getCompound(i)));
      }
    }

    ListTag mainlistnbt = compound.getList("MainInventory", 10);
    for (int i = 0; i < mainlistnbt.size(); ++i) {
      CompoundTag compoundnbt = mainlistnbt.getCompound(i);
      int j = compoundnbt.getByte("Slot") & 255;
      this.personMainInv.setItem(j, ItemStack.parseOptional(this.registryAccess(), compoundnbt.getCompound("Item")));
    }

    if (compound.contains("StatBlock")) {
      this.statBlock = StatBlock.load(compound.getCompound("StatBlock"));
    }
    if (!this.level().isClientSide && this.statBlock != null) {
      // Re-project on every load: recomputes each gene modifier from the saved
      // scores, so weight rebalances reach existing villagers automatically.
      StatProjection.apply(this, this.statBlock);
    }

    if (!this.level().isClientSide) {
      this.readPersistentAngerSaveData(this.level(), compound);
    }
    this.stopSleeping();
  }

  @Override
  public void addAdditionalSaveData(CompoundTag compound) {
    super.addAdditionalSaveData(compound);
    if (this.statBlock != null) {
      compound.put("StatBlock", this.statBlock.save());
    }
    compound.putInt("SkinVariant", this.getSkinVariant());
    compound.putInt("ShieldCooldown", this.shieldCoolDown);
    compound.putBoolean("GuiOpen", this.guiOpen);
    compound.putBoolean("Immobile", this.immobile);
    compound.putBoolean("Eating", this.isEating());
    compound.putBoolean("RunningToEat", this.isRunningToEat());
    compound.putBoolean("Interrupted", this.isInterrupted());
    compound.putInt("DaysSinceSleep", this.getDaysSinceSleep());

    ListTag listnbt = new ListTag();
    for (int i = 0; i < this.personEquipInv.getContainerSize(); ++i) {
      ItemStack itemstack = this.personEquipInv.getItem(i);
      if (!itemstack.isEmpty()) {
        CompoundTag compoundnbt = new CompoundTag();
        compoundnbt.putByte("Slot", (byte) i);
        compoundnbt.put("Item", itemstack.save(this.registryAccess()));
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
        compoundnbt.put("Item", itemstack.save(this.registryAccess()));
        mainlistnbt.add(compoundnbt);
      }
    }
    compound.put("MainInventory", mainlistnbt);

    this.addPersistentAngerSaveData(compound);
  }

  @Override
  public boolean doHurtTarget(Entity entityIn) {
    ItemStack hand = this.getMainHandItem();
    hand.hurtAndBreak(1, this, EquipmentSlot.MAINHAND);
    return super.doHurtTarget(entityIn);
  }

  @Override
  public boolean isInvulnerable() {
    return super.isInvulnerable() || this.isSleeping();
  }

  @Override
  public boolean isInvulnerableTo(DamageSource source) {
    // A campfire never hurts a villager. The village gathering point is a lit
    // campfire and idle villagers crowd right up against it, so anyone whose
    // hitbox overlaps the block would slowly cook. A campfire deals its own
    // CAMPFIRE damage type (since 1.19.4), not the IN_FIRE of a fire block, so
    // the IN_FIRE check alone never applied to it and they burned (3788829).
    // Standing in fire never hurts a villager; being set alight still does, so
    // BURNING_TIME (genetics) stays meaningful and lava keeps its own
    // teleport-home handling.
    return source.is(DamageTypes.CAMPFIRE)
        || source.is(DamageTypes.IN_FIRE)
        || super.isInvulnerableTo(source);
  }

  @Override
  public boolean isImmobile() {
    return hasGuiOpen()
        || this.immobile
        || super.isImmobile()
        || (this.isSleeping() && this.level().isNight());
  }

  @Override
  public void die(DamageSource source) {
    super.die(source);
  }

  @Override
  protected void completeUsingItem() {
    InteractionHand interactionhand = this.getUsedItemHand();
    if (!this.useItem.equals(this.getItemInHand(interactionhand))) {
      this.releaseUsingItem();
    } else {
      if (!this.useItem.isEmpty() && this.isUsingItem()) {
        ItemStack copy = this.useItem.copy();
        ItemStack itemstack = EventHooks.onItemUseFinish(this, copy,
            getUseItemRemainingTicks(), this.useItem.finishUsingItem(this.level(), this));
        if (itemstack != this.useItem) {
          this.setItemInHand(interactionhand, itemstack);
        }
        if (!this.useItem.has(DataComponents.FOOD))
          this.useItem.shrink(1);
        this.stopUsingItem();
      }
    }
  }

  /**
   * A bite heals by Aaron's rule: the food's nutrition as hearts, plus a
   * quarter of its saturation as hearts. Health is in half-hearts, so hearts
   * double. An apple (4 nutrition, 2.4 saturation) heals 4.6 hearts; cooked
   * beef (8, 12.8) heals 11.2, a full bar and more.
   */
  public ItemStack eatFood(Level world, ItemStack stack) {
    FoodProperties food = stack.getFoodProperties(this);
    if (food != null) {
      this.heal(food.nutrition() * 2.0F + food.saturation() / 2.0F);
    }
    ItemStack result = super.eat(world, stack);
    world.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.PLAYER_BURP, SoundSource.PLAYERS, 0.5F,
        world.random.nextFloat() * 0.1F + 0.9F);
    this.setEating(false);
    return result;
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
      this.eatFood(this.level(), this.getOffhandItem());
    }
    if (!this.level().isClientSide) {
      this.updatePersistentAnger((ServerLevel) this.level(), true);
    }
    this.updateSwingTime();
    super.aiStep();
  }

  @Override
  protected EntityDimensions getDefaultDimensions(Pose poseIn) {
    return SIZE_BY_POSE.getOrDefault(poseIn, EntityDimensions.scalable(0.6F, 1.95F).withEyeHeight(1.62F));
  }

  @Override
  protected void blockUsingShield(LivingEntity entityIn) {
    super.blockUsingShield(entityIn);
    if (entityIn.getMainHandItem().canDisableShield(this.useItem, this, entityIn))
      this.disableShield(true);
  }

  @Override
  protected void hurtCurrentlyUsedShield(float damage) {
    if (this.useItem.canPerformAction(ItemAbilities.SHIELD_BLOCK)) {
      if (damage >= 3.0F) {
        int i = 1 + Mth.floor(damage);
        InteractionHand hand = this.getUsedItemHand();
        this.useItem.hurtAndBreak(i, this, getSlotForHand(hand));
        if (this.useItem.isEmpty()) {
          if (hand == InteractionHand.MAIN_HAND) {
            this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
          } else {
            this.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
          }
          this.useItem = ItemStack.EMPTY;
          this.playSound(SoundEvents.SHIELD_BREAK, 0.8F, 0.8F + this.level().random.nextFloat() * 0.4F);
        }
      }
    }
  }

  @Override
  public void startUsingItem(InteractionHand hand) {
    ItemStack itemstack = this.getItemInHand(hand);
    if (itemstack.canPerformAction(ItemAbilities.SHIELD_BLOCK)) {
      AttributeInstance modifiableattributeinstance = this.getAttribute(Attributes.MOVEMENT_SPEED);
      modifiableattributeinstance.removeModifier(SPEED_MODIFIER_ID);
      modifiableattributeinstance.addTransientModifier(USE_ITEM_SPEED_PENALTY);
    }
    super.startUsingItem(hand);
  }

  @Override
  public void stopUsingItem() {
    if (this.getAttribute(Attributes.MOVEMENT_SPEED).hasModifier(SPEED_MODIFIER_ID))
      this.getAttribute(Attributes.MOVEMENT_SPEED).removeModifier(SPEED_MODIFIER_ID);
    super.stopUsingItem();
  }

  public void disableShield(boolean increase) {
    float chance = 0.25F;
    if (increase)
      chance += 0.75;
    if (this.random.nextFloat() < chance) {
      this.shieldCoolDown = 100;
      this.stopUsingItem();
      this.level().broadcastEntityEvent(this, (byte) 30);
    }
  }

  @Override
  protected void defineSynchedData(SynchedEntityData.Builder builder) {
    super.defineSynchedData(builder);
    builder.define(SKIN_VARIANT, 0);
    builder.define(DATA_CHARGING_STATE, false);
    builder.define(EATING, false);
    builder.define(RUNNING_TO_EAT, false);
    builder.define(INTERRUPTED, false);
    builder.define(DAYS_SINCE_SLEEP, 0);
  }

  public boolean isCharging() {
    return this.entityData.get(DATA_CHARGING_STATE);
  }

  @Override
  public void setChargingCrossbow(boolean charging) {
    this.entityData.set(DATA_CHARGING_STATE, charging);
  }

  @Override
  protected void populateDefaultEquipmentSlots(net.minecraft.util.RandomSource random, DifficultyInstance difficulty) {
    for (EquipmentSlot equipmentslottype : EquipmentSlot.values()) {
      List<ItemStack> stacks = this.getItemsFromLootTable(equipmentslottype);
      if (stacks != null) {
        for (ItemStack stack : stacks) {
          this.setItemSlot(equipmentslottype, stack);
        }
      }
    }
    this.handDropChances[EquipmentSlot.MAINHAND.getIndex()] = 100.0F;
    this.handDropChances[EquipmentSlot.OFFHAND.getIndex()] = 100.0F;
  }

  public List<ItemStack> getItemsFromLootTable(EquipmentSlot slot) {
    if (EQUIPMENT_SLOT_ITEMS.containsKey(slot) && this.level() instanceof ServerLevel serverLevel) {
      LootTable loot = serverLevel.getServer().reloadableRegistries().getLootTable(EQUIPMENT_SLOT_ITEMS.get(slot));
      LootParams params = (new LootParams.Builder(serverLevel))
          .withParameter(LootContextParams.THIS_ENTITY, this)
          .create(PersonLootTables.SLOT);
      return loot.getRandomItems(params);
    }
    return null;
  }

  public int getSkinVariant() {
    return this.entityData.get(SKIN_VARIANT);
  }

  /**
   * Sets which skin this person wears. The value is wrapped into the available range so a
   * variant saved by a build with a larger skin pool can never point at a missing texture.
   */
  public void setSkinVariant(int variant) {
    this.entityData.set(SKIN_VARIANT, Math.floorMod(variant, SKIN_INDEX_RANGE));
  }

  @Override
  public ItemStack getPickedResult(HitResult target) {
    return new ItemStack(VillagelifeItems.PERSON_SPAWN_EGG.get());
  }

  @Override
  public boolean canBeLeashed() {
    return true; // TODO, change back to false
  }

  @Override
  public boolean canPickUpLoot() {
    return true;
  }

  @Override
  protected void pickUpItem(ItemEntity itemEntity) {
    recordPickup(itemEntity);
    // A picked-up trinket goes ON the person when an accessory mod gives them
    // somewhere to put it (#46), so a ring thrown to a villager is worn rather
    // than pocketed. Anything left over is ordinary inventory.
    ItemStack leftover = AccessoryCompat.equipIfPossible(this, itemEntity.getItem());
    if (leftover.isEmpty()) {
      this.take(itemEntity, itemEntity.getItem().getCount());
      itemEntity.discard();
      return;
    }
    itemEntity.setItem(leftover);
    HopperBlockEntity.addItem(this.personMainInv, itemEntity);
  }

  /**
   * Remembers what was picked up and who threw it — the conversation system's
   * ground truth for emergent gifting. A pickup is only a memory (decided on
   * #43): whether it was a gift is the villager's own judgment, made in
   * conversation, where it can move their personal opinion of the thrower.
   */
  private void recordPickup(ItemEntity itemEntity) {
    ItemStack stack = itemEntity.getItem();
    if (stack.isEmpty()) {
      return;
    }
    Optional<UUID> thrower = Optional.ofNullable(itemEntity.getOwner())
        .filter(owner -> owner instanceof Player)
        .map(Entity::getUUID);
    PersonalLogData.Entry entry = PersonalLogData.pickup(
        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
        stack.getCount(),
        this.level().getDayTime(),
        thrower);
    setData(VillagelifeAttachments.PERSONAL_LOG.get(),
        getData(VillagelifeAttachments.PERSONAL_LOG.get()).withEntry(entry));
  }

  /**
   * Logs a problem this person ran into — emitted only by game code at real
   * moments (never LLM-invented), the foundation of emergent quests. An
   * identical issue within the last day is dropped so a stuck goal cannot
   * spam the log.
   */
  public void logIssue(String text, Optional<UUID> who) {
    PersonalLogData log = getData(VillagelifeAttachments.PERSONAL_LOG.get());
    long now = this.level().getDayTime();
    if (log.hasRecentIssue(text, now)) {
      return;
    }
    setData(VillagelifeAttachments.PERSONAL_LOG.get(),
        log.withEntry(PersonalLogData.issue(text, now, who)));
    Villagelife.LOGGER.debug("[{}] logged issue: {}", this.getName().getString(), text);
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
      default:
        break;
    }
  }

  /** Something a villager can eat or drink to heal: food, or a potion that is not thrown. */
  public static boolean isMeal(ItemStack stack) {
    return !stack.isEmpty()
        && (stack.getUseAnimation() == UseAnim.EAT
            || (stack.getUseAnimation() == UseAnim.DRINK && !(stack.getItem() instanceof SplashPotionItem)));
  }

  /** The pack slot holding the first meal, or -1 when the pack has none. */
  public int mealSlotInPack() {
    for (int slot = 0; slot < this.personMainInv.getContainerSize(); slot++) {
      if (isMeal(this.personMainInv.getItem(slot))) {
        return slot;
      }
    }
    return -1;
  }

  /** Whether there is anything to eat, in the off hand or in the pack. */
  public boolean hasMeal() {
    return isMeal(this.getOffhandItem()) || mealSlotInPack() >= 0;
  }

  /** How many bites are carried, off hand and pack together. */
  public int mealsCarried() {
    int count = isMeal(this.getOffhandItem()) ? this.getOffhandItem().getCount() : 0;
    for (int slot = 0; slot < this.personMainInv.getContainerSize(); slot++) {
      ItemStack stack = this.personMainInv.getItem(slot);
      if (isMeal(stack)) {
        count += stack.getCount();
      }
    }
    return count;
  }

  /**
   * A shield, or any off-hand item that can block: a guard's defence. Told by the
   * SHIELD_BLOCK ability rather than the vanilla class, so a modded shield counts
   * the same way {@link com.quzzar.villagelife.entities.ai.goals.RaiseShieldGoal}
   * reads it.
   */
  public static boolean isShield(ItemStack stack) {
    return !stack.isEmpty()
        && stack.getItem().canPerformAction(stack, net.neoforged.neoforge.common.ItemAbilities.SHIELD_BLOCK);
  }

  /** The pack slot holding the first shield, or -1 when the pack has none. */
  public int shieldSlotInPack() {
    for (int slot = 0; slot < this.personMainInv.getContainerSize(); slot++) {
      if (isShield(this.personMainInv.getItem(slot))) {
        return slot;
      }
    }
    return -1;
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

  @Override
  public boolean canAttack(LivingEntity target) {
    return !(target instanceof Person)
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
    // Plain right-click talks; sneak-right-click opens the equipment screen.
    if (this.getTarget() != player && this.isEffectiveAi() && player instanceof ServerPlayer serverPlayer) {
      if (player.isSecondaryUseActive() || !(this instanceof RealPerson realPerson)) {
        this.openGui(serverPlayer);
      } else {
        realPerson.openChat(serverPlayer);
      }
      return InteractionResult.SUCCESS;
    }
    return InteractionResult.CONSUME;
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
        if ((!damageSource.is(DamageTypeTags.IS_FIRE) || !itemstack.has(DataComponents.FIRE_RESISTANT))
            && itemstack.getItem() instanceof ArmorItem) {
          EquipmentSlot slot = switch (i) {
            case 0 -> EquipmentSlot.HEAD;
            case 1 -> EquipmentSlot.CHEST;
            case 2 -> EquipmentSlot.LEGS;
            case 3 -> EquipmentSlot.FEET;
            default -> null;
          };
          if (slot != null) {
            itemstack.hurtAndBreak((int) damage, this, slot);
          }
        }
      }
    }
  }

  public void openGui(ServerPlayer player) {
    setGuiOpen(true);
    player.openMenu(new SimpleMenuProvider(
        (id, playerInventory, p) -> new PersonContainer(id, playerInventory, this.personEquipInv, this),
        this.getDisplayName()), buf -> buf.writeInt(this.getId()));
  }

  public static AttributeSupplier.Builder createAttributes() {
    // ATTACK_KNOCKBACK and ATTACK_SPEED are not in the Mob defaults; they are
    // registered here so the genetics projection (StatProjection) can modify
    // them.
    return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 40.0D).add(Attributes.MOVEMENT_SPEED, 0.5D)
        .add(Attributes.ATTACK_DAMAGE, 1.0D).add(Attributes.FOLLOW_RANGE, 20.0D)
        .add(Attributes.ATTACK_KNOCKBACK, 0.0D).add(Attributes.ATTACK_SPEED, 4.0D);
  }

  public boolean isEating() {
    return this.entityData.get(EATING);
  }

  public void setEating(boolean eating) {
    this.entityData.set(EATING, eating);
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
    if (angerTime == null) {
      angerTime = net.minecraft.util.TimeUtil.rangeOfSeconds(20, 39);
    }
    this.setRemainingPersistentAngerTime(angerTime.sample(this.random));
  }

  @Override
  public void performRangedAttack(LivingEntity target, float distanceFactor) {
    this.shieldCoolDown = 8;
    if (this.getMainHandItem().getItem() instanceof CrossbowItem)
      this.performCrossbowAttack(this, 6.0F);
    if (this.getMainHandItem().getItem() instanceof BowItem) {
      shootArrowAt(target, distanceFactor, (float) (14 - this.level().getDifficulty().getId() * 4));
    }
  }

  /**
   * Looses one arrow from the held bow. Combat keeps vanilla's
   * difficulty-scaled spread (performRangedAttack above); the hunter passes a
   * tight spread of its own, because a hunter who misses half their shots
   * starves the lodge (HuntStep).
   *
   * The projectile comes from getProjectile: a special arrow is a real item
   * and is spent on the shot, while the plain-arrow fallback is conjured and
   * never counted (docs/worker-loops.md).
   */
  public void shootArrowAt(LivingEntity target, float power, float inaccuracy) {
    ItemStack bow = this.getItemInHand(Utils.getHandWith(this, item -> item instanceof BowItem));
    if (!(bow.getItem() instanceof BowItem)) {
      return; // callers gate on the bow, but a hand swapped mid-tick stays safe
    }
    ItemStack projectile = this.getProjectile(bow);
    AbstractArrow arrow = ProjectileUtil.getMobArrow(this, projectile, power, bow);
    double d0 = target.getX() - this.getX();
    double d1 = target.getY(0.3333333333333333D) - arrow.getY();
    double d2 = target.getZ() - this.getZ();
    double d3 = Mth.sqrt((float) (d0 * d0 + d2 * d2));
    arrow.shoot(d0, d1 + d3 * (double) 0.2F, d2, 1.6F, inaccuracy);
    this.playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
    this.level().addFreshEntity(arrow);
    if (!projectile.is(Items.ARROW)) {
      projectile.shrink(1);
    }
    bow.hurtAndBreak(1, this, EquipmentSlot.MAINHAND);
  }

  /**
   * What the next shot fires: any special arrow actually carried (tipped,
   * spectral - anything the weapon accepts that is not a plain arrow), hands
   * first and then the pack, else a conjured plain arrow. Plain arrows are
   * never counted or consumed - workers do not bookkeep ammunition - but
   * specials given to a villager are real, finite, and used first
   * (docs/worker-loops.md). The crossbow's vanilla charge path consumes
   * through here too, so a guard's tipped bolts follow the same rule.
   */
  @Override
  public ItemStack getProjectile(ItemStack shootable) {
    if (!(shootable.getItem() instanceof ProjectileWeaponItem weapon)) {
      return ItemStack.EMPTY;
    }
    Predicate<ItemStack> supported = weapon.getSupportedHeldProjectiles();
    ItemStack held = ProjectileWeaponItem.getHeldProjectile(this, supported);
    if (isSpecialArrow(held)) {
      return held;
    }
    for (int slot = 0; slot < this.personMainInv.getContainerSize(); slot++) {
      ItemStack stack = this.personMainInv.getItem(slot);
      if (supported.test(stack) && isSpecialArrow(stack)) {
        return stack;
      }
    }
    return new ItemStack(Items.ARROW);
  }

  /** A projectile worth bookkeeping: present, and not the infinite plain arrow. */
  private static boolean isSpecialArrow(ItemStack stack) {
    return !stack.isEmpty() && !stack.is(Items.ARROW);
  }

  @Override
  public void onCrossbowAttackPerformed() {
    this.noActionTime = 0;
  }

}
