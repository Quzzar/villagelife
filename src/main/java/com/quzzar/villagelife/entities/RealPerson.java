package com.quzzar.villagelife.entities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.networking.OpenPersonChatPacket;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.Occupation;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.VillageManager;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.TimeUtil;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.ai.goals.ArmorerRepairPersonArmorGoal;
import com.quzzar.villagelife.entities.ai.goals.DefendOthersFromPlayerGoal;
import com.quzzar.villagelife.entities.ai.goals.HarvestCropGoal;
import com.quzzar.villagelife.entities.ai.goals.ReturnBackToVillageGoal;
import com.quzzar.villagelife.entities.ai.goals.RunAwayGoal;
import com.quzzar.villagelife.entities.ai.goals.PersonEatFoodGoal;
import com.quzzar.villagelife.entities.ai.goals.PersonMeleeGoal;
import com.quzzar.villagelife.entities.ai.goals.ProcessItemGoal;
import com.quzzar.villagelife.entities.ai.goals.RunToEatGoal;
import com.quzzar.villagelife.entities.ai.goals.SearchForItemsGoal;
import com.quzzar.villagelife.entities.ai.goals.SetRunningToEatGoal;
import com.quzzar.villagelife.entities.ai.goals.HealPersonAndPlayerGoal;
import com.quzzar.villagelife.entities.ai.goals.PanicToBedGoal;
import com.quzzar.villagelife.entities.ai.goals.RaiseShieldGoal;
import com.quzzar.villagelife.entities.ai.goals.RangedBowAttackPassiveGoal;
import com.quzzar.villagelife.entities.ai.goals.RangedCrossbowAttackPassiveGoal;
import com.quzzar.villagelife.entities.ai.goals.SleepAtNightGoal;
import com.quzzar.villagelife.entities.ai.goals.StrollAroundVillage;
import com.quzzar.villagelife.entities.ai.goals.TillSoilGoal;
import com.quzzar.villagelife.entities.ai.goals.UnstuckPersonGoal;
import com.quzzar.villagelife.entities.ai.goals.UseBonemealGoal;
import com.quzzar.villagelife.entities.ai.goals.DepositHaulGoal;
import com.quzzar.villagelife.entities.ai.goals.WorkInMineGoal;
import com.quzzar.villagelife.entities.ai.goals.WorkOnBuildingGoal;
import com.quzzar.villagelife.entities.ai.goals.WorkOnMakingPathsGoal;
import com.quzzar.villagelife.entities.ai.goals.WorkOnWoodcuttingGoal;
import com.quzzar.villagelife.other.EquipmentUpgrade;

import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.ResetUniversalAngerTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;

public class RealPerson extends Person {

  // Constants
  private static final double UNISEX_NAME_CHANCE = 0.1;
  private static final int MIN_FAV_ITEMS = 3, MAX_FAV_ITEMS = 7;

