package com.quzzar.villagelife.entities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.Map.Entry;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.Occupation;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.VillageManager;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
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
import net.minecraft.world.level.Level;

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
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;

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

  private static final EntityDataAccessor<String> FAVORITE_ITEMS = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.STRING); // JSON Object for Map<String, Double>
  private static final EntityDataAccessor<String> RELATIONSHIPS = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.STRING); // JSON Object for Map<UUID, Integer>

  private static final EntityDataAccessor<String> PERSONALITY = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.STRING);
  private static final EntityDataAccessor<String> MARRIAGE_STATUS = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.STRING);
  private static final EntityDataAccessor<String> OCCUPATION = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.STRING);
  private static final EntityDataAccessor<String> GENDER = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.STRING);

  // Virtues
  private static final EntityDataAccessor<Float> VIRTUE_AGGRESSION = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.FLOAT);
  private static final EntityDataAccessor<Float> VIRTUE_PROTECT_SELF = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.FLOAT);
  private static final EntityDataAccessor<Float> VIRTUE_PROTECT_OTHERS = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.FLOAT);
  private static final EntityDataAccessor<Float> VIRTUE_CURIOSITY = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.FLOAT);
  private static final EntityDataAccessor<Float> VIRTUE_DRIVE = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.FLOAT);

  public int callToBedCoolDown = 0;

  public RealPerson(EntityType<? extends Person> type, Level world) {
    super(type, world);

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
        .parseString(new TranslatableComponent(personality.name().toLowerCase() + "."
            + ((isUnisexName) ? "nonbinary" : getGender().name().toLowerCase()) + ".first_names").getString())
        .getAsJsonArray();
    setFirstName(firstNameArray.get(rand.nextInt(firstNameArray.size())).getAsString());

    JsonArray lastNameArray = JsonParser.parseString(new TranslatableComponent("last_names").getString())
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

    // Set angerTime based on Virtues
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
  protected void defineSynchedData() {
    super.defineSynchedData();
    this.entityData.define(FIRST_NAME, "");
    this.entityData.define(LAST_NAME, "");

    this.entityData.define(VILLAGE_UUID, "");

    this.entityData.define(PERSONALITY, "CHEERFUL");
    this.entityData.define(MARRIAGE_STATUS, "SINGLE");
    this.entityData.define(OCCUPATION, "NITWIT");
    this.entityData.define(GENDER, "NONBINARY");

    this.entityData.define(FAVORITE_ITEMS, "");
    this.entityData.define(RELATIONSHIPS, "");

    this.entityData.define(VIRTUE_AGGRESSION, 0F);
    this.entityData.define(VIRTUE_CURIOSITY, 0F);
    this.entityData.define(VIRTUE_DRIVE, 0F);
    this.entityData.define(VIRTUE_PROTECT_OTHERS, 0F);
    this.entityData.define(VIRTUE_PROTECT_SELF, 0F);

  }

  @Override
  public void readAdditionalSaveData(CompoundTag compound) {
    super.readAdditionalSaveData(compound);

    this.entityData.set(FIRST_NAME, compound.getString("FirstName"));
    this.entityData.set(LAST_NAME, compound.getString("LastName"));

    this.entityData.set(VILLAGE_UUID, compound.getString("VillageUUID"));

    this.entityData.set(PERSONALITY, compound.getString("Personality"));
    this.entityData.set(MARRIAGE_STATUS, compound.getString("MarriageStatus"));
    this.entityData.set(OCCUPATION, compound.getString("Occupation"));
    this.entityData.set(GENDER, compound.getString("Gender"));

    this.entityData.set(FAVORITE_ITEMS, compound.getString("FavoriteItems"));
    this.entityData.set(RELATIONSHIPS, compound.getString("Relationships"));

    this.entityData.set(VIRTUE_AGGRESSION, compound.getFloat("VirtueAggression"));
    this.entityData.set(VIRTUE_CURIOSITY, compound.getFloat("VirtueCuriosity"));
    this.entityData.set(VIRTUE_DRIVE, compound.getFloat("VirtueDrive"));
    this.entityData.set(VIRTUE_PROTECT_OTHERS, compound.getFloat("VirtueProtectOthers"));
    this.entityData.set(VIRTUE_PROTECT_SELF, compound.getFloat("VirtueProtectSelf"));

    this.callToBedCoolDown = compound.getInt("CallToBedCooldown");

    reloadState();
  }

  @Override
  public void addAdditionalSaveData(CompoundTag compound) {
    super.addAdditionalSaveData(compound);

    compound.putString("FirstName", this.entityData.get(FIRST_NAME));
    compound.putString("LastName", this.entityData.get(LAST_NAME));

    compound.putString("VillageUUID", this.entityData.get(VILLAGE_UUID));

    compound.putString("Personality", this.entityData.get(PERSONALITY));
    compound.putString("MarriageStatus", this.entityData.get(MARRIAGE_STATUS));
    compound.putString("Occupation", this.entityData.get(OCCUPATION));
    compound.putString("Gender", this.entityData.get(GENDER));

    compound.putString("FavoriteItems", this.entityData.get(FAVORITE_ITEMS));
    compound.putString("Relationships", this.entityData.get(RELATIONSHIPS));

    compound.putFloat("VirtueAggression", this.entityData.get(VIRTUE_AGGRESSION));
    compound.putFloat("VirtueCuriosity", this.entityData.get(VIRTUE_CURIOSITY));
    compound.putFloat("VirtueDrive", this.entityData.get(VIRTUE_DRIVE));
    compound.putFloat("VirtueProtectOthers", this.entityData.get(VIRTUE_PROTECT_OTHERS));
    compound.putFloat("VirtueProtectSelf", this.entityData.get(VIRTUE_PROTECT_SELF));

    compound.putInt("CallToBedCooldown", this.callToBedCoolDown);

  }

  @Override
  public void aiStep() {
    if (this.callToBedCoolDown > 0) {
      --this.callToBedCoolDown;
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

  public Village getVillage() {
    return VillageManager.getVillageFromUUID(this.entityData.get(VILLAGE_UUID));
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
    this.goalSelector.removeAllGoals();
    this.registerGoals();
  }

  public void goToBed(double speed) {
    if (this.callToBedCoolDown > 0) {
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
    switch (virtue) {
      case AGGRESSION:
        this.entityData.set(VIRTUE_AGGRESSION, value);
        break;
      case CURIOSITY:
        this.entityData.set(VIRTUE_CURIOSITY, value);
        break;
      case DRIVE:
        this.entityData.set(VIRTUE_DRIVE, value);
        break;
      case PROTECT_OTHERS:
        this.entityData.set(VIRTUE_PROTECT_OTHERS, value);
        break;
      case PROTECT_SELF:
        this.entityData.set(VIRTUE_PROTECT_SELF, value);
        break;
      default:
        break;
    }
  }

  public float getVirtue(Virtue virtue) {
    switch (virtue) {
      case AGGRESSION:
        return this.entityData.get(VIRTUE_AGGRESSION);
      case CURIOSITY:
        return this.entityData.get(VIRTUE_CURIOSITY);
      case DRIVE:
        return this.entityData.get(VIRTUE_DRIVE);
      case PROTECT_OTHERS:
        return this.entityData.get(VIRTUE_PROTECT_OTHERS);
      case PROTECT_SELF:
        return this.entityData.get(VIRTUE_PROTECT_SELF);
      default:
        return 0F;
    }
  }

  @Override
  protected void populateDefaultEquipmentSlots(DifficultyInstance difficulty) {
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
        this.setItemSlot(EquipmentSlot.OFFHAND, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.HEALING));
        this.setItemSlot(EquipmentSlot.MAINHAND,
            PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), Potions.REGENERATION));
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
    this.populateDefaultEquipmentSlots(null);
  }

  protected void setFavoriteItems(Map<String, Double> favoriteItems) {

    Gson gson = new Gson();
    this.entityData.set(FAVORITE_ITEMS, gson.toJson(favoriteItems));

  }

  public Map<String, Double> getFavoriteItems() {

    JsonObject obj = JsonParser.parseString(this.entityData.get(FAVORITE_ITEMS)).getAsJsonObject();
    Iterator<Entry<String, JsonElement>> iter = obj.entrySet().iterator();

    Map<String, Double> map = new HashMap<>();
    while (iter.hasNext()) {
      Entry<String, JsonElement> entry = iter.next();
      map.put(entry.getKey(), entry.getValue().getAsDouble());
    }

    return map;

  }

  protected void setRelationships(Map<UUID, Integer> relationships) {

    Gson gson = new Gson();
    this.entityData.set(RELATIONSHIPS, gson.toJson(relationships));

  }

  public Map<UUID, Integer> getRelationships() {

    JsonObject obj = JsonParser.parseString(this.entityData.get(RELATIONSHIPS)).getAsJsonObject();
    Iterator<Entry<String, JsonElement>> iter = obj.entrySet().iterator();

    Map<UUID, Integer> map = new HashMap<>();
    while (iter.hasNext()) {
      Entry<String, JsonElement> entry = iter.next();
      map.put(UUID.fromString(entry.getKey()), entry.getValue().getAsInt());
    }

    return map;

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

  public void refreshDisplayName() {
    this.setCustomName(new TextComponent(getFullName()));
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
      this.goalSelector.addGoal(4, new WorkInMineGoal(this));
    }
    if (getOccupation() == Occupation.BUILDER) {
      this.goalSelector.addGoal(4, new WorkOnBuildingGoal(this));
      this.goalSelector.addGoal(8, new WorkOnMakingPathsGoal(this));
    }
    if (getOccupation() == Occupation.LUMBERJACK) {
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

    this.goalSelector.addGoal(4, new StrollAroundVillage(this, 0.5D));
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
