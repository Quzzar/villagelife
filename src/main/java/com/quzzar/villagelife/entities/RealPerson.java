package com.quzzar.villagelife.entities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.UUID;

import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.networking.OpenPersonChatPacket;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.PersonalChest;
import com.quzzar.villagelife.village.Occupation;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.VillageManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.TimeUtil;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.ai.goals.work.ConsolidateStep;
import com.quzzar.villagelife.entities.ai.goals.work.GatherStep;
import com.quzzar.villagelife.entities.ai.goals.work.WorkLoopGoal;
import com.quzzar.villagelife.entities.ai.goals.work.BonemealStep;
import com.quzzar.villagelife.entities.ai.goals.work.ChopStep;
import com.quzzar.villagelife.entities.ai.goals.work.CookStep;
import com.quzzar.villagelife.entities.ai.goals.work.HarvestStep;
import com.quzzar.villagelife.entities.ai.goals.work.TillStep;
import com.quzzar.villagelife.entities.ai.goals.work.HaulStep;
import com.quzzar.villagelife.entities.ai.goals.work.CraftStep;
import com.quzzar.villagelife.entities.ai.goals.work.CullStep;
import com.quzzar.villagelife.entities.ai.goals.work.BlacksmithStep;
import com.quzzar.villagelife.entities.ai.goals.work.MineStep;
import com.quzzar.villagelife.entities.ai.goals.work.BuildStep;
import com.quzzar.villagelife.entities.ai.goals.work.WallStep;
import com.quzzar.villagelife.entities.ai.goals.work.MarketStep;
import com.quzzar.villagelife.entities.ai.goals.work.HealStep;
import com.quzzar.villagelife.entities.ai.goals.work.HuntStep;
import com.quzzar.villagelife.entities.ai.goals.work.FetchStep;
import com.quzzar.villagelife.entities.ai.goals.work.FishStep;
import com.quzzar.villagelife.entities.ai.goals.work.HerdStep;
import com.quzzar.villagelife.entities.ai.goals.work.PathStep;
import com.quzzar.villagelife.entities.ai.goals.work.GradeStep;
import com.quzzar.villagelife.entities.ai.goals.work.ClearBrushStep;
import com.quzzar.villagelife.entities.ai.goals.work.CompostStep;
import com.quzzar.villagelife.entities.ai.goals.work.FetchBonemealStep;
import com.quzzar.villagelife.entities.ai.goals.work.PlantStep;
import com.quzzar.villagelife.entities.ai.goals.work.StashBonemealStep;
import com.quzzar.villagelife.entities.ai.goals.ArmorerRepairPersonArmorGoal;
import com.quzzar.villagelife.entities.ai.goals.ReturnBackToVillageGoal;
import com.quzzar.villagelife.entities.ai.goals.RunAwayGoal;
import com.quzzar.villagelife.entities.ai.goals.PersonEatFoodGoal;
import com.quzzar.villagelife.entities.ai.goals.PersonMeleeGoal;
import com.quzzar.villagelife.entities.ai.goals.RunToEatGoal;
import com.quzzar.villagelife.entities.ai.goals.SearchForItemsGoal;
import com.quzzar.villagelife.entities.ai.goals.SetRunningToEatGoal;
import com.quzzar.villagelife.entities.ai.goals.PanicToBedGoal;
import com.quzzar.villagelife.entities.ai.goals.RaiseShieldGoal;
import com.quzzar.villagelife.entities.ai.goals.RangedBowAttackPassiveGoal;
import com.quzzar.villagelife.entities.ai.goals.RangedCrossbowAttackPassiveGoal;
import com.quzzar.villagelife.entities.ai.goals.NightWatchRestockGoal;
import com.quzzar.villagelife.entities.ai.goals.SleepAtNightGoal;
import com.quzzar.villagelife.entities.ai.goals.SlowToAngerGoal;
import com.quzzar.villagelife.entities.ai.goals.StrollAroundVillage;
import com.quzzar.villagelife.entities.ai.goals.UnstuckPersonGoal;
import com.quzzar.villagelife.other.EquipmentUpgrade;

import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.ResetUniversalAngerTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;

public class RealPerson extends Person {

  // Constants
  private static final int MIN_FAV_ITEMS = 3, MAX_FAV_ITEMS = 7;

  /**
   * How long a noted activity still counts as "just now" for the chat
   * briefing: five minutes. Long enough to outlast any conversation (the chat
   * pauses the work that would refresh it), short enough that yesterday's
   * felling is not today's answer.
   */
  private static final long RECENT_ACTIVITY_TICKS = 6000L;

  /**
   * What this villager was last doing, as they would put it ("felling trees"),
   * and when. Every work loop notes it while it runs and the chat briefing
   * reads it. Remembered rather than asked live because opening a chat pauses
   * the work goal (PauseForConversationGoal), so by the time the villager is
   * asked what they are up to, the honest answer is what they were doing a
   * moment ago. Not persisted: after a reload nobody has done anything yet.
   */
  private String lastActivity;
  private long lastActivityTick;

  /** Records what this villager is doing right now; see {@link #recentActivity}. */
  public void noteActivity(String activity) {
    this.lastActivity = activity;
    this.lastActivityTick = this.level().getGameTime();
  }