  // Variables
  private static final EntityDataAccessor<String> FIRST_NAME = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.STRING);
  private static final EntityDataAccessor<String> LAST_NAME = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.STRING);

  private static final EntityDataAccessor<String> VILLAGE_UUID = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.STRING);
  /** Display-only village name, synced so the client can render it under the nameplate. */
  private static final EntityDataAccessor<String> VILLAGE_NAME = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.STRING);

  private static final EntityDataAccessor<String> PERSONALITY = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.STRING);
  private static final EntityDataAccessor<String> MARRIAGE_STATUS = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.STRING);
  private static final EntityDataAccessor<String> OCCUPATION = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.STRING);
  private static final EntityDataAccessor<String> GENDER = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.STRING);

  public int callToBedCoolDown = 0;

  // Village-directed walk target (arriving at the campfire / leaving the
  // village); driven by Village.tickTravelers, executed by VillageTravelGoal.
  private transient BlockPos travelTarget;

  @Nullable
  public BlockPos getTravelTarget() {
    return travelTarget;
  }

  public void setTravelTarget(@Nullable BlockPos target) {
    this.travelTarget = target;
  }

  public RealPerson(EntityType<? extends Person> type, Level world) {
    super(type, world);
  }

  @Override
  public SpawnGroupData finalizeSpawn(ServerLevelAccessor worldIn, DifficultyInstance difficultyIn,
      MobSpawnType reason, @Nullable SpawnGroupData spawnDataIn) {
    SpawnGroupData data = super.finalizeSpawn(worldIn, difficultyIn, reason, spawnDataIn);
    generateIdentity();
    return data;
  }

  /**
   * Rolls this person's identity: gender, virtues, personality, name, and
   * favorite items. Runs once, server-side, at spawn finalization; entities
   * loaded from NBT keep their saved identity instead.
   */
  private void generateIdentity() {
    if (!getFirstName().isEmpty()) {
      return;
    }

    Random rand = new Random();

    // Gender
    setGender(Gender.generateGender());

    // Virtues (from -0.5 to 0.5)
    setVirtue(Virtue.AGGRESSION, rand.nextFloat() - 0.5F);
    setVirtue(Virtue.CURIOSITY, rand.nextFloat() - 0.5F);
    setVirtue(Virtue.DRIVE, rand.nextFloat() - 0.5F);
    setVirtue(Virtue.PROTECT_OTHERS, rand.nextFloat() - 0.5F);
    setVirtue(Virtue.PROTECT_SELF, rand.nextFloat() - 0.5F);

    // Personality
    Personality personality = Personality.generateFromVirtues(
        getVirtue(Virtue.AGGRESSION),
        getVirtue(Virtue.CURIOSITY),
        getVirtue(Virtue.DRIVE),
        getVirtue(Virtue.PROTECT_OTHERS),
        getVirtue(Virtue.PROTECT_SELF));
    setPersonality(personality);

    // Parse JSON array from lang file, get random first/last name, set it to entity
    // name
    boolean isUnisexName = (rand.nextDouble() < UNISEX_NAME_CHANCE);
    JsonArray firstNameArray = JsonParser
        .parseString(Component.translatable(personality.name().toLowerCase() + "."
            + ((isUnisexName) ? "nonbinary" : getGender().name().toLowerCase()) + ".first_names").getString())
        .getAsJsonArray();
    setFirstName(firstNameArray.get(rand.nextInt(firstNameArray.size())).getAsString());

    JsonArray lastNameArray = JsonParser.parseString(Component.translatable("last_names").getString())
        .getAsJsonArray();
    setLastName(lastNameArray.get(rand.nextInt(lastNameArray.size())).getAsString());

    refreshDisplayName();

    // Pick some random favorite items
    List<String> favItemList = personality.getFavoriteItems();
    Collections.shuffle(favItemList);
    Map<String, Double> favoriteItems = new HashMap<>();
    for (int i = 0; i <= rand.nextInt(MAX_FAV_ITEMS - MIN_FAV_ITEMS) + MIN_FAV_ITEMS; i++) {
      favoriteItems.put(favItemList.get(i), rand.nextDouble());
    }

    setFavoriteItems(favoriteItems);
    setRelationships(new HashMap<UUID, Integer>());

    setMarriageStatus(MarriageStatus.SINGLE);

    refreshAngerTime();
    reloadState();

  }

  /** Anger timing is derived from the virtues, so it must be refreshed whenever they change or load. */
  private void refreshAngerTime() {
    int baseAngerTime = (int) (10 + 20 * ((getVirtue(Virtue.PROTECT_SELF) + getVirtue(Virtue.PROTECT_OTHERS)) / 2));
    int rangeAngerTime = (int) (10 + 20 * getVirtue(Virtue.AGGRESSION));
    if (baseAngerTime <= 0) {
      baseAngerTime = 1;
    }
    if (rangeAngerTime <= 0) {
      rangeAngerTime = 1;
    }
    this.angerTime = TimeUtil.rangeOfSeconds(baseAngerTime, baseAngerTime + rangeAngerTime);
  }

  @Override
  protected void defineSynchedData(SynchedEntityData.Builder builder) {
    super.defineSynchedData(builder);
    builder.define(FIRST_NAME, "");
    builder.define(LAST_NAME, "");

    builder.define(VILLAGE_UUID, "");
    builder.define(VILLAGE_NAME, "");

    builder.define(PERSONALITY, "CHEERFUL");
    builder.define(MARRIAGE_STATUS, "SINGLE");
    builder.define(OCCUPATION, "IDLE");
    builder.define(GENDER, "NONBINARY");

  }

  @Override
  public void readAdditionalSaveData(CompoundTag compound) {
    super.readAdditionalSaveData(compound);

    // Only overwrite the generated defaults when the tag actually carries a value,
    // so freshly summoned people keep their randomly generated identity.
    setStringIfPresent(compound, "FirstName", FIRST_NAME);
    setStringIfPresent(compound, "LastName", LAST_NAME);

    setStringIfPresent(compound, "VillageUUID", VILLAGE_UUID);
    setStringIfPresent(compound, "VillageName", VILLAGE_NAME);

    setStringIfPresent(compound, "Personality", PERSONALITY);
    setStringIfPresent(compound, "MarriageStatus", MARRIAGE_STATUS);
    setStringIfPresent(compound, "Occupation", OCCUPATION);
    setStringIfPresent(compound, "Gender", GENDER);

    this.callToBedCoolDown = compound.getInt("CallToBedCooldown");

    refreshAngerTime();

    refreshDisplayName();
    reloadState();
  }

  private void setStringIfPresent(CompoundTag compound, String key, EntityDataAccessor<String> accessor) {
    String value = compound.getString(key);
    if (!value.isEmpty()) {
      this.entityData.set(accessor, value);
    }
  }

  @Override
  public void addAdditionalSaveData(CompoundTag compound) {
    super.addAdditionalSaveData(compound);

    compound.putString("FirstName", this.entityData.get(FIRST_NAME));
    compound.putString("LastName", this.entityData.get(LAST_NAME));

    compound.putString("VillageUUID", this.entityData.get(VILLAGE_UUID));
    compound.putString("VillageName", this.entityData.get(VILLAGE_NAME));

    compound.putString("Personality", this.entityData.get(PERSONALITY));
    compound.putString("MarriageStatus", this.entityData.get(MARRIAGE_STATUS));
    compound.putString("Occupation", this.entityData.get(OCCUPATION));
    compound.putString("Gender", this.entityData.get(GENDER));

    compound.putInt("CallToBedCooldown", this.callToBedCoolDown);

  }

  @Override
  public void aiStep() {
    if (this.callToBedCoolDown > 0) {
      --this.callToBedCoolDown;
    }
    // Orphan self-heal (#59): a person pointing at a village that no longer
    // exists, or whose roster dropped them without the goodbye completing (an
    // emigrant whose chunks unloaded mid-walk), quietly becomes a wanderer.
    // Mid-walk travelers are exempt: they are village-bound but not yet on
    // the roster.
    if (!this.level().isClientSide && this.tickCount % 200 == 137
        && !this.entityData.get(VILLAGE_UUID).isEmpty()) {
      Village village = getVillage();
      if (village == null
          || (!village.hasResident(getUUID()) && !village.isTraveler(getUUID()))) {
        setVillage("");
        setVillageName("");
        Villagelife.LOGGER.debug("[{}] was orphaned from their village and now wanders",
            this.getName().getString());
      }
    }
    super.aiStep();
  }

  @Override
  public void die(DamageSource source) {
    super.die(source);
    Village village = getVillage();
    if (village != null) {
      village.removePerson(getUUID());
    }
  }

  public void setVillage(String villageUUID) {
    this.entityData.set(VILLAGE_UUID, villageUUID);
  }

  /** Display-only; the authoritative membership is the village UUID. */
  public void setVillageName(String villageName) {
    this.entityData.set(VILLAGE_NAME, villageName);
  }

  public String getVillageName() {
    return this.entityData.get(VILLAGE_NAME);
  }

  public Village getVillage() {
    if (this.level().isClientSide || !(this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
      return null;
    }
    // Save data access is main-thread only; off-thread callers (entity
    // deserialization workers) simply see no village until the next tick.
    if (!serverLevel.getServer().isSameThread()) {
      return null;
    }
    return VillageManager.get(serverLevel).getVillage(this.entityData.get(VILLAGE_UUID));
  }

  protected void setPersonality(Personality personality) {
    this.entityData.set(PERSONALITY, personality.name());
  }

  protected void setMarriageStatus(MarriageStatus marriageStatus) {
    this.entityData.set(MARRIAGE_STATUS, marriageStatus.name());
  }

  protected void setGender(Gender gender) {
    this.entityData.set(GENDER, gender.name());
  }

  public void setOccupation(Occupation occupation) {
    this.entityData.set(OCCUPATION, occupation.name());
  }

  public void reloadState() {
    this.temp_setDefaultEquipment();

    // Reregister Goals
    this.goalSelector.removeAllGoals((goal) -> true);
    this.registerGoals();
  }

  public void goToBed(double speed) {
    if (this.callToBedCoolDown > 0 || this.getVillage() == null) {
      return;
    }
    this.callToBedCoolDown = 100;

    BlockPos headingToLoc = LocationManager.getBedLocation(this);
    BlockPos depositToLoc = LocationManager.getJobLocation(this);
    if (headingToLoc.equals(BlockPos.ZERO)) {
      headingToLoc = depositToLoc;
    }

    if (!headingToLoc.equals(BlockPos.ZERO)) {
      this.setInterrupted(true);
      this.getNavigation().moveTo(headingToLoc.getX(), headingToLoc.getY(), headingToLoc.getZ(), speed);
    }

    // If no depositToLoc, set to null to indicate no container preference
    if (depositToLoc.equals(BlockPos.ZERO)) {
      depositToLoc = null;
    }

    // Put items in main inventory away
    List<ItemStack> items = this.clearMainInventory();
    for (ItemStack item : items) {
      boolean addedItem = this.getVillage().placeItemStackIntoVillage(item, this, depositToLoc);
      if (!addedItem) {
        // TODO, make note to build more storage, high priority
      }
    }

    //

    // Grab replacement gear if not holding any
    if (getOccupation() == Occupation.GUARD) {

      equipBestPossibleGear(SwordItem.class, null, true, depositToLoc);

      // Find up to 16 bread
      if (this.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) {

        ItemStack breadItem = this.getVillage().gatherItemStackFromVillage(new ItemStack(Items.BREAD, 16),
            depositToLoc);
        if (breadItem.getCount() >= 1) {
          this.setItemSlot(EquipmentSlot.OFFHAND, breadItem);
        } else {
          // A guard going to bed with no rations is a shortage worth remembering.
          this.getVillage().logEvent(
              new com.quzzar.villagelife.village.bookkeeping.NoResourceBookkeepingEvent(Items.BREAD, 16));
        }

      }

    } else {

      // Will attempt to equip with upgraded main & offhand item
      equipBestPossibleGear(null, null, false, depositToLoc);

    }

    // Take 1 sponge if builder or miner // TODO, change to if needed
    if (getOccupation() == Occupation.BUILDER || getOccupation() == Occupation.MINER) {

      ItemStack item = this.getVillage().gatherItemStackFromVillage(new ItemStack(Items.SPONGE, 1), depositToLoc);
      this.addItems(Arrays.asList(item));

    }

    // Grab bonemeal
    if (getOccupation() == Occupation.LUMBERJACK || getOccupation() == Occupation.FARMER) {

      ItemStack item = this.getVillage().gatherItemStackFromVillage(new ItemStack(Items.BONE_MEAL, 16), depositToLoc);
      this.addItems(Arrays.asList(item));

    }

    // Take seeds
    if (getOccupation() == Occupation.FARMER) {

      ArrayList<ItemStack> gatheredSeeds = new ArrayList<>();
      for (Item seed : TillSoilGoal.PLANTABLES.keySet()) {
        ItemStack gatheredSeed = this.getVillage().gatherItemStackFromVillage(new ItemStack(seed, 8), depositToLoc);
        gatheredSeeds.add(gatheredSeed);
      }

      this.addItems(gatheredSeeds);

    }

  }

  public void equipBestPossibleGear(Class mainHand, Class offHand, boolean includeArmor, BlockPos preferNearestToLoc) {

    final ArrayList<ItemStack> villageInventory = this.getVillage().getVillageInventory();

    findAndEquipForSlot(mainHand, EquipmentSlot.MAINHAND, villageInventory, preferNearestToLoc);
    findAndEquipForSlot(offHand, EquipmentSlot.OFFHAND, villageInventory, preferNearestToLoc);

    if (includeArmor) {
      findAndEquipForSlot(ArmorItem.class, EquipmentSlot.HEAD, villageInventory, preferNearestToLoc);
      findAndEquipForSlot(ArmorItem.class, EquipmentSlot.CHEST, villageInventory, preferNearestToLoc);
      findAndEquipForSlot(ArmorItem.class, EquipmentSlot.LEGS, villageInventory, preferNearestToLoc);
      findAndEquipForSlot(ArmorItem.class, EquipmentSlot.FEET, villageInventory, preferNearestToLoc);
    }

  }

  private void findAndEquipForSlot(Class classType, EquipmentSlot slot, ArrayList<ItemStack> villageInventory,
      BlockPos preferNearestToLoc) {

    ItemStack oldItem = this.getItemBySlot(slot);
    ItemStack newItem;
    if (oldItem.isEmpty()) {
      if (classType != null) {
        newItem = EquipmentUpgrade.findBestOfType(classType, slot, villageInventory, this.random);
      } else {
        newItem = null;
      }
    } else {
      newItem = EquipmentUpgrade.findUpgrade(oldItem, villageInventory, this.random);
    }

    if (newItem == null) {
      // No upgrade found
      return;
    }

    newItem.setCount(1);
    ItemStack foundItem = this.getVillage().gatherItemStackFromVillage(newItem, preferNearestToLoc);
    if (foundItem.getCount() == 1) {

      if (!oldItem.isEmpty()) {
        // Place old item into village first then equip new item
        this.getVillage().placeItemStackIntoVillage(oldItem, this, preferNearestToLoc);
      }

      this.setItemSlot(slot, foundItem);

    }

  }

  public void tpToHome() {
    this.moveTo(LocationManager.getVillageCenter(this), 0.0F, 0.0F);
    // TODO, or to follow leader if has one
  }

  public Personality getPersonality() {
    return Personality.valueOf(this.entityData.get(PERSONALITY));
  }

  public MarriageStatus getMarriageStatus() {
    return MarriageStatus.valueOf(this.entityData.get(MARRIAGE_STATUS));
  }

  public Gender getGender() {
    return Gender.valueOf(this.entityData.get(GENDER));
  }

  public Occupation getOccupation() {
    return Occupation.valueOf(this.entityData.get(OCCUPATION));
  }

  protected void setVirtue(Virtue virtue, float value) {
    setData(VillagelifeAttachments.SOCIAL, getData(VillagelifeAttachments.SOCIAL).withVirtue(virtue, value));
  }

  public float getVirtue(Virtue virtue) {
    return getData(VillagelifeAttachments.SOCIAL).getVirtue(virtue);
  }

  @Override
  protected void populateDefaultEquipmentSlots(RandomSource random, DifficultyInstance difficulty) {
    switch (getOccupation()) {
      case GUARD:
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
        this.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.BREAD, 16));
        this.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        this.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
        this.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
        this.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
        // super.populateDefaultEquipmentSlots(difficulty);
        break;
      case LUMBERJACK:
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_AXE));
        break;
      case BLACKSMITH:
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_INGOT));
        break;
      case BUILDER:
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CRAFTING_TABLE));
        break;
      case CLERIC:
        ItemStack healingPotion = new ItemStack(Items.POTION);
        healingPotion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.HEALING));
        this.setItemSlot(EquipmentSlot.OFFHAND, healingPotion);
        ItemStack regenPotion = new ItemStack(Items.SPLASH_POTION);
        regenPotion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.REGENERATION));
        this.setItemSlot(EquipmentSlot.MAINHAND, regenPotion);
        break;
      case FARMER:
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_HOE));
        break;
      case LEADER:
        this.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
        break;
      case LIBRARIAN:
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOOK));
        break;
      case MINER:
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
        break;
      case NITWIT:
        break;
      default:
        break;
    }
  }

  public void temp_setDefaultEquipment() {
    this.populateDefaultEquipmentSlots(this.random, null);
  }

  protected void setFavoriteItems(Map<String, Double> favoriteItems) {
    setData(VillagelifeAttachments.SOCIAL, getData(VillagelifeAttachments.SOCIAL).withFavoriteItems(favoriteItems));
  }

  public Map<String, Double> getFavoriteItems() {
    return getData(VillagelifeAttachments.SOCIAL).favoriteItems();
  }

  protected void setRelationships(Map<UUID, Integer> relationships) {
    setData(VillagelifeAttachments.SOCIAL, getData(VillagelifeAttachments.SOCIAL).withRelationships(relationships));
  }

  public Map<UUID, Integer> getRelationships() {
    return getData(VillagelifeAttachments.SOCIAL).relationships();
  }

  /*
   * public int getRelationship(UUID playerUUID){
   * if(relationships.containsKey(playerUUID)){
   * return relationships.get(playerUUID);
   * } else {
   * return 5;
   * }
   * }
   * public int increaseRelationship(UUID playerUUID){
   * if(relationships.containsKey(playerUUID)){
   * int value = relationships.get(playerUUID);
   * relationships.put(playerUUID, value+1);
   * return value+1;
   * } else {
   * relationships.put(playerUUID, 1);
   * return 1;
   * }
   * }
   * public int decreaseRelationship(UUID playerUUID){
   * if(relationships.containsKey(playerUUID)){
   * int value = relationships.get(playerUUID);
   * relationships.put(playerUUID, value-1);
   * return value-1;
   * } else {
   * relationships.put(playerUUID, 0);
   * return 0;
   * }
   * }
   */

  protected void setFirstName(String name) {
    this.entityData.set(FIRST_NAME, name);
  }

  protected void setLastName(String name) {
    this.entityData.set(LAST_NAME, name);
  }

  public String getFirstName() {
    return this.entityData.get(FIRST_NAME);
  }

  public String getLastName() {
    return this.entityData.get(LAST_NAME);
  }

  public String getFullName() {
    return getFirstName() + " " + getLastName();
  }

  /**
   * Opens the conversation screen; header lines are composed server-side.
   * When titles exist, the title replaces the occupation + village line.
   */
  public void openChat(ServerPlayer player) {
    String detail = Utils.capitalize(getOccupation().name().toLowerCase());
    if (getVillage() != null) {
      detail = detail + " of " + getVillage().getName();
    }
    com.quzzar.villagelife.chat.PersonChatDispatcher.markOpen(this, player);
    List<OpenPersonChatPacket.ExchangeLine> scrollback = getData(
        VillagelifeAttachments.CHAT_HISTORY.get()).with(player.getUUID()).stream()
        .map(e -> new OpenPersonChatPacket.ExchangeLine(e.playerLine(), e.reply()))
        .toList();
    // A merchant at a staffed market gets a Trade tab; everyone else is chat alone.
    boolean canTrade = getOccupation() == Occupation.MERCHANT && getVillage() != null
        && com.quzzar.villagelife.economy.Treasury.tradeBlocker(getVillage(),
            (net.minecraft.server.level.ServerLevel) level()).isEmpty();
    PacketDistributor.sendToPlayer(player,
        new OpenPersonChatPacket(this.getId(), getFullName(), detail, canTrade, scrollback));
    if (scrollback.isEmpty()) {
      // Nothing to show yet: the villager opens the conversation themselves.
      com.quzzar.villagelife.chat.PersonChatDispatcher.greet(this, player);
    }
  }

  public void refreshDisplayName() {
    this.setCustomName(Component.literal(getFullName()));
  }

  public boolean doesCombat() {
    return willInitiateCombat() || willDefendItself();
  }

  public boolean willDefendItself() {
    if (willInitiateCombat()) {
      return true;
    } else {
      double i = 0.0;
      switch (getOccupation()) {
        case GUARD:
          i += 0.9;
          break;
        case MINER:
          i += 0.4;
          break;
        case LUMBERJACK:
          i += 0.4;
          break;
        case BLACKSMITH:
          i += 0.4;
          break;
        case BUILDER:
          i += 0.2;
          break;
        case FARMER:
          i += 0.2;
          break;
        default:
          break;
      }
      i += getVirtue(Virtue.PROTECT_SELF);
      return (i > 0.5);
    }
  }

  public boolean willInitiateCombat() {
    double i = 0.0;
    switch (getOccupation()) {
      case GUARD:
        i += 0.8;
        break;
      case MINER:
        i += 0.15;
        break;
      case LUMBERJACK:
        i += 0.15;
        break;
      case BLACKSMITH:
        i += 0.15;
        break;
      case BUILDER:
        i += 0.1;
        break;
      case FARMER:
        i += 0.1;
        break;
      default:
        break;
    }
    i += getVirtue(Virtue.AGGRESSION);
    return (i > 0.5);
  }

  public boolean willDefendBestFriend() {
    double i = 0.0;
    switch (getOccupation()) {
      case GUARD:
        i += 0.2;
        break;
      case LEADER:
        i += 0.3;
        break;
      default:
        break;
    }
    i += getVirtue(Virtue.PROTECT_OTHERS) * 2;
    return willDefendItself() && (i > 0.5);
  }

  @Override
  protected void registerGoals() {

    Villagelife.LOGGER.debug("REGISTERING GOALS FOR " + getUUID());

    // Just in case some goals made them immobile,
    this.setImmobile(false);

    this.goalSelector.addGoal(0, new FloatGoal(this));
    this.goalSelector.addGoal(0, new PersonEatFoodGoal(this));
    this.goalSelector.addGoal(1, new com.quzzar.villagelife.entities.ai.goals.VillageTravelGoal(this));
    this.goalSelector.addGoal(3, new com.quzzar.villagelife.entities.ai.goals.PauseForConversationGoal(this));

    if (doesCombat()) {

      this.goalSelector.addGoal(2, new RangedCrossbowAttackPassiveGoal<>(this, 1.0D, 8.0F));
      this.goalSelector.addGoal(2, new RangedBowAttackPassiveGoal<>(this, 0.5D, 20, 15.0F));
      this.goalSelector.addGoal(2, new PersonMeleeGoal(this, 0.8D, true));

      this.targetSelector.addGoal(3,
          new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false, this::isAngryAt));
      // this.targetSelector.addGoal(3, new FollowLeaderHurtByTargetGoal(this));

      this.goalSelector.addGoal(1, new RaiseShieldGoal(this));

    } else {

      this.goalSelector.addGoal(1, new PanicToBedGoal(this, 0.6D));

      this.goalSelector.addGoal(5, new AvoidEntityGoal<>(this, Mob.class, 12.0F, 0.5D, 0.5D, (mob) -> {
        return mob instanceof Enemy;
      }));

    }

    if (willInitiateCombat()) {// Actively seeks out combat

      this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Mob.class, 5, true, true, (mob) -> {
        return mob instanceof Enemy && !(mob instanceof Creeper) && !(mob instanceof EnderMan);
      }));

      // this.targetSelector.addGoal(3, new FollowLeaderHurtTargetGoal(this));
      this.targetSelector.addGoal(5, new DefendOthersFromPlayerGoal(this));

      // Only run away to eat, if you'll join the fight again.
      // Else they enter the state of run eat, hide, run eat
      this.goalSelector.addGoal(1, new SetRunningToEatGoal(this, 1.0D));
      this.goalSelector.addGoal(2, new RunToEatGoal(this));

    } else {

      this.goalSelector.addGoal(1, new RunAwayGoal(this));

    }

    if (willDefendBestFriend()) {

      // this.targetSelector.addGoal(3, new BestFriendHurtTargetGoal(this));

    }

    if (getOccupation() == Occupation.CLERIC) {
      this.goalSelector.addGoal(2, new HealPersonAndPlayerGoal(this, 1, 7, 7.0F));
    }
    if (getOccupation() == Occupation.GUARD) {
      this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }
    if (getOccupation() == Occupation.MINER) {
      // Ahead of the work goal: a full pack is worth a trip before more digging.
      this.goalSelector.addGoal(3, new DepositHaulGoal(this));
      this.goalSelector.addGoal(4, new WorkInMineGoal(this));
    }
    if (getOccupation() == Occupation.BUILDER) {
      this.goalSelector.addGoal(4, new WorkOnBuildingGoal(this));
      this.goalSelector.addGoal(8, new WorkOnMakingPathsGoal(this));
    }
    if (getOccupation() == Occupation.LUMBERJACK) {
      this.goalSelector.addGoal(3, new DepositHaulGoal(this));
      this.goalSelector.addGoal(4, new UseBonemealGoal(this, true));
      this.goalSelector.addGoal(4, new WorkOnWoodcuttingGoal(this));
      this.goalSelector.addGoal(8, new ProcessItemGoal(this,
          new ItemStack(Items.STRIPPED_OAK_LOG, 4),
          new ItemStack(Items.OAK_PLANKS, 16),
          8,
          SoundEvents.SMITHING_TABLE_USE));
    }
    if (getOccupation() == Occupation.FARMER) {
      this.goalSelector.addGoal(4, new UseBonemealGoal(this, true));
      this.goalSelector.addGoal(4, new HarvestCropGoal(this, true));
      this.goalSelector.addGoal(6, new TillSoilGoal(this, true));

      this.goalSelector.addGoal(8, new ProcessItemGoal(this,
          new ItemStack(Items.MELON_SLICE, 16),
          new ItemStack(Items.MELON_SEEDS, 16),
          4,
          SoundEvents.PUMPKIN_CARVE));
      this.goalSelector.addGoal(8, new ProcessItemGoal(this,
          new ItemStack(Items.PUMPKIN, 4),
          new ItemStack(Items.PUMPKIN_SEEDS, 16),
          4,
          SoundEvents.PUMPKIN_CARVE));

      for (Item seed : TillSoilGoal.PLANTABLES.keySet()) {
        this.goalSelector.addGoal(8, new ProcessItemGoal(this,
            new ItemStack(seed, 64),
            new ItemStack(Items.BONE_MEAL, 3), // ~2.74
            4,
            SoundEvents.COMPOSTER_FILL_SUCCESS));
      }
    }

    // this.goalSelector.addGoal(3, new FollowHeroGoal(this)); Doesn't work?
    // this.goalSelector.addGoal(4, new WalkBackToCheckPointGoal(this, 0.5D));
    this.goalSelector.addGoal(3, new OpenDoorGoal(this, true) {
      @Override
      public void start() {
        this.mob.swing(InteractionHand.MAIN_HAND);
        super.start();
      }
    });

    this.goalSelector.addGoal(6, new SleepAtNightGoal(this));
    // this.goalSelector.addGoal(6, new RunToClericGoal(this)); Don't need it seems
    this.goalSelector.addGoal(6, new ArmorerRepairPersonArmorGoal(this));

    // Below the work goals, which sit at 4: a goal can only take movement from
    // one with a strictly higher priority number, so at equal priority a
    // villager who happened to start strolling could not be pulled back to
    // work until the stroll ran itself out.
    this.goalSelector.addGoal(5, new StrollAroundVillage(this, 0.5D));
    // this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.5D));
    // Don't need it seems
    this.goalSelector.addGoal(8, new ReturnBackToVillageGoal(this));

    this.goalSelector.addGoal(8, new SearchForItemsGoal(this));

    this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
    this.targetSelector.addGoal(3, (new HurtByTargetGoal(this)).setAlertOthers());
    this.goalSelector.addGoal(8, new UnstuckPersonGoal(this));
    this.targetSelector.addGoal(4, new ResetUniversalAngerTargetGoal<>(this, false));

  }

}