  /** The activity noted within the last few minutes, if any. */
  public Optional<String> recentActivity() {
    if (this.lastActivity == null
        || this.level().getGameTime() - this.lastActivityTick > RECENT_ACTIVITY_TICKS) {
      return Optional.empty();
    }
    return Optional.of(this.lastActivity);
  }

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
  private static final EntityDataAccessor<String> TITLE = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.STRING);

  /**
   * Marks a wandering merchant ({@link Occupation#WANDERING_MERCHANT}). Synced
   * because the client renderer needs it: it draws the trader skin pool and
   * drops the nameplate while the merchant is invisible at night. The home
   * village and virtual ledger it trades from are server-only
   * ({@link #sourceVillageUuid}, {@link #wanderingStock}).
   */
  private static final EntityDataAccessor<Boolean> WANDERING_MERCHANT = SynchedEntityData.defineId(RealPerson.class,
      EntityDataSerializers.BOOLEAN);

  /** Torches the miner's bedtime restock tops the pack up to; MineStep spends them lighting the shaft. */
  private static final int TORCH_PACK_TARGET = 16;

  /**
   * Cobblestone the bedtime restock tops the miner's pack up to: floor for a
   * cave the shaft breaks into, and plugs for the veins it pulls. The bedtime
   * stow returns the day's stone to the stores, so the pack starts each morning
   * empty of it, and the restock carries a cave's worth back out from bed,
   * rather than a fetch trip mid-shaft that a cut-off storehouse could strand.
   */
  private static final int COBBLE_PACK_TARGET = 32;

  // Bites a guard carries on watch: rations are topped up to this, best food first.
  private static final int RATION_TARGET = 6;

  /**
   * Torches one lump of coal or charcoal presses into at bedtime. Sticks are
   * deliberately not asked for: shaft lighting should not wait on the forest.
   */
  private static final int TORCHES_PER_COAL = 4;

  /**
   * Bone meal the farmer's bedtime restock tops the pack up to; BonemealStep
   * spends it on whatever is still growing.
   */
  private static final int BONEMEAL_PACK_TARGET = 16;

  /** Bone meal one bone grinds into, as the vanilla recipe has it. */
  private static final int MEAL_PER_BONE = 3;

  /**
   * Saplings the lumberjack's bedtime restock tops the pack up to; PlantStep
   * spends one on the stand each time its tree comes down.
   */
  private static final int SAPLING_PACK_TARGET = 4;

  public int callToBedCoolDown = 0;

  /**
   * A wandering merchant's home village (server-only; empty for everyone
   * else). Kept separate from {@link #VILLAGE_UUID} on purpose: a merchant is
   * not a resident, and a non-empty VILLAGE_UUID would trip the orphan
   * self-heal in {@link #aiStep} and demote it to a wanderer within seconds.
   */
  private String sourceVillageUuid = "";

  /** Ticks a wandering merchant has left before it packs up and leaves, vanilla-trader style. */
  private int merchantDespawnCountdown = 0;

  /**
   * A wandering merchant's virtual stock, seeded from its home village's daily
   * snapshot and drifting as it trades. Never physical: nothing here rides on
   * the merchant's body, so killing it drops no trade loot. Null for everyone
   * who is not a wandering merchant.
   */
  private com.quzzar.villagelife.economy.EconomySnapshot wanderingStock = null;

  // Game day of the last bedtime torch-craft offer. goToBed refires every 100
  // ticks until sleep takes, so without this one night would put the same
  // question to the brain over and over. Not persisted: a reload mid-night at
  // worst asks once more.
  private transient long torchOfferDay = -1;

  // Same guard for the farmer's bedtime bone-grind offer.
  private transient long bonemealOfferDay = -1;

  // Same guard for the bedtime question of what to keep in the chest at home.
  private transient long stashOfferDay = -1;

  // True from that question going out until its answer lands (StashOffer). The
  // pack is held whole meanwhile: stowing it would empty the pockets the brain
  // is still deciding over.
  private transient boolean stashPending;

  // What the villager chose to keep tonight, by kind. It stays in the pack,
  // skipped by the stow, until StashAtHomeGoal sets it down in their own
  // chest; empty when nothing is being kept.
  private transient Set<Item> keepingForHome = Set.of();

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

  // The road (docs/population-and-labor.md): where a wanderer set out from,
  // which way they are walking today, and which day chose that heading. Set at
  // the village edge when someone leaves (or when an orphan finds they have no
  // village), cleared when a village takes them in, and saved so a reload
  // continues the same walk. Walked by RoamGoal, which turns it with each dawn.
  @Nullable
  private BlockPos roamOrigin;
  private double roamHeading;
  private long roamDay;

  /** Puts the person on the road from {@code origin}, walking the given heading (radians, +x east, +z south) for the rest of today. */
  public void beginRoaming(BlockPos origin, double heading) {
    this.roamOrigin = origin;
    this.roamHeading = heading;
    this.roamDay = today();
  }

  /**
   * A roaming wanderer: on the road with no village to go back to. An idle
   * villager is titled Wanderer too, but belongs at a campfire; this is the
   * one who lives off the land (docs/population-and-labor.md, "The road").
   */
  public boolean isRoamingWanderer() {
    return isRoaming() && getVillage() == null;
  }

  /**
   * Each new day on the road picks a fresh heading, aimless by design (Aaron,
   * 2026-09-02): the day's wandering follows it and the next dawn turns it
   * again. The day the road began keeps the heading it began with. True when
   * a heading was just chosen.
   */
  public boolean turnWithTheDay() {
    long today = today();
    if (today == this.roamDay) {
      return false;
    }
    this.roamDay = today;
    this.roamHeading = this.random.nextDouble() * Math.PI * 2;
    Villagelife.LOGGER.info("[road] '{}' wanders {} today", getFullName(), roamHeadingName());
    return true;
  }

  /** The world's day count by its clock: a new day begins at dawn, whatever the clock was set to. */
  private long today() {
    return Math.floorDiv(level().getDayTime(), 24000L);
  }

  /**
   * A wanderer with an empty hand and the makings of a tool in the pack makes
   * one: an axe, which fells and fights alike, out of a log at the recipes'
   * rate ({@link JobTool#makeFromPack}). Nothing is conjured on the road; a
   * pack with no wood in it leaves the hand empty.
   */
  public void toolUpFromPack() {
    if (!getMainHandItem().isEmpty()) {
      return;
    }
    ItemStack made = JobTool.makeFromPack(this, JobTool.AXE);
    if (!made.isEmpty()) {
      setItemSlot(EquipmentSlot.MAINHAND, made);
    }
  }

  /**
   * The job's kit stays with the village: whatever the post put in their hands
   * and on their back (the builder's table, the guard's axe and rations, the
   * armour) is cleared at the edge, so nobody walks off into the distance
   * holding a crafting table (Aaron, 2026-09-01). The pack is theirs and goes
   * with them. Conjured kit vanishes rather than returning to stores, the same
   * way it never drops on death.
   */
  public void leaveKitBehind() {
    for (EquipmentSlot slot : EquipmentSlot.values()) {
      if (slot.getType() != EquipmentSlot.Type.ANIMAL_ARMOR) {
        setItemSlot(slot, ItemStack.EMPTY);
      }
    }
  }

  /** A village has taken them in: the road is behind them. */
  public void endRoaming() {
    this.roamOrigin = null;
    breakCamp();
  }

  // A roaming wanderer's own fire for the night (CampStep): where it stands,
  // saved so a reload or a recruitment mid-night still puts it out.
  @Nullable
  private BlockPos camp;

  @Nullable
  public BlockPos getCamp() {
    return camp;
  }

  public void setCamp(@Nullable BlockPos camp) {
    this.camp = camp;
  }

  /**
   * The camp's fire is put out and left where it stood, nothing of it back in
   * the pack (Aaron, 2026-09-02): a wanderer's fire is for the night, not a
   * possession. A fire in a chunk that is not loaded stays as it is; the
   * memory of it is dropped regardless.
   */
  public void breakCamp() {
    if (camp == null) {
      return;
    }
    if (level() instanceof net.minecraft.server.level.ServerLevel level && level.hasChunkAt(camp)
        && level.getBlockState(camp).is(net.minecraft.world.level.block.Blocks.CAMPFIRE)) {
      level.removeBlock(camp, false);
    }
    camp = null;
  }

  public boolean isRoaming() {
    return roamOrigin != null;
  }

  @Nullable
  public BlockPos getRoamOrigin() {
    return roamOrigin;
  }

  public double getRoamHeading() {
    return roamHeading;
  }

  public void turnRoamHeading(double delta) {
    this.roamHeading = (this.roamHeading + delta) % (Math.PI * 2);
  }

  /** The compass point the road heading points at, for a log line. */
  public String roamHeadingName() {
    return Utils.compassPoint(roamHeading);
  }

  /**
   * Steps out of the loaded world and onto the road beyond the horizon: the
   * whole person is remembered in the server's {@link com.quzzar.villagelife.village.WandererPool}
   * so a growing village anywhere can draw them back in, and the entity is
   * discarded. Only ever called on a loaded, village-less wanderer, so there is
   * exactly one copy of them at any moment.
   */
  public void crossHorizon() {
    if (!(level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
      return;
    }
    boolean remembered = VillageManager.get(serverLevel).getWanderers().bank(this, serverLevel.getGameTime());
    Villagelife.LOGGER.info("'{}' passed beyond the horizon heading {}{}", getFullName(), roamHeadingName(),
        remembered ? " and may yet turn up at another village" : " and is gone for good");
    discard();
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
    // Draw the given name from the villager's OWN gender so it matches their
    // gendered skin: a bearded man never lands a woman's name. Nonbinary
    // villagers draw from the nonbinary list, which is the androgynous pool.
    JsonArray firstNameArray = JsonParser
        .parseString(Component.translatable(personality.name().toLowerCase() + "."
            + getGender().name().toLowerCase() + ".first_names").getString())
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
    builder.define(OCCUPATION, "WANDERER");
    builder.define(GENDER, "NONBINARY");
    builder.define(TITLE, "");
    builder.define(WANDERING_MERCHANT, false);

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
    setStringIfPresent(compound, "Title", TITLE);
    setStringIfPresent(compound, "Gender", GENDER);

    this.callToBedCoolDown = compound.getInt("CallToBedCooldown");
    this.camp = compound.contains("Camp") ? BlockPos.of(compound.getLong("Camp")) : null;

    this.entityData.set(WANDERING_MERCHANT, compound.getBoolean("WanderingMerchant"));
    this.sourceVillageUuid = compound.getString("SourceVillageUUID");
    this.merchantDespawnCountdown = compound.getInt("MerchantDespawnCountdown");
    this.wanderingStock = compound.contains("WanderingStock")
        ? com.quzzar.villagelife.economy.EconomySnapshot.load(compound.getCompound("WanderingStock"))
        : null;

    if (compound.contains("RoamOrigin")) {
      this.roamOrigin = BlockPos.of(compound.getLong("RoamOrigin"));
      this.roamHeading = compound.getDouble("RoamHeading");
      this.roamDay = compound.getLong("RoamDay");
    } else {
      this.roamOrigin = null;
    }

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
    compound.putString("Title", this.entityData.get(TITLE));
    compound.putString("Gender", this.entityData.get(GENDER));

    compound.putInt("CallToBedCooldown", this.callToBedCoolDown);
    if (camp != null) {
      compound.putLong("Camp", camp.asLong());
    }

    compound.putBoolean("WanderingMerchant", this.entityData.get(WANDERING_MERCHANT));
    if (!this.sourceVillageUuid.isEmpty()) {
      compound.putString("SourceVillageUUID", this.sourceVillageUuid);
    }
    compound.putInt("MerchantDespawnCountdown", this.merchantDespawnCountdown);
    if (this.wanderingStock != null) {
      compound.put("WanderingStock", this.wanderingStock.save());
    }

    if (roamOrigin != null) {
      compound.putLong("RoamOrigin", roamOrigin.asLong());
      compound.putDouble("RoamHeading", roamHeading);
      compound.putLong("RoamDay", roamDay);
    }
  }

  @Override
  public void aiStep() {
    if (this.callToBedCoolDown > 0) {
      --this.callToBedCoolDown;
    }
    // Nights are counted here, for every villager, whatever goal held them.
    // The count used to live in the sleep goal's own tick, so a villager some
    // stronger goal kept from ever bedding down never accrued a night, and the
    // last-resort recovery never fired for exactly the wedged villagers it was
    // written for. A night counts as slept when they lay down in it, or stood
    // watch through it (noteSlept).
    if (!this.level().isClientSide) {
      boolean night = this.level().isNight();
      if (this.wasNight && !night && !this.sleptTonight && !isWanderingMerchant()) {
        setDaysSinceSleep(getDaysSinceSleep() + 1);
      }
      if (!this.wasNight && night) {
        this.sleptTonight = false;
      }
      this.wasNight = night;
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
        // No village, no job: a wanderer, and one with nowhere to be, so they
        // take to the road from right here in whatever direction the dice say.
        setOccupation(Occupation.WANDERER);
        beginRoaming(blockPosition(), this.random.nextDouble() * Math.PI * 2);
        reloadState();
        Villagelife.LOGGER.debug("[{}] was orphaned from their village and set out {}",
            this.getName().getString(), roamHeadingName());
      }
    }
    // A worker whose job tool is in their pack instead of their hand cannot
    // work, since the work steps read the hand (a miner will not swing at stone
    // his HELD tool would not drop). Off the hot path, a few times a minute by
    // day, a worker with an empty hand draws their tool back out, or fetches or
    // makes one, so a villager who put their tool away or gave it off does not
    // stand idle beside work they own the tool for.
    if (!this.level().isClientSide && this.tickCount % 200 == 71 && !this.level().isNight()) {
      tendJobTool();
    }
    super.aiStep();

    // A wandering merchant keeps its own clock: it counts down to its departure
    // and, like the vanilla trader it replaces, slips out of sight at night.
    // Last, so that a despawn ends the tick cleanly.
    if (!this.level().isClientSide && isWanderingMerchant()) {
      tickWanderingMerchant();
    }
  }

  /**
   * Make sure a worker is actually holding their job tool, so the work steps
   * (which read the hand) can run. The self-help cascade a worker with an empty
   * hand walks on their own: draw the tool they already own out of their pack;
   * else take a spare the village stores can hand over; else make one from the
   * stores' cobblestone and sticks. A worker who can do none of these has the
   * shortage logged (JobTool.replace) and keeps to an idle rather than standing
   * empty-handed beside work they cannot do (Aaron, 2026-09-02: the miner whose
   * pickaxe was in his pack, not his hand, so he never mined).
   */
  public void tendJobTool() {
    JobTool tool = JobTool.of(getOccupation());
    if (tool == null || tool.inHand(this) || getVillage() == null) {
      return;
    }
    BlockPos depositTo = LocationManager.getJobLocation(this);
    // A swap leaves the last job's tool in hand (a quartermaster's writable_book
    // on someone now farming). The kit and equipBestPossibleGear fill only an
    // EMPTY hand, so the new tool never lands until the wrong one is set down:
    // the reason a job swap "did not close out" and the worker stood idle with
    // the wrong thing in hand. Deposit it to the village and empty the hand.
    ItemStack wrong = getMainHandItem();
    if (!wrong.isEmpty()) {
      getVillage().placeItemStackIntoVillage(wrong, this, depositTo);
      setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }
    if (drawJobToolFromPack(tool)) {
      return;
    }
    equipBestPossibleGear(tool.kind(), null, getOccupation() == Occupation.GUARD, depositTo);
    if (tool.inHand(this)) {
      return;
    }
    JobTool.replace(this, tool, depositTo);
  }

  /**
   * Move a job tool the worker already owns from their pack into the main hand,
   * the hand's own item taking the freed slot. The tool is MOVED, never copied:
   * held items are single-source, and a copy duplicates on death. True when one
   * was drawn.
   */
  private boolean drawJobToolFromPack(JobTool tool) {
    for (int slot = 0; slot < this.personMainInv.getContainerSize(); slot++) {
      ItemStack stack = this.personMainInv.getItem(slot);
      if (!stack.isEmpty() && tool.kind().isInstance(stack.getItem())) {
        ItemStack held = this.getMainHandItem();
        this.setItemSlot(EquipmentSlot.MAINHAND, stack);
        this.personMainInv.setItem(slot, held);
        return true;
      }
    }
    return false;
  }

  /**
   * A wandering merchant's private clock (server side). It counts down to the
   * hour it packs up and leaves (the vanilla trader despawns after a couple of
   * days), carrying nothing tradeable since its ledger was only ever virtual;
   * and it drinks itself invisible at night and visible again at dawn, the
   * trader's signature disappearing act. The nameplate is dropped client-side
   * while the effect holds ({@code PersonRenderer}), so an invisible merchant
   * is not given away by a floating name.
   */
  private void tickWanderingMerchant() {
    if (this.merchantDespawnCountdown > 0 && --this.merchantDespawnCountdown <= 0) {
      this.discard();
      return;
    }
    boolean night = this.level().isNight();
    boolean hidden = this.hasEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY);
    if (night && !hidden) {
      this.addEffect(new net.minecraft.world.effect.MobEffectInstance(
          net.minecraft.world.effect.MobEffects.INVISIBILITY, -1, 0, false, false));
    } else if (!night && hidden) {
      this.removeEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY);
    }
  }

  @Override
  public void die(DamageSource source) {
    super.die(source);
    breakCamp();
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

  /**
   * The construction lead is the builder a building project owns: the village's
   * first builder, or its only one. The other builders keep to their own duty
   * while a project runs (docs/worker-loops.md).
   */
  public boolean isConstructionLead() {
    Village village = getVillage();
    return village == null || village.builderRank(getUUID()) == 0;
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

  /**
   * Weds this villager to another (docs/marriage.md): both become
   * {@link MarriageStatus#MARRIED} and both take the one {@code marriedSurname}
   * their household has settled on. That name is the couple's own choice, made
   * in the naming deliberation the brain convenes ({@code MarriageNaming}), and
   * falls back to a hyphenation only when their talk settles nothing; either
   * way, deciding it is {@code MarriageService}'s, not this method's. Marriage is
   * symmetric, so one call settles both people. Who is married to whom is the
   * pair edge's to hold ({@code RelationshipPair.married}); this writes only the
   * person-level projection. The caller owns the guard that neither is already
   * wed and flags the edge married.
   */
  public void marry(RealPerson spouse, String marriedSurname) {
    this.setLastName(marriedSurname);
    spouse.setLastName(marriedSurname);
    this.setMarriageStatus(MarriageStatus.MARRIED);
    spouse.setMarriageStatus(MarriageStatus.MARRIED);
    this.refreshDisplayName();
    spouse.refreshDisplayName();
  }

  protected void setGender(Gender gender) {
    this.entityData.set(GENDER, gender.name());
  }

  public void setOccupation(Occupation occupation) {
    this.entityData.set(OCCUPATION, occupation.name());
  }

  /** Whether this person is a wandering merchant (config "Wandering merchant"). */
  public boolean isWanderingMerchant() {
    return this.entityData.get(WANDERING_MERCHANT);
  }

  public void setWanderingMerchant(boolean value) {
    this.entityData.set(WANDERING_MERCHANT, value);
  }

  public String getSourceVillageUuid() {
    return this.sourceVillageUuid;
  }

  public void setSourceVillageUuid(String uuid) {
    this.sourceVillageUuid = uuid == null ? "" : uuid;
  }

  /**
   * The home village a wandering merchant was sent from, resolved live (server,
   * main thread only, like {@link #getVillage}); null for a resident, off the
   * main thread, or if that village has since been deleted. Used only for the
   * player's live standing with it; the goods come from {@link #wanderingStock}.
   */
  public Village getSourceVillage() {
    if (this.sourceVillageUuid.isEmpty()
        || !(this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
      return null;
    }
    if (!serverLevel.getServer().isSameThread()) {
      return null;
    }
    return VillageManager.get(serverLevel).getVillage(this.sourceVillageUuid);
  }

  public com.quzzar.villagelife.economy.EconomySnapshot getWanderingStock() {
    return this.wanderingStock;
  }

  public void setWanderingStock(com.quzzar.villagelife.economy.EconomySnapshot stock) {
    this.wanderingStock = stock;
  }

  public int getMerchantDespawnCountdown() {
    return this.merchantDespawnCountdown;
  }

  public void setMerchantDespawnCountdown(int ticks) {
    this.merchantDespawnCountdown = ticks;
  }

  public String getTitle() {
    return this.entityData.get(TITLE);
  }

  public void setTitle(String title) {
    this.entityData.set(TITLE, title == null ? "" : title);
  }

  /**
   * The line shown under the villager's name: their honorific title if they have
   * one, otherwise their occupation. Titles are not granted anywhere yet (the
   * slot is reserved, docs/genetics-and-attributes.md), so today this reads as
   * the occupation for everyone.
   */
  public String getRoleLabel() {
    String title = getTitle();
    if (!title.isBlank()) {
      return title;
    }
    if (isWanderingMerchant()) {
      return "Wandering Merchant";
    }
    return Utils.capitalize(getOccupation().name().toLowerCase());
  }

  /**
   * Re-registers the goals for the occupation as it now stands. The hands are
   * not touched: the starting kit is issued once, when a job is first taken
   * ({@link #issueStartingKit}). A state reload used to re-run it, which handed
   * every guard and lumberjack a fresh stone axe on every chunk load, over
   * whatever they held, and refilled a hand that had just given its axe away.
   */
  public void reloadState() {
    // Running goals are stopped on the way out, or they hold the villager's
    // legs for good (clearGoals). The target selector is rebuilt the same way;
    // it used to gain another copy of every target goal on each reload.
    clearGoals(this.goalSelector);
    clearGoals(this.targetSelector);
    this.registerGoals();
  }

  /**
   * Removes every goal from the selector, stopping the running ones first.
   * The selector's own removeAllGoals drops the wrappers without stopping
   * them, and a goal that is never stopped keeps its control flags locked in
   * the selector for good: nothing at its priority or below can take the
   * flag again, and the villager stands wherever the reload caught them.
   * That was the wanderer frozen at the village edge (the walk to the exit,
   * priority 1, was still running when the edge reloaded the goals), and the
   * same trap waits for any worker reassigned mid-task.
   */
  private static void clearGoals(net.minecraft.world.entity.ai.goal.GoalSelector selector) {
    for (net.minecraft.world.entity.ai.goal.WrappedGoal wrapped : List.copyOf(selector.getAvailableGoals())) {
      selector.removeGoal(wrapped.getGoal());
    }
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

    if (this.stashPending) {
      return; // the brain has the pack's contents; settleStash stows once it answers
    }
    if (maybeOfferStash()) {
      return;
    }
    stowPackAndRestock();
  }

  /**
   * Once a night, a villager with a chest of their own is asked what, if
   * anything, they would rather keep than return to the stores (StashOffer).
   * True when the question went out: the stow then waits for the answer.
   */
  private boolean maybeOfferStash() {
    long day = this.level().getDayTime() / 24000L;
    // Night only: a daytime panic runs goToBed too, and that is no time to be
    // asked what to keep.
    if (this.stashOfferDay == day || !this.level().isNight() || this.personMainInv.isEmpty()) {
      return false;
    }
    BlockPos chest = PersonalChest.of(this);
    if (chest == null) {
      return false;
    }
    this.stashOfferDay = day;
    this.stashPending = true;
    StashOffer.offer(this, chest);
    return true;
  }

  /**
   * The bedtime chest question answered, or given up on: note what to keep, if
   * anything, then the rest of the pack goes to the stores as it always did.
   */
  void settleStash(Set<Item> keep) {
    this.stashPending = false;
    Set<Item> carried = new LinkedHashSet<>();
    for (Item item : keep) {
      if (this.personMainInv.countItem(item) > 0) {
        carried.add(item);
      }
    }
    this.keepingForHome = Collections.unmodifiableSet(carried);
    if (!this.keepingForHome.isEmpty() && this.isSleeping()) {
      // The answer came after they had lain down. A sleeper at night is
      // immobile (Person.isImmobile) and an immobile entity ticks no goals, so
      // the walk home could only have started at dawn, and did, live: a
      // lumberjack carried the day's planks to her chest the next morning. Up
      // again for a minute: the stash goal outranks sleep and hands the bed
      // back once the chest is done.
      this.stopSleeping();
    }
    if (this.getVillage() != null) {
      stowPackAndRestock();
    }
  }

  /** The kinds of item this villager is carrying home for their own chest tonight; empty when none. */
  public Set<Item> keepingForHome() {
    return this.keepingForHome;
  }

  /** The kept items are put away, or the trip was given up: nothing is held back any more. */
  public void doneKeeping() {
    this.keepingForHome = Set.of();
  }

  /**
   * The bedtime stow-and-restock without the bed. Jobs that stand watch through
   * the night (Occupation.sleepsAtNight() false) never run goToBed, but bedtime
   * is when the village hands out gear, rations and upgrades, so the night
   * watch runs the same routine at their post (NightWatchRestockGoal). Shares
   * goToBed's cooldown, so a bell ring and the nightly cadence cannot
   * double-fire it.
   */
  public void restockForNightWatch() {
    if (this.callToBedCoolDown > 0 || this.getVillage() == null) {
      return;
    }
    this.callToBedCoolDown = 100;
    stowPackAndRestock();
  }

  /** The shared bedtime routine: empty the pack into stores, re-gear for the job. */
  private void stowPackAndRestock() {
    BlockPos depositToLoc = LocationManager.getJobLocation(this);
    // If no depositToLoc, set to null to indicate no container preference
    if (depositToLoc.equals(BlockPos.ZERO)) {
      depositToLoc = null;
    }

    // Put items in main inventory away, all but what tonight's chest question
    // held back: that stays in the pack for the walk home (StashAtHomeGoal).
    List<ItemStack> items = this.clearMainInventory();
    List<ItemStack> kept = new ArrayList<>();
    for (ItemStack item : items) {
      if (this.keepingForHome.contains(item.getItem())) {
        kept.add(item);
        continue;
      }
      boolean addedItem = this.getVillage().placeItemStackIntoVillage(item, this, depositToLoc);
      if (!addedItem) {
        // Storage is full and the item stays in the pack rather than dropping on
        // the ground silently. Flag the village so it knows to build more storage,
        // the same signal ConsolidateStep raises on storehouse overflow. (Not a
        // NoResourceBookkeepingEvent: that reports a shortage, and a surplus we
        // cannot shelve is the opposite of one.)
        this.getVillage().setStorageStrained(true);
      }
    }
    this.addItems(kept);

    // Re-gear for the job, from real stock only. Set down first whatever is in
    // hand that is not a tool of THIS job's kind: a guard's old sword, or the
    // last job's tool left over from a swap (a quartermaster's book on someone
    // now farming). The guard's axe is both weapon and daytime work tool, so it
    // is kept; anything else goes back to the village. Without this the kit and
    // equipBestPossibleGear, which fill only an empty hand, never land the new
    // tool and the worker stands idle holding the wrong thing (the job swap that
    // "did not close out", docs/population-and-labor.md). Then the best tool of
    // the job's kind the village can spare (armour too, for guards), and a tool
    // still missing after that is made from cobblestone and sticks out of the
    // stores, or its absence logged (JobTool.replace). Nothing is conjured: a
    // guard once gave their axe away in conversation and had a new one in hand
    // five seconds later.
    JobTool tool = JobTool.of(getOccupation());
    ItemStack held = this.getItemBySlot(EquipmentSlot.MAINHAND);
    if (!held.isEmpty() && tool != null && !tool.kind().isInstance(held.getItem())) {
      this.getVillage().placeItemStackIntoVillage(held, this, depositToLoc);
      this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }
    equipBestPossibleGear(tool == null ? null : tool.kind(), null,
        getOccupation() == Occupation.GUARD, depositToLoc);
    if (tool != null && !tool.inHand(this)) {
      JobTool.replace(this, tool, depositToLoc);
    }
    if (getOccupation() == Occupation.GUARD) {
      restockRations(depositToLoc);
    }

    // Take 1 sponge if builder or miner // TODO, change to if needed
    if (getOccupation() == Occupation.BUILDER || getOccupation() == Occupation.MINER) {

      ItemStack item = gatherForWork(new ItemStack(Items.SPONGE, 1), depositToLoc);
      this.addItems(Arrays.asList(item));

    }

    // Restock torches for the miner to light the shaft as they dig. Physical:
    // taken from the miner's own chest at home and then village stores, so the
    // mine goes dark only when neither has torches to spare. MineStep places
    // them from the pack a few blocks apart. Buckets ride along for the same
    // reason: a miner who breaks into water or lava seals the leak with one and
    // keeps digging (MineStep), so a flooded shaft reads as a village with no
    // bucket to spare rather than a dead end. One is all it needs - the bucket
    // is a tool it never fills or spends.
    if (getOccupation() == Occupation.MINER) {
      ItemStack torches = gatherForWork(new ItemStack(Items.TORCH, TORCH_PACK_TARGET), depositToLoc);
      this.addItems(Arrays.asList(torches));
      ItemStack buckets = gatherForWork(new ItemStack(Items.BUCKET, 1), depositToLoc);
      this.addItems(Arrays.asList(buckets));
      // Cobblestone for the day's flooring and vein plugs, topped up from the
      // stores like the torches. It is spent deep in the shaft, far from any
      // chest and, as Ember Hill showed, sometimes a chest the shaft cannot even
      // walk to; carrying the day's worth from bed avoids a fetch trip that a
      // cut-off storehouse would strand. A part-load keeps what it has.
      ItemStack cobble = gatherForWork(new ItemStack(Items.COBBLESTONE,
          COBBLE_PACK_TARGET - this.personMainInv.countItem(Items.COBBLESTONE)), depositToLoc);
      this.addItems(Arrays.asList(cobble));
      maybeCraftTorchesFromCoal(depositToLoc);
    }

    // Grab bonemeal
    if (getOccupation() == Occupation.LUMBERJACK || getOccupation() == Occupation.FARMER) {

      ItemStack item = gatherForWork(new ItemStack(Items.BONE_MEAL, BONEMEAL_PACK_TARGET), depositToLoc);
      this.addItems(Arrays.asList(item));

    }

    // Saplings for the stand, any kind. The felled canopy's saplings ride to
    // the lodge's barrel with the rest of the haul, and this is how they come
    // back out; a pack that already holds enough keeps working from it.
    if (getOccupation() == Occupation.LUMBERJACK) {
      restockSaplings(depositToLoc);
    }

    // Take seeds
    if (getOccupation() == Occupation.FARMER) {

      ArrayList<ItemStack> gatheredSeeds = new ArrayList<>();
      for (Item seed : TillStep.PLANTABLES.keySet()) {
        ItemStack gatheredSeed = gatherForWork(new ItemStack(seed, 8), depositToLoc);
        gatheredSeeds.add(gatheredSeed);
      }

      this.addItems(gatheredSeeds);

      maybeCraftBonemealFromBones(depositToLoc);

    }

  }

  /**
   * A stack for tomorrow's work, taken from this villager's own chest at home
   * first and from village stores for whatever that leaves short. What a
   * villager held back for themselves at an earlier bedtime is theirs to work
   * with, so the restock reaches into it before it asks the village: the miner
   * once kept the village's only bucket at home and the next miner stood at a
   * flooded shaft while it sat in the barrel. Returns the want with the count
   * actually found, possibly zero, like the village gather it wraps.
   */
  public ItemStack gatherForWork(ItemStack want, BlockPos depositToLoc) {
    ItemStack fromHome = takeFromHomeChest(want);
    int short_ = want.getCount() - fromHome.getCount();
    if (short_ <= 0) {
      return fromHome;
    }
    ItemStack fromStores = this.getVillage().gatherItemStackFromVillage(want.copyWithCount(short_), depositToLoc);
    if (fromHome.isEmpty()) {
      return fromStores;
    }
    fromHome.grow(fromStores.getCount());
    return fromHome;
  }

  /**
   * Up to {@code want}'s count of its item out of this villager's own chest at
   * home (PersonalChest), or none when they have no chest, its chunk is not
   * resident, or it holds none of the item. Never pages a chunk in to look.
   */
  private ItemStack takeFromHomeChest(ItemStack want) {
    BlockPos chest = PersonalChest.of(this);
    Container container = chest == null ? null : PersonalChest.container(this, chest);
    if (container == null) {
      return want.copyWithCount(0);
    }
    int taken = 0;
    for (int slot = 0; slot < container.getContainerSize() && taken < want.getCount(); slot++) {
      if (container.getItem(slot).is(want.getItem())) {
        taken += container.removeItem(slot, want.getCount() - taken).getCount();
      }
    }
    if (taken > 0) {
      container.setChanged();
      Villagelife.LOGGER.info("'{}' took {} {} from their chest at home for tomorrow's work",
          getFullName(), taken, want.getItem().getDescription().getString().toLowerCase(java.util.Locale.ROOT));
    }
    return want.copyWithCount(taken);
  }

  /**
   * Tops the guard's rations up to {@link #RATION_TARGET} bites from the
   * village's own food, best first: what heals most per bite (the rule in
   * {@link Person#eatFood}) is taken before what heals less, and when the best
   * runs short the next kind follows, so a village that has bread and apples
   * sends its guard out with the bread. The off hand carries the first kind
   * taken; any further kind rides in the pack, where a bite is found just the
   * same. Nothing is conjured: a village with no food at all logs the shortage
   * and the guard stands watch hungry.
   */
  private void restockRations(BlockPos depositToLoc) {
    int wanted = RATION_TARGET - this.mealsCarried();
    if (wanted <= 0) {
      return;
    }
    List<Map.Entry<Item, Integer>> larder = new ArrayList<>();
    for (Map.Entry<Item, Integer> entry : this.getVillage().stockTally().entrySet()) {
      if (entry.getValue() > 0 && Person.isMeal(new ItemStack(entry.getKey()))) {
        larder.add(entry);
      }
    }
    larder.sort(Comparator.comparingDouble((Map.Entry<Item, Integer> entry) -> healPerBite(entry.getKey())).reversed());
    for (Map.Entry<Item, Integer> entry : larder) {
      if (wanted <= 0) {
        break;
      }
      ItemStack taken = gatherForWork(new ItemStack(entry.getKey(), Math.min(wanted, entry.getValue())), depositToLoc);
      if (taken.isEmpty()) {
        continue; // the tally was a moment stale; the next kind may still be there
      }
      wanted -= taken.getCount();
      if (this.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) {
        this.setItemSlot(EquipmentSlot.OFFHAND, taken);
      } else {
        this.addItems(List.of(taken));
      }
    }
    if (this.mealsCarried() == 0) {
      // A guard starting the night watch with nothing to eat is a shortage
      // worth remembering. The event records an item, and nothing reads which;
      // bread stands for "a meal".
      this.getVillage().logEvent(
          new com.quzzar.villagelife.village.bookkeeping.NoResourceBookkeepingEvent(Items.BREAD, wanted));
    }
  }

  /** What one bite of this heals, by {@link Person#eatFood}'s rule; nothing for a drink with no food value. */
  private float healPerBite(Item item) {
    FoodProperties food = new ItemStack(item).getFoodProperties(this);
    return food == null ? 0.0F : food.nutrition() * 2.0F + food.saturation() / 2.0F;
  }

  /**
   * Offers the miner's brain a bedtime torch craft when the restock above left
   * the pack short and the stores hold coal or charcoal. The rules decide the
   * legal move and its size (a pack top-up, nothing more); CraftOffer carries
   * the ask, and the model only takes it or leaves it, in character
   * (docs/llm-brain.md). Once per night: goToBed refires until sleep takes.
   */
  private void maybeCraftTorchesFromCoal(BlockPos depositToLoc) {
    long day = this.level().getDayTime() / 24000L;
    if (this.torchOfferDay == day) {
      return;
    }
    int torchesHeld = this.personMainInv.countItem(Items.TORCH);
    int torchesWanted = TORCH_PACK_TARGET - torchesHeld;
    if (torchesWanted <= 0) {
      return;
    }
    int coalHeld = com.quzzar.villagelife.economy.VillagePricing.countHeld(this.getVillage(), Items.COAL)
        + com.quzzar.villagelife.economy.VillagePricing.countHeld(this.getVillage(), Items.CHARCOAL);
    if (coalHeld <= 0) {
      return;
    }
    this.torchOfferDay = day;

    int coalToSpend = Math.min(coalHeld, Math.ceilDiv(torchesWanted, TORCHES_PER_COAL));
    CraftOffer.Press press = new CraftOffer.Press(List.of(Items.COAL, Items.CHARCOAL), Items.TORCH,
        TORCHES_PER_COAL);
    String situation = CraftOffer.identityLead(this)
        + "You are turning in for the night carrying " + torchesHeld + " of the " + TORCH_PACK_TARGET
        + " torches you like to take down the shaft. The village stores hold " + coalHeld
        + " coal, and a lump presses into " + TORCHES_PER_COAL
        + " torches. Decide whether to spend " + coalToSpend
        + " coal on light for tomorrow's dig, and give your reason in a few words.";
    CraftOffer.offer(this, depositToLoc, press, coalToSpend, situation);
  }

  /**
   * Tops the pack up to {@link #SAPLING_PACK_TARGET} saplings from village
   * stores, taking whatever kinds are there: the stand grows any of them, and a
   * lodge whose first oak is down plants a spruce if spruce is what the
   * woodland gave. Only saplings that grow into a tree count
   * ({@link PlantStep#isSapling}).
   */
  private void restockSaplings(BlockPos depositToLoc) {
    int wanted = SAPLING_PACK_TARGET - PlantStep.saplingsHeld(this);
    for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.SAPLINGS)) {
      if (wanted <= 0) {
        return;
      }
      if (!PlantStep.isSapling(holder.value())) {
        continue;
      }
      ItemStack got = this.getVillage().gatherItemStackFromVillage(new ItemStack(holder.value(), wanted),
          depositToLoc);
      if (!got.isEmpty()) {
        wanted -= got.getCount();
        this.addItems(Arrays.asList(got));
      }
    }
  }

  /**
   * Offers the farmer's brain a bedtime bone grind when the restock above left
   * the pack short and the stores hold bones - the same press as the miner's
   * torch craft, through the same {@link CraftOffer} (silence crafts, only an
   * explicit "leave it" keeps the stores; docs/llm-brain.md). The meal lands in
   * the pack for tomorrow's crops, and the shelf steps move any surplus into
   * the farm's barrel once the field stops asking for it.
   */
  private void maybeCraftBonemealFromBones(BlockPos depositToLoc) {
    long day = this.level().getDayTime() / 24000L;
    if (this.bonemealOfferDay == day) {
      return;
    }
    int mealHeld = this.personMainInv.countItem(Items.BONE_MEAL);
    int mealWanted = BONEMEAL_PACK_TARGET - mealHeld;
    if (mealWanted <= 0) {
      return;
    }
    int bonesHeld = com.quzzar.villagelife.economy.VillagePricing.countHeld(this.getVillage(), Items.BONE);
    if (bonesHeld <= 0) {
      return;
    }
    this.bonemealOfferDay = day;

    int bonesToSpend = Math.min(bonesHeld, Math.ceilDiv(mealWanted, MEAL_PER_BONE));
    CraftOffer.Press press = new CraftOffer.Press(List.of(Items.BONE), Items.BONE_MEAL, MEAL_PER_BONE);
    String situation = CraftOffer.identityLead(this)
        + "You are turning in for the night carrying " + mealHeld + " of the " + BONEMEAL_PACK_TARGET
        + " bone meal you like to keep for the crops. The village stores hold " + bonesHeld
        + " bones, and one presses into " + MEAL_PER_BONE
        + " bone meal. Decide whether to press " + bonesToSpend
        + " bones for tomorrow's fields, and give your reason in a few words.";
    CraftOffer.offer(this, depositToLoc, press, bonesToSpend, situation);
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

  // Runtime-only: whether the last tick was night, and whether this night has
  // been slept (or watched) through. See aiStep.
  private boolean wasNight;
  private boolean sleptTonight;

  /** This night counts as rest: the sleeper lay down, or the watch stood it. */
  public void noteSlept() {
    setDaysSinceSleep(0);
    this.sleptTonight = true;
  }

  /**
   * The last resort for a villager who could not get home for nights on end:
   * set them down at the head of their bed, or, with no bed, where the idle
   * gather (the standing spot beside the campfire, which is the town center's
   * own block when the fire is out; see Village.getGatheringPoint). A villager
   * with no village at all is left where they are; the road is their home.
   * Logged, because a teleport is exactly the kind of thing that should be
   * visible in the record when a villager is found somewhere surprising.
   */
  public void tpToRest(String why) {
    Village village = getVillage();
    if (village == null) {
      return;
    }
    BlockPos bed = LocationManager.getBedLocation(this);
    boolean hasBed = !bed.equals(BlockPos.ZERO);
    BlockPos target = hasBed ? bed.above() : village.getGatheringPoint();
    String where = hasBed ? "their bed" : "the campfire";
    if (target.equals(BlockPos.ZERO)) {
      return; // no town center either: nowhere to bring them
    }
    this.getNavigation().stop();
    this.moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, this.getYRot(), this.getXRot());
    Villagelife.LOGGER.info("[unstuck] {} {}; brought to {} at {}",
        this.getName().getString(), why, where, target.toShortString());
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
        // An axe doubles as a serviceable weapon and the guard's occasional
        // woodland tool. It stays basic until the village can make better;
        // armour waits on the leather chain. No rations on taking the post:
        // food is never conjured. The first bedtime restock draws apples from
        // the village stores, and a village with none logs the shortage rather
        // than inventing a meal.
        kitIfEmpty(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_AXE));
        break;
      case LUMBERJACK:
        kitIfEmpty(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_AXE));
        break;
      case BLACKSMITH:
        kitIfEmpty(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_INGOT));
        break;
      case BUILDER:
        kitIfEmpty(EquipmentSlot.MAINHAND, new ItemStack(Items.CRAFTING_TABLE));
        break;
      case CLERIC:
        ItemStack healingPotion = new ItemStack(Items.POTION);
        healingPotion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.HEALING));
        kitIfEmpty(EquipmentSlot.OFFHAND, healingPotion);
        ItemStack regenPotion = new ItemStack(Items.SPLASH_POTION);
        regenPotion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.REGENERATION));
        kitIfEmpty(EquipmentSlot.MAINHAND, regenPotion);
        break;
      case FARMER:
        // A basic hoe, like the lumberjack's stone axe and miner's stone
        // pickaxe: kept basic until the village can make better. An iron hoe is
        // the blacksmith's to forge later (BlacksmithStep), not a starting tool
        // that fakes iron the village has not yet mined.
        kitIfEmpty(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_HOE));
        break;
      case LEADER:
        kitIfEmpty(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
        break;
      case LIBRARIAN:
        kitIfEmpty(EquipmentSlot.MAINHAND, new ItemStack(Items.BOOK));
        break;
      case HUNTER:
        // A plain bow, like the guard's stone axe: enough to work with until
        // the bedtime gear pass finds better. No arrows come with it - the
        // plain fallback is conjured per shot (Person.getProjectile), and only
        // special arrows someone hands them are real. A bow that wears out is
        // replaced at bedtime from stores or made from sticks and string
        // (JobTool), never re-granted.
        kitIfEmpty(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        break;
      case MINER:
        kitIfEmpty(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
        break;
      case QUARTERMASTER:
        // The quartermaster keeps the village's stores; a writable book reads as
        // the ledger they are forever taking count in.
        kitIfEmpty(EquipmentSlot.MAINHAND, new ItemStack(Items.WRITABLE_BOOK));
        break;
      default:
        break;
    }
  }

  /**
   * The bare starting kit, issued once, when a job is first taken
   * (JobClaiming.startJob): a stone tool for the trades that work with one, a
   * token of the trade for the rest. Only bare slots are filled, so a villager
   * still carrying the pickaxe from an earlier posting keeps it rather than
   * getting a second. After this nothing is conjured: a tool given away, lost or
   * worn out is replaced at bedtime from the village stores or made from real
   * materials, and a village with neither logs the shortage (JobTool).
   */
  public void issueStartingKit() {
    this.populateDefaultEquipmentSlots(this.random, null);
  }

  /** A starting-kit slot: filled only when bare, so nothing already held is replaced. */
  private void kitIfEmpty(EquipmentSlot slot, ItemStack stack) {
    if (this.getItemBySlot(slot).isEmpty()) {
      this.setItemSlot(slot, stack);
    }
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
    // Somebody the village has stopped speaking to gets nothing to open. This
    // is the cost of standing being real: it is felt on arrival, before you can
    // explain yourself (docs/economy.md, #64).
    if (getVillage() != null && level() instanceof net.minecraft.server.level.ServerLevel serverLevel
        && com.quzzar.villagelife.wrongdoing.Standing.tierOf(getVillage(), serverLevel, player.getUUID())
            .atLeastAsBadAs(com.quzzar.villagelife.wrongdoing.Standing.Tier.SHUNNED)) {
      player.displayClientMessage(Component.literal(
          getFullName() + " turns away from you."), true);
      return;
    }

    String detail = isWanderingMerchant() ? "Wandering Merchant"
        : Utils.capitalize(getOccupation().name().toLowerCase());
    if (getVillage() != null) {
      detail = detail + " of " + getVillage().getName();
    } else if (isWanderingMerchant() && !getVillageName().isBlank()) {
      // A wandering merchant is "of" its home village though no resident of it.
      detail = detail + " of " + getVillageName();
    }
    com.quzzar.villagelife.chat.PersonChatDispatcher.markOpen(this, player);

    // A conversation is a session bounded by the Minecraft day. Re-opening on the
    // same day continues where it left off; a new day is a fresh conversation: the
    // prior session was already summarized into a memory when it last closed
    // (PersonChatDispatcher.summarizeSession), so the transcript is cleared here and
    // the screen opens blank for the villager to greet anew from that ready memory,
    // with no wait on a summary and no long transcript to lose the thread of.
    List<com.quzzar.villagelife.chat.ChatHistoryData.Exchange> priorExchanges = getData(
        VillagelifeAttachments.CHAT_HISTORY.get()).with(player.getUUID());
    boolean freshSession = getData(VillagelifeAttachments.CHAT_HISTORY.get())
        .staleFor(player.getUUID(), level().getDayTime());
    if (freshSession) {
      setData(VillagelifeAttachments.CHAT_HISTORY.get(),
          getData(VillagelifeAttachments.CHAT_HISTORY.get()).clearedFor(player.getUUID()));
    }
    List<OpenPersonChatPacket.ExchangeLine> scrollback = freshSession ? List.of()
        : priorExchanges.stream()
            .map(e -> new OpenPersonChatPacket.ExchangeLine(e.playerLine(), e.reply()))
            .toList();
    // A merchant at a staffed market gets a Trade tab; everyone else is chat
    // alone. A wandering merchant carries its own market, so it trades wherever
    // it stands as long as its ledger holds something.
    boolean canTrade = isWanderingMerchant()
        ? getWanderingStock() != null && !getWanderingStock().isEmpty()
        : getOccupation() == Occupation.MERCHANT && getVillage() != null
            && com.quzzar.villagelife.economy.Treasury.tradeBlocker(getVillage(),
                (net.minecraft.server.level.ServerLevel) level()).isEmpty();
    // The menu is what opens the screen now, because the screen is a real
    // container; the conversation follows separately, since a container's extra
    // data is written once and a chat log keeps growing.
    final String header = getFullName();
    final String subtitle = detail;
    final boolean trades = canTrade;
    player.openMenu(new net.minecraft.world.SimpleMenuProvider(
        (containerId, inventory, opener) -> new com.quzzar.villagelife.menu.MarketMenu(
            containerId, inventory, getId(), header, subtitle, trades),
        Component.literal(header)),
        buffer -> {
          buffer.writeVarInt(getId());
          buffer.writeUtf(header);
          buffer.writeUtf(subtitle);
          buffer.writeBoolean(trades);
        });
    PacketDistributor.sendToPlayer(player,
        new OpenPersonChatPacket(this.getId(), getFullName(), detail, canTrade, scrollback));
    if (scrollback.isEmpty()) {
      // Blank screen (a first-ever chat, or a new day's fresh session): the
      // villager opens the conversation themselves.
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
        case WANDERER:
          // The campfire reservoir is the settlement's last line of defence:
          // every idle resident stands their ground when the camp is attacked.
          i += 1.0;
          break;
        case GUARD:
          i += 0.9;
          break;
        case HUNTER:
          // The village's own archer does not run from a zombie: all but the
          // meekest hunters stand and shoot (docs/worker-loops.md).
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
      case WANDERER:
        // Idle residents do not go hunting, but they do actively clear threats
        // from the campfire area; the target predicate below supplies the tether.
        i += 1.0;
        break;
      case GUARD:
        i += 0.8;
        break;
      case HUNTER:
        // Below the guard, above everyone else: killing is the trade, so most
        // hunters take on nearby monsters, but they are not the watch.
        i += 0.6;
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

  /** Whether a hostile is close enough for an idle resident to defend the camp from it. */
  private boolean isCampfireThreat(LivingEntity mob) {
    if (!(mob instanceof Enemy) || mob instanceof Creeper || mob instanceof EnderMan) {
      return false;
    }
    if (!getOccupation().isIdle()) {
      return true;
    }
    Village village = getVillage();
    if (village == null) {
      return false;
    }
    BlockPos campfire = village.getGatheringPoint();
    return campfire != null && !campfire.equals(BlockPos.ZERO)
        && campfire.closerToCenterThan(mob.position(), 16.0D);
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

  /**
   * Whether this villager personally counts the player an enemy: their own
   * opinion of them, not the village's, at or below the configured grudge
   * line (docs/relationships.md). Struck often enough, a villager crosses it;
   * fighters answer it by attacking, everyone else by keeping their distance.
   */
  /** How long a quarrel picked in conversation lasts before the villager lets it go. */
  private static final int QUARREL_TICKS = 20 * 60;

  private UUID quarrelTarget;
  private long quarrelUntil;

  /**
   * Comes to blows with whoever they were talking to, player or villager, over
   * what was said: the villager's own decision, made in conversation ("fight"
   * in the reply, docs/villager-conversations.md). A fighter draws what they
   * carry; anyone else uses their fists. A quarrel is short and never saved: a
   * minute of it, then the villager lets it go unless given fresh cause. Unlike
   * a grudge it needs no low opinion first; the words were enough. A villager
   * struck by someone who picked a quarrel with them answers in kind
   * ({@link #hurt}), so a fight takes two for its whole minute.
   */
  public void pickFightWith(LivingEntity other) {
    quarrelTarget = other.getUUID();
    quarrelUntil = level().getGameTime() + QUARREL_TICKS;
    setTarget(other);
    Villagelife.LOGGER.info("'{}' picks a fight with {}", getFullName(), other.getName().getString());
  }

  /** Whether this villager is in a quarrel they picked with this one. */
  public boolean isQuarrellingWith(LivingEntity other) {
    return quarrelTarget != null && quarrelTarget.equals(other.getUUID()) && level().getGameTime() < quarrelUntil;
  }

  @Override
  public boolean hurt(DamageSource source, float amount) {
    boolean hurt = super.hurt(source, amount);
    // Blows from a villager who picked a quarrel with this one are answered in
    // kind: a fight takes two, and a farmer punched by a farmer punches back
    // rather than standing there or running for bed.
    if (hurt && source.getEntity() instanceof RealPerson attacker && attacker.isQuarrellingWith(this)
        && !isQuarrellingWith(attacker)) {
      pickFightWith(attacker);
    }
    return hurt;
  }

  /** Whether this villager is in a quarrel they picked with anyone. */
  public boolean isQuarrelling() {
    return quarrelTarget != null && level().getGameTime() < quarrelUntil;
  }

  public boolean holdsGrudgeAgainst(Player player) {
    return com.quzzar.villagelife.relationships.OpinionService.opinionOf(this, player.getUUID())
        <= com.quzzar.villagelife.configuration.VillagelifeConfig.GrudgeAttackBelow;
  }

  /** Standing verdicts are memoized briefly: goal predicates poll every few ticks. */
  private UUID threatVerdictPlayer;
  private long threatVerdictExpires;
  private boolean threatVerdict;

  /**
   * Whether this villager's village counts the player an outright threat, the
   * HOSTILE rung of standing (docs/economy.md). Answering means averaging the
   * whole roster, so the verdict is cached for a few seconds per player.
   */
  public boolean villageConsidersThreat(Player player) {
    if (getVillage() == null
        || !(level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
      return false;
    }
    long now = level().getGameTime();
    if (player.getUUID().equals(threatVerdictPlayer) && now < threatVerdictExpires) {
      return threatVerdict;
    }
    threatVerdictPlayer = player.getUUID();
    threatVerdictExpires = now + 100L;
    threatVerdict = com.quzzar.villagelife.wrongdoing.Standing
        .tierOf(getVillage(), serverLevel, player.getUUID())
        == com.quzzar.villagelife.wrongdoing.Standing.Tier.HOSTILE;
    return threatVerdict;
  }

  /**
   * The goal set of a wandering merchant: keep near where it arrived, amble
   * about, watch passers-by, flee danger, and stay out of the water. This is
   * the vanilla trader's roaming without any village-worker machinery. Trading
   * is driven by right-click ({@link #openChat}), not a goal; its departure and
   * night-invisibility live in {@link #tickWanderingMerchant}.
   */
  private void registerWanderingMerchantGoals() {
    this.setImmobile(false);
    this.goalSelector.addGoal(0, new FloatGoal(this));
    this.goalSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.PanicGoal(this, 0.55D));
    this.goalSelector.addGoal(2, new net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal(this, 0.5D));
    this.goalSelector.addGoal(3, new net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal(this, 0.45D));
    this.goalSelector.addGoal(8, new net.minecraft.world.entity.ai.goal.LookAtPlayerGoal(this, Player.class, 8.0F));
    this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
  }

  @Override
  protected void registerGoals() {

    // A wandering merchant runs its own small goal set (above) and none of the
    // village-worker machinery, so it branches out before any of that.
    if (isWanderingMerchant()) {
      registerWanderingMerchantGoals();
      return;
    }

    Villagelife.LOGGER.debug("REGISTERING GOALS FOR " + getUUID());

    // Just in case some goals made them immobile,
    this.setImmobile(false);

    this.goalSelector.addGoal(0, new FloatGoal(this));
    this.goalSelector.addGoal(0, new PersonEatFoodGoal(this));
    // A hurt villager with nothing to eat goes and gets some, from the stores
    // or their own chest, before any work; the eating goal takes over on arrival.
    this.goalSelector.addGoal(1, new com.quzzar.villagelife.entities.ai.goals.FetchFoodWhenHurtGoal(this));
    this.goalSelector.addGoal(1, new com.quzzar.villagelife.entities.ai.goals.VillageTravelGoal(this));
    this.goalSelector.addGoal(3, new com.quzzar.villagelife.entities.ai.goals.PauseForConversationGoal(this));

    if (doesCombat()) {

      this.goalSelector.addGoal(2, new RangedCrossbowAttackPassiveGoal<>(this, 1.0D, 8.0F));
      this.goalSelector.addGoal(2, new RangedBowAttackPassiveGoal<>(this, 0.5D, 20, 15.0F));
      this.goalSelector.addGoal(2, new PersonMeleeGoal(this, 0.8D, true));

      // A personal grudge is answered personally: a player who struck this
      // villager past the grudge line is THIS villager's enemy on sight,
      // whatever the rest of the village thinks of them.
      this.targetSelector.addGoal(3,
          new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
              (target) -> target instanceof Player player && holdsGrudgeAgainst(player)));

      this.goalSelector.addGoal(1, new RaiseShieldGoal(this));

    } else {

      this.goalSelector.addGoal(1, new PanicToBedGoal(this, 0.6D));

      this.goalSelector.addGoal(5, new AvoidEntityGoal<>(this, Mob.class, 12.0F, 0.5D, 0.5D, (mob) -> {
        return mob instanceof Enemy;
      }));

      // The unarmed answer to a grudge or a village threat is distance: the
      // same shape as avoiding monsters, because that is what a player who
      // beats villagers has made of themselves here.
      this.goalSelector.addGoal(5, new AvoidEntityGoal<>(this, Player.class, 12.0F, 0.5D, 0.5D,
          (target) -> target instanceof Player player
              && (holdsGrudgeAgainst(player) || villageConsidersThreat(player))));

      // Fists, and only in a quarrel this villager picked: a monster that hurts
      // them still sets a target through SlowToAngerGoal, and that is answered
      // with distance as before, not a punch.
      this.goalSelector.addGoal(2, new PersonMeleeGoal(this, 0.8D, true) {
        @Override
        public boolean canUse() {
          LivingEntity target = RealPerson.this.getTarget();
          return target != null && isQuarrellingWith(target) && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
          LivingEntity target = RealPerson.this.getTarget();
          return target != null && isQuarrellingWith(target) && super.canContinueToUse();
        }
      });

    }

    // A quarrel picked in conversation is answered by anyone, armed or not, for
    // as long as it lasts (pickFightWith), against a player or a fellow
    // villager alike. Above the grudge and the village's verdict: this one the
    // villager chose.
    this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
        (target) -> target instanceof Player player && isQuarrellingWith(player)));
    this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, RealPerson.class, 10, true, false,
        (target) -> target instanceof RealPerson other && isQuarrellingWith(other)));

    if (willInitiateCombat()) {// Actively seeks out combat

      this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Mob.class, 5, true, true, (mob) -> {
        return isCampfireThreat(mob);
      }));

      // The bottom rung of standing: a village whose people loathe you sets its
      // fighters on you (#64). Nothing about this is permanent — opinions of
      // outsiders fade — so it is a state you can leave by leaving.
      this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
          (target) -> target instanceof Player player && villageConsidersThreat(player)));

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
      this.goalSelector.addGoal(2, new WorkLoopGoal<>(this, new HealStep(1, 7, 7.0F)));
    }
    if (getOccupation() == Occupation.GUARD) {
      // Guarding always wins on priority. In a quiet spell, a guard very
      // occasionally clears one natural tree around their post using the exact
      // same chopping step as the lumberjack. The reach matches the lumberjack's
      // because this is a fresh camp's only wood until it can afford a lodge: at
      // six blocks the guard found no tree at all, and a camp sat on its first
      // lodge goal for four hours, nine logs short the whole time.
      //
      // Level with strolling and visiting (5), and registered ahead of them: a
      // goal only takes movement from one with a strictly higher number, so a
      // pick already under way is walked out and cut rather than dropped for a
      // stroll, while a stroll already under way still keeps a new pick from
      // starting. At 8 the stroll roll, once every twelve seconds or so, threw
      // away a third of the guard's rare picks between choosing a tree and
      // reaching it, and the log showed the choice with neither a fell nor a
      // miss after it.
      //
      // One roll in four every ten seconds: a fresh camp's guard is its only
      // axe until the lodge stands, and at one in twenty a tree fell every
      // fifteen minutes if the dice were kind (Aaron, 2026-09-01: too rare).
      //
      this.goalSelector.addGoal(5, new WorkLoopGoal<>(this,
          new ChopStep(12, 0.25F, 200)));
      // The loose watch is the guard's real default, sitting at the visiting
      // priority but AFTER the chop and ahead of the chat: the founding chop
      // above still wins the flag whenever a tree is near (it is a fresh camp's
      // only wood, docs/economy.md), and otherwise the guard walks the village
      // keeping an eye on it rather than loitering in conversation. Without it a
      // work-less guard fell to seeking chats, fixed on its best-liked
      // neighbour, and parked on a worker it kept pulling off the job (Aaron,
      // 2026-09-02: the miner and guard just standing together). A chat fits the
      // pauses between the watch's legs.
      this.goalSelector.addGoal(5,
          new com.quzzar.villagelife.entities.ai.goals.GuardPatrolGoal(this));
      this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }
    if (getOccupation() == Occupation.MINER) {
      // Ahead of the work goal: a full pack is worth a trip before more digging.
      this.goalSelector.addGoal(3, new WorkLoopGoal<>(this, new HaulStep()));
      this.goalSelector.addGoal(4, new WorkLoopGoal<>(this, new MineStep()));
    }
    if (getOccupation() == Occupation.BUILDER) {
      // A lone builder does it all in one order: gathering the recipe, raising
      // it (the two gate on the project's phase and never run at once), the
      // wall, then between builds the ground graded to walkable slopes before
      // the paths across it are worn in. With more than one builder the posts
      // divide the duties (docs/worker-loops.md): the lead builds, the second
      // wears paths, the third grades, each taking the others' work only when
      // its own is done. Without this a village's one builder never reached
      // the paths, because grading a hillside does not end.
      int rank = getVillage() == null ? 0 : getVillage().builderRank(getUUID());
      int[] order = switch (rank) { // gather, build, wall, grade, path
        case 1 -> new int[] {7, 7, 4, 8, 4};
        case 2 -> new int[] {8, 8, 4, 4, 7};
        default -> new int[] {3, 4, 4, 7, 8};
      };
      this.goalSelector.addGoal(order[0], new WorkLoopGoal<>(this, new GatherStep()));
      this.goalSelector.addGoal(order[1], new WorkLoopGoal<>(this, new BuildStep()));
      this.goalSelector.addGoal(order[2], new WorkLoopGoal<>(this, new WallStep()));
      this.goalSelector.addGoal(order[3], new WorkLoopGoal<>(this, new GradeStep()));
      this.goalSelector.addGoal(order[4], new WorkLoopGoal<>(this, new PathStep()));
    }
    if (getOccupation() == Occupation.MERCHANT) {
      this.goalSelector.addGoal(4, new WorkLoopGoal<>(this, new MarketStep()));
    }
    if (getOccupation() == Occupation.QUARTERMASTER) {
      this.goalSelector.addGoal(4, new WorkLoopGoal<>(this, new ConsolidateStep()));
    }
    if (getOccupation() == Occupation.LUMBERJACK) {
      this.goalSelector.addGoal(3, new WorkLoopGoal<>(this, new HaulStep()));
      // The stand's own loop: fell the tree at the station, set a sapling from
      // the pack on the stump, feed it while there is bone meal, fell it again.
      // The three cannot want the station at once (a log, a bare stump, a
      // sapling), so they share one priority.
      this.goalSelector.addGoal(4, new WorkLoopGoal<>(this,
          new BonemealStep(true)));
      this.goalSelector.addGoal(4, new WorkLoopGoal<>(this,
          new ChopStep()));
      this.goalSelector.addGoal(4, new WorkLoopGoal<>(this,
          new PlantStep()));
      // The planted stand above is the reliable job. This lower-priority pass
      // also clears nearby woodland, about three times as often and farther out
      // than a guard (a roll of two in five every five seconds, twenty blocks
      // out); level with strolling for the reason given at the guard's.
      this.goalSelector.addGoal(5, new WorkLoopGoal<>(this,
          new ChopStep(20, 0.4F, 100)));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.STRIPPED_OAK_LOG, 4),
          new ItemStack(Items.OAK_PLANKS, 16),
          8,
          SoundEvents.SMITHING_TABLE_USE)));
    }
    if (getOccupation() == Occupation.MASON) {
      // The mason does not dig. The mine brings cobblestone up; this is the
      // chain that turns it into something a village can build with, which is
      // why stone_bricks are the most-used crafted block in the catalogue.
      this.goalSelector.addGoal(3, new WorkLoopGoal<>(this, new HaulStep()));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.COBBLESTONE, 8),
          new ItemStack(Items.STONE, 8),
          8,
          SoundEvents.FURNACE_FIRE_CRACKLE)));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.STONE, 4),
          new ItemStack(Items.STONE_BRICKS, 4),
          6,
          SoundEvents.UI_STONECUTTER_TAKE_RESULT)));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.SAND, 4),
          new ItemStack(Items.SANDSTONE, 1),
          6,
          SoundEvents.UI_STONECUTTER_TAKE_RESULT)));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.SANDSTONE, 4),
          new ItemStack(Items.CUT_SANDSTONE, 4),
          6,
          SoundEvents.UI_STONECUTTER_TAKE_RESULT)));
    }
    if (getOccupation() == Occupation.FARMER) {
      // Ahead of the work goals, like every other gatherer: a full pack is worth
      // a trip to a chest before harvesting more, or the crops overflow the pack
      // and drop on the ground. The farmer was the only gatherer missing this.
      this.goalSelector.addGoal(3, new WorkLoopGoal<>(this, new HaulStep()));
      // Before the feeder: a farmer with an empty pack but a stocked barrel
      // walks over and refills rather than watching the crops sulk.
      this.goalSelector.addGoal(4, new WorkLoopGoal<>(this, new FetchBonemealStep()));
      this.goalSelector.addGoal(4, new WorkLoopGoal<>(this,
          new BonemealStep(true)));
      this.goalSelector.addGoal(4, new WorkLoopGoal<>(this, new HarvestStep(true)));
      this.goalSelector.addGoal(6, new WorkLoopGoal<>(this, new TillStep(true)));

      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.MELON_SLICE, 16),
          new ItemStack(Items.MELON_SEEDS, 16),
          4,
          SoundEvents.PUMPKIN_CARVE)));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.PUMPKIN, 4),
          new ItemStack(Items.PUMPKIN_SEEDS, 16),
          4,
          SoundEvents.PUMPKIN_CARVE)));

      // The idle chain, in working order: finish converting what is carried,
      // shelve what nothing wants, and only then pull more brush. Together they
      // are the farmer's answer to a field that is all still growing. The
      // composter also eats surplus sowing seeds (CompostStep), which is what
      // replaced the abstract seeds-to-bone-meal craft that used to sit here:
      // the farmer has a real composter at the station, so it uses that.
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CompostStep()));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new StashBonemealStep()));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new ClearBrushStep()));
    }
    if (getOccupation() == Occupation.BLACKSMITH) {
      // The forge's SMELTING half: raw iron the mine brings up becomes ingots, the stock
      // everything iron is priced in (docs/buildings.md, mine -> ORES -> blacksmith). Tools
      // and REPAIR are the blacksmith's other grants; REPAIR already has its own goal below.
      this.goalSelector.addGoal(3, new WorkLoopGoal<>(this, new HaulStep()));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.RAW_IRON, 1),
          new ItemStack(Items.IRON_INGOT, 1),
          8,
          SoundEvents.FURNACE_FIRE_CRACKLE)));
      // The forge's CRAFTING half: turn those ingots into the gear the village
      // lacks - buckets first (the miner cannot make its own), then basic iron
      // tools. Priority 7, so needed gear takes the forge before more raw smelting.
      this.goalSelector.addGoal(7, new WorkLoopGoal<>(this, new BlacksmithStep()));
    }
    if (getOccupation() == Occupation.TANNER) {
      // Hides off the hunter and the pasture become worked leather -- the armoury
      // (docs/buildings.md: LEATHER -> tannery -> armoury). The TANNER occupation stays
      // even as the category is renamed tannery -> workshop.
      this.goalSelector.addGoal(3, new WorkLoopGoal<>(this, new HaulStep()));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.LEATHER, 8),
          new ItemStack(Items.LEATHER_CHESTPLATE, 1),
          8,
          SoundEvents.SMITHING_TABLE_USE)));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.LEATHER, 4),
          new ItemStack(Items.LEATHER_BOOTS, 1),
          8,
          SoundEvents.SMITHING_TABLE_USE)));
    }
    if (getOccupation() == Occupation.BAKER) {
      // The bakery absorbed the mill, so the baker grinds their own grain into bread
      // (building-spec.md:449; farm -> GRAIN -> bakery -> BREAD).
      this.goalSelector.addGoal(3, new WorkLoopGoal<>(this, new HaulStep()));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.WHEAT, 3),
          new ItemStack(Items.BREAD, 1),
          6,
          SoundEvents.COMPOSTER_FILL_SUCCESS)));
    }
    if (getOccupation() == Occupation.BUTCHER) {
      // Raw meat off the pasture, lodge and fishery is cooked into keeping food
      // (docs/buildings.md: MEAT -> butchery). One recipe per meat the village can bring in.
      this.goalSelector.addGoal(3, new WorkLoopGoal<>(this, new HaulStep()));
      // The pen is thinned by the butcher, not the herder: a kind the herder has
      // bred to the cap is slaughtered down to what the pen keeps, and its hide
      // carried to the chest (CullStep). Above the cooking, so the cull comes
      // first and the meat it leaves in the pack is what gets cooked.
      this.goalSelector.addGoal(4, new WorkLoopGoal<>(this, new CullStep()));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.BEEF, 4), new ItemStack(Items.COOKED_BEEF, 4),
          6, SoundEvents.FURNACE_FIRE_CRACKLE)));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.PORKCHOP, 4), new ItemStack(Items.COOKED_PORKCHOP, 4),
          6, SoundEvents.FURNACE_FIRE_CRACKLE)));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.CHICKEN, 4), new ItemStack(Items.COOKED_CHICKEN, 4),
          6, SoundEvents.FURNACE_FIRE_CRACKLE)));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.MUTTON, 4), new ItemStack(Items.COOKED_MUTTON, 4),
          6, SoundEvents.FURNACE_FIRE_CRACKLE)));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.COD, 4), new ItemStack(Items.COOKED_COD, 4),
          6, SoundEvents.FURNACE_FIRE_CRACKLE)));
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CraftStep(
          new ItemStack(Items.SALMON, 4), new ItemStack(Items.COOKED_SALMON, 4),
          6, SoundEvents.FURNACE_FIRE_CRACKLE)));
    }
    if (getOccupation() == Occupation.HUNTER) {
      // Shoots game on the ground around the lodge (HuntStep: bow work, special
      // arrows loosed first); the kill's meat and leather fall for pickup, and a
      // full pack goes home ahead of the next quarry.
      this.goalSelector.addGoal(3, new WorkLoopGoal<>(this, new HaulStep()));
      this.goalSelector.addGoal(4, new WorkLoopGoal<>(this, new HuntStep()));
    }
    if (getOccupation() == Occupation.FISHER) {
      this.goalSelector.addGoal(3, new WorkLoopGoal<>(this, new HaulStep()));
      this.goalSelector.addGoal(4, new WorkLoopGoal<>(this, new FishStep()));
    }
    if (getOccupation() == Occupation.HERDER) {
      // Shears wool and breeds the herd rather than culling it -- the pasture's
      // renewable half, distinct from the hunter's. Haul carries the sheared wool
      // home; without it the CLOTH output strands in the herder's own pack.
      // Fetch carries the breeding wheat OUT: an empty pocket refills from a
      // chest before the round, so no grain is conjured across the village.
      this.goalSelector.addGoal(3, new WorkLoopGoal<>(this, new HaulStep()));
      this.goalSelector.addGoal(4, new WorkLoopGoal<>(this, new FetchStep(Items.WHEAT, 8)));
      this.goalSelector.addGoal(4, new WorkLoopGoal<>(this, new HerdStep()));
    }
    if (getOccupation().isIdle()) {
      // Idle hands at the fire: cook raw food the village holds into keeping
      // food, the campfire-model twin of the farmer's composter chain and an
      // early-camp bridge until a butchery exists (docs/population-and-labor.md).
      // Lowest priority, so defence, eating and sleep all pull them off it.
      this.goalSelector.addGoal(8, new WorkLoopGoal<>(this, new CookStep()));
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
    // Fence gates too, since the route now runs through them
    // (PersonPathNavigation): opened on the bump, closed a second later.
    this.goalSelector.addGoal(3, new com.quzzar.villagelife.entities.ai.goals.OpenFenceGateGoal(this));

    // The road: a roaming wanderer lives off the land (each step's select says
    // whether this villager is one) at the work loops' priority, so game and
    // timber met on the way outrank the walk, and walks their day's heading
    // the rest of the time. The walk sits below fleeing (5) so monsters still
    // scatter them, and above strolling only because StrollAroundVillage
    // yields while roaming.
    this.goalSelector.addGoal(4, new WorkLoopGoal<>(this,
        new com.quzzar.villagelife.entities.ai.goals.work.ForageHuntStep()));
    this.goalSelector.addGoal(4, new WorkLoopGoal<>(this,
        new com.quzzar.villagelife.entities.ai.goals.work.ForageChopStep()));
    this.goalSelector.addGoal(4, new WorkLoopGoal<>(this,
        new com.quzzar.villagelife.entities.ai.goals.work.CampStep()));
    this.goalSelector.addGoal(6, new com.quzzar.villagelife.entities.ai.goals.RoamGoal(this));

    // Safe to decide at registration: every occupation change goes through
    // setOccupation + reloadState, which rebuilds all goals.
    if (getOccupation().sleepsAtNight()) {
      // Ahead of sleep: what the bedtime chest question held back is set down
      // at home first, and only then does the bed take over.
      this.goalSelector.addGoal(5, new com.quzzar.villagelife.entities.ai.goals.StashAtHomeGoal(this));
      this.goalSelector.addGoal(6, new SleepAtNightGoal(this));
    } else {
      // The watch stands all night. They keep their bed (the JobClaiming
      // housing gate is untouched); only the sleeping is skipped, and the
      // bedtime stow-and-restock runs at their post instead.
      this.goalSelector.addGoal(6, new NightWatchRestockGoal(this));
    }
    // this.goalSelector.addGoal(6, new RunToClericGoal(this)); Don't need it seems
    this.goalSelector.addGoal(6, new ArmorerRepairPersonArmorGoal(this));

    // Below the work goals, which sit at 4: a goal can only take movement from
    // one with a strictly higher priority number, so at equal priority a
    // villager who happened to start strolling could not be pulled back to
    // work until the stroll ran itself out.
    //
    // Seeking a chat is registered before strolling at the same priority, so
    // when both fire on the same idle moment the villager goes visiting; a
    // stroll already underway finishes first, which is fine pacing.
    this.goalSelector.addGoal(5, new com.quzzar.villagelife.entities.ai.goals.SeekConversationGoal(this, 0.5D));
    this.goalSelector.addGoal(5, new StrollAroundVillage(this, 0.5D));
    // this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.5D));
    // Don't need it seems
    this.goalSelector.addGoal(8, new ReturnBackToVillageGoal(this));

    this.goalSelector.addGoal(8, new SearchForItemsGoal(this));

    this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
    this.targetSelector.addGoal(3, new SlowToAngerGoal(this));
    this.goalSelector.addGoal(8, new UnstuckPersonGoal(this));
    this.targetSelector.addGoal(4, new ResetUniversalAngerTargetGoal<>(this, false));

  }

}
