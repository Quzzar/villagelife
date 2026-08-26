package com.quzzar.villagelife.village;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.relationships.RelationshipPair;
import com.quzzar.villagelife.relationships.RelationshipService;
import com.quzzar.villagelife.village.bookkeeping.BookkeepingEvent;
import com.quzzar.villagelife.village.buildings.BuildProgress;
import com.quzzar.villagelife.village.buildings.Building;
import com.quzzar.villagelife.village.buildings.BuildingInfo;
import com.quzzar.villagelife.village.buildings.Buildings;
import com.quzzar.villagelife.village.buildings.InstantBuildStructure;
import com.quzzar.villagelife.village.buildings.LocationValidator;
import com.quzzar.villagelife.village.buildings.StructureInProgress;
import com.quzzar.villagelife.village.buildings.UrbanPlanner;
import com.quzzar.villagelife.persona.PersonaSpawner;
import com.quzzar.villagelife.village.tiers.VillageTier;
import com.quzzar.villagelife.village.tiers.VillageTiers;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public class Village {

  public static final Codec<Village> CODEC = RecordCodecBuilder.create(inst -> inst.group(
      Codec.STRING.fieldOf("id").forGetter(Village::getID),
      Codec.STRING.fieldOf("name").forGetter(Village::getName),
      Codec.INT.fieldOf("time").forGetter(v -> v.time),
      VillageBrain.CODEC.fieldOf("brain").forGetter(v -> v.brain),
      UUIDUtil.CODEC.optionalFieldOf("town_center").forGetter(v -> Optional.ofNullable(v.townCenterUUID)),
      Codec.LONG.listOf().fieldOf("claim_grid").forGetter(v -> List.copyOf(v.claimGrid)),
      StructureInProgress.CODEC.optionalFieldOf("current_project").forGetter(v -> Optional.ofNullable(v.currentProject)),
      UUIDUtil.CODEC.listOf().fieldOf("people").forGetter(v -> List.copyOf(v.people)),
      Building.CODEC.listOf().fieldOf("buildings").forGetter(v -> List.copyOf(v.buildings.values())),
      Codec.unboundedMap(UUIDUtil.STRING_CODEC, JobAssignment.CODEC).fieldOf("job_assignments").forGetter(v -> Map.copyOf(v.jobAssignments)),
      Codec.unboundedMap(UUIDUtil.STRING_CODEC, BedAssignment.CODEC).fieldOf("bed_assignments").forGetter(v -> Map.copyOf(v.bedAssignments)),
      JobAssignment.CODEC.listOf().fieldOf("unassigned_jobs").forGetter(v -> List.copyOf(v.unassignedJobs)),
      BedAssignment.CODEC.listOf().fieldOf("unassigned_beds").forGetter(v -> List.copyOf(v.unassignedBeds)),
      Codec.STRING.optionalFieldOf("tier", VillageTiers.DEFAULT_TIER_ID).forGetter(v -> v.tierId),
      PendingTraveler.CODEC.listOf().optionalFieldOf("pending_arrivals", List.of()).forGetter(v -> List.copyOf(v.pendingArrivals)),
      PendingTraveler.CODEC.listOf().optionalFieldOf("pending_departures", List.of()).forGetter(v -> List.copyOf(v.pendingDepartures))
  ).apply(inst, Village::fromCodec));

  /** Someone mid-walk: arriving at the campfire or leaving for the village edge. */
  public record PendingTraveler(UUID personId, long deadline, long target) {
    public static final Codec<PendingTraveler> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        UUIDUtil.CODEC.fieldOf("person").forGetter(PendingTraveler::personId),
        Codec.LONG.fieldOf("deadline").forGetter(PendingTraveler::deadline),
        Codec.LONG.fieldOf("target").forGetter(PendingTraveler::target)
    ).apply(inst, PendingTraveler::new));
  }

  private static Village fromCodec(String id, String name, int time, VillageBrain brain, Optional<UUID> townCenter,
      List<Long> claimGrid, Optional<StructureInProgress> currentProject, List<UUID> people, List<Building> buildings,
      Map<UUID, JobAssignment> jobAssignments, Map<UUID, BedAssignment> bedAssignments,
      List<JobAssignment> unassignedJobs, List<BedAssignment> unassignedBeds, String tierId,
      List<PendingTraveler> pendingArrivals, List<PendingTraveler> pendingDepartures) {
    Village village = new Village(id, name);
    village.time = time;
    village.brain = brain;
    village.townCenterUUID = townCenter.orElse(null);
    village.claimGrid = new HashSet<>(claimGrid);
    village.currentProject = currentProject.orElse(null);
    village.people = new ArrayList<>(people);
    buildings.forEach(b -> village.buildings.put(b.getUUID(), b));
    village.jobAssignments = new HashMap<>(jobAssignments);
    village.bedAssignments = new HashMap<>(bedAssignments);
    village.unassignedJobs = new ArrayList<>(unassignedJobs);
    village.unassignedBeds = new ArrayList<>(unassignedBeds);
    village.tierId = tierId;
    village.pendingArrivals = new ArrayList<>(pendingArrivals);
    village.pendingDepartures = new ArrayList<>(pendingDepartures);
    return village;
  }

  private final int CHECK_PROJECT_PROGRESS = 60; // 60 // seconds
  /** Longest a stalled village waits between planning attempts. */
  private static final int MAX_PLANNING_BACKOFF = 600; // seconds

  private int time = 0;

  private final String id;
  private String name;

  private VillageBrain brain;
  private UUID townCenterUUID;

  // Runtime-only state, re-attached by the VillageManager save data. Never persisted.
  private transient ServerLevel level;
  private transient Random random = new Random();
  private transient VillageAttractiveness attractiveness;
  // Sentinel far in the past, but safe from overflow in (now - last) arithmetic.
  private transient long lastShortageLogTime = -1_000_000_000L;
  // True while the brain is deciding what to build; keeps one decision in flight.
  private transient boolean projectDecisionPending;
  /** No planning until this second: a village that cannot build must not keep asking. */
  private transient int planningQuietUntil;
  private transient int planningBackoffSeconds;

  private HashSet<Long> claimGrid = new HashSet<>();

  private StructureInProgress currentProject;

  private ArrayList<UUID> people;
  private HashMap<UUID, Building> buildings;
  private HashMap<UUID, JobAssignment> jobAssignments;
  private HashMap<UUID, BedAssignment> bedAssignments;

  private ArrayList<JobAssignment> unassignedJobs;
  private ArrayList<BedAssignment> unassignedBeds;

  private ArrayList<PendingTraveler> pendingArrivals = new ArrayList<>();
  private ArrayList<PendingTraveler> pendingDepartures = new ArrayList<>();

  // High-water mark: only ever rises (docs/village-tiers.md). A read-out of
  // population, never a gate.
  private String tierId = VillageTiers.DEFAULT_TIER_ID;

  public Village(String name) {
    this(UUID.randomUUID().toString(), name);
  }

  private Village(String id, String name) {

    this.id = id;
    this.name = name;

    this.brain = new VillageBrain();

    this.people = new ArrayList<>();
    this.buildings = new HashMap<>();
    this.jobAssignments = new HashMap<>();
    this.bedAssignments = new HashMap<>();
    this.unassignedJobs = new ArrayList<>();
    this.unassignedBeds = new ArrayList<>();

  }

  /** Binds this village to the level it lives in; called by the manager, never persisted. */
  public void attach(ServerLevel level) {
    this.level = level;
    if (this.currentProject != null) {
      this.currentProject.attach(level);
    }
  }

  public ServerLevel getLevel() {
    return level;
  }

  public void initNew(BlockPos centerLoc) {
    if (this.townCenterUUID != null || this.buildings.size() > 0) {
      return;
    }
    if (this.level == null) {
      Villagelife.LOGGER.error("Village {} cannot init without an attached level", id);
      return;
    }

    BuildingInfo centerInfo = Buildings.getByName(Buildings.VILLAGE_CENTER_NAME);
    if (centerInfo == null) {
      Villagelife.LOGGER.error("No building definition '{}' is loaded; cannot found a village", Buildings.VILLAGE_CENTER_NAME);
      return;
    }

    InstantBuildStructure centerStruct = new InstantBuildStructure(
        new Building(centerInfo.getName(), Rotation.values()[random.nextInt(Rotation.values().length)]), random, level)
        .setOriginLocation(centerLoc, claimGrid);
    centerStruct.buildInstantly();

    Building centerBuilding = centerStruct.getBuilding();

    this.townCenterUUID = centerBuilding.getUUID();
    addBuilding(centerBuilding);

    placeCampfireIfMissing();

    // The rest of the founding set (docs/building-spec.md, "How a village
    // starts"): companions share the camp plat on opposite sides of the
    // center, placed free. Their definitions keep normal recipes; only the
    // founding path skips payment, which buildInstantly inherently does.
    int clearance = foundingClearance(centerInfo);
    placeFoundingCompanion(Buildings.FOUNDING_MINE_NAME, centerLoc.offset(clearance, 0, 0));
    placeFoundingCompanion(Buildings.FOUNDING_STOREHOUSE_NAME, centerLoc.offset(-clearance, 0, 0));

    // No founding crew is spawned directly: personas are generated before any
    // spawn (persona map #4), so the first villagers arrive through the
    // campfire loop like everyone else.

  }

  /**
   * How far from the center origin a founding companion sits: past the
   * center's own template span in any rotation, plus a walkway gap.
   */
  private int foundingClearance(BuildingInfo centerInfo) {
    var template = level.getStructureManager().getOrCreate(
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, centerInfo.getPath()));
    var size = template.getSize();
    return Math.max(size.getX(), size.getZ()) + 4;
  }

  /** Places one founding-set companion free, or skips it loudly when its definition is missing. */
  private void placeFoundingCompanion(String name, BlockPos near) {
    BuildingInfo info = Buildings.getByName(name);
    if (info == null) {
      Villagelife.LOGGER.warn("Founding set building '{}' is not loaded; the camp founds without it", name);
      return;
    }
    BlockPos ground = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, near);
    InstantBuildStructure struct = new InstantBuildStructure(
        new Building(info.getName(), Rotation.NONE), random, level)
        .setOriginLocation(ground, claimGrid);
    struct.buildInstantly();
    addBuilding(struct.getBuilding());
  }

  /** Places the gathering-point campfire if the datapack defines one and the block isn't there yet. */
  private void placeCampfireIfMissing() {
    BlockPos fire = gatheringPointPos();
    if (fire != null && level != null && !level.getBlockState(fire).is(Blocks.CAMPFIRE)) {
      level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState(), 3);
    }
  }

  /** The datapack-defined gathering point of the town center in world coordinates, or null. */
  @javax.annotation.Nullable
  private BlockPos gatheringPointPos() {
    Building townCenter = getTownCenter();
    if (townCenter == null || townCenter.getInfo() == null) {
      return null;
    }
    Long offset = townCenter.getInfo().getGatheringPoint();
    if (offset == null) {
      return null;
    }
    return BlockPos.of(townCenter.getOriginLocation())
        .offset(BlockPos.of(offset).rotate(townCenter.getRotation()));
  }

  /**
   * Where idle people gather: a standing spot BESIDE the lit campfire, or the
   * village center as fallback (the fire may be broken or doused).
   *
   * Never the fire block itself. Everyone who gathers walks here, idles here,
   * and is snapped here when their travel times out, so returning the fire
   * would burn them alive: it did, once.
   */
  public BlockPos getGatheringPoint() {
    BlockPos fire = gatheringPointPos();
    if (fire != null && level != null) {
      var state = level.getBlockState(fire);
      if (state.is(Blocks.CAMPFIRE) && state.getValue(CampfireBlock.LIT)) {
        return standingSpotBeside(fire);
      }
    }
    Building townCenter = getTownCenter();
    return townCenter != null ? BlockPos.of(townCenter.getCenterLocation()) : BlockPos.ZERO;
  }

  /** The first free neighbour of the fire a person can stand in, else the nearest air above it. */
  private BlockPos standingSpotBeside(BlockPos fire) {
    for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
      BlockPos side = fire.relative(direction);
      if (level.getBlockState(side).isPathfindable(net.minecraft.world.level.pathfinder.PathComputationType.LAND)
          && level.getBlockState(side.above()).isPathfindable(net.minecraft.world.level.pathfinder.PathComputationType.LAND)
          && level.getBlockState(side.below()).isSolid()) {
        return side;
      }
    }
    return fire.above();
  }

  /**
   * Instantly places a building next to the town centre, for dev testing only:
   * the planner normally decides what gets built and pays for it. Mirrors the
   * founding placement path.
   */
  public boolean devPlaceBuilding(String buildingName) {
    if (level == null || getTownCenter() == null) {
      return false;
    }
    BlockPos near = BlockPos.of(getTownCenter().getCenterLocation()).offset(16, 0, 16);
    BlockPos ground = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, near);
    InstantBuildStructure struct = new InstantBuildStructure(
        new Building(buildingName, Rotation.NONE), random, level)
        .setOriginLocation(ground, claimGrid);
    if (!struct.buildInstantly()) {
      return false;
    }
    addBuilding(struct.getBuilding());
    return true;
  }

  protected void addBuilding(Building building) {
    this.buildings.put(building.getUUID(), building);
    this.brain.processNewBuilding(building, unassignedBeds, unassignedJobs);
  }

  private void checkCurrentProject() {

    if (currentProject != null) {
      if (currentProject.getProgress() == BuildProgress.COMPLETE) {
        Villagelife.LOGGER.debug("Since current project is complete, adding it and setting to null");

        addBuilding(currentProject.getBuilding());
        currentProject = null;

      } else {
        // Building is being worked on...

        // TODO, if project is in progress but builder has never placed first block. Make note.
        // After X number of progress updates, give up and abandon project.

      }
    } else {

      // Choosing is asynchronous: the rules filter to legal, affordable options
      // and the brain picks among them, which may take a moment or never
      // answer. One decision is in flight at a time; the project starts when
      // the answer lands.
      if (projectDecisionPending || time < planningQuietUntil) {
        return;
      }
      projectDecisionPending = true;
      UrbanPlanner.chooseNextProject(this).whenComplete((chosen, error) -> {
        if (level == null) {
          projectDecisionPending = false;
          return;
        }
        level.getServer().execute(() -> {
          projectDecisionPending = false;
          if (error != null) {
            Villagelife.LOGGER.error("Village '{}' failed to choose a project", name, error);
            return;
          }
          if (chosen == null) {
            maybeLogShortage(UrbanPlanner.firstUnaffordableMaterial(this));
            backOffPlanning();
            return;
          }
          if (currentProject == null && !startProject(chosen)) {
            backOffPlanning();
          }
        });
      });
    }

  }

  /** Places a chosen building on the best free site, or logs why it could not. */
  private boolean startProject(BuildingInfo buildingInfo) {
      Building building = new Building(buildingInfo.getName(), Rotation.values()[random.nextInt(Rotation.values().length)]);
      StructureInProgress project = new StructureInProgress(building, random);
      project.attach(level);
      BoundingBox bounds = project.getStructureTemplate().getBoundingBox(project.getStructurePlaceSettings(),
          BlockPos.ZERO);

      BlockPos projectLocation = LocationValidator.findValidLocation(level,
          BlockPos.of(getTownCenter().getCenterLocation()).below(), bounds, this, random);

      if (projectLocation != BlockPos.ZERO) {
        // Beginning construction of new project.

        UrbanPlanner.payForBuilding(this, project.getBuilding().getInfo());

        currentProject = project.setOriginLocation(projectLocation);

        BlockPos centerOffset = new BlockPos(bounds.getCenter().getX(), 0, bounds.getCenter().getZ());
        currentProject.getBuilding().setCenterLocation(projectLocation.above().offset(centerOffset).asLong());
        currentProject.getBuilding().setRadius(LocationValidator.getBuildingRadius(bounds));

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
          for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            claimGrid.add(BlockPos.asLong(projectLocation.getX() + x, 0, projectLocation.getZ() + z));
          }
        }

        planningBackoffSeconds = 0;
        return true;

      } else {
        Villagelife.LOGGER.debug("Failed to find a valid location for the new building.");
        return false;
      }

  }

  /**
   * Doubles the wait before the next planning attempt. A village with no room
   * and no materials would otherwise spend an LLM call every
   * {@link #CHECK_PROJECT_PROGRESS} seconds forever, starving persona
   * generation and every other queued request behind it.
   */
  private void backOffPlanning() {
    planningBackoffSeconds = planningBackoffSeconds == 0
        ? CHECK_PROJECT_PROGRESS
        : Math.min(planningBackoffSeconds * 2, MAX_PLANNING_BACKOFF);
    planningQuietUntil = time + planningBackoffSeconds;
  }

  public void update(ServerLevel level) { // Every 1 second
    attach(level);

    this.brain.update();

    // Recompute attractiveness every 10 seconds, phase-staggered per village so
    // many villages don't all scan their containers on the same tick.
    if (attractiveness == null || (time + Math.floorMod(id.hashCode(), 10)) % 10 == 0) {
      attractiveness = computeAttractiveness();
    }

    if (time % CHECK_PROJECT_PROGRESS == 0) {// Every X seconds

      checkCurrentProject();

    }

    int populationInterval = Math.max(5, com.quzzar.villagelife.configuration.VillagelifeConfig.PopulationCheckIntervalSeconds);
    if ((time + Math.floorMod(id.hashCode(), populationInterval)) % populationInterval == 0) {
      checkPopulation();

      // Backfill the display-only village name onto residents that predate the
      // field (it is normally set at arrival).
      for (UUID personId : people) {
        RealPerson person = getPerson(level, personId);
        if (person != null && person.getVillageName().isBlank()) {
          person.setVillageName(this.name);
        }
      }
    }

    tickTravelers();

    JobClaiming.tick(this, level);

    // Villagers making up their minds about what has happened to them lately.
    int reflectInterval = com.quzzar.villagelife.relationships.ReflectionService.REFLECT_INTERVAL_SECONDS;
    if ((time + Math.floorMod(id.hashCode(), reflectInterval)) % reflectInterval == 0) {
      com.quzzar.villagelife.relationships.ReflectionService.tick(this, level);
    }

    // Familiarity: not a judgement, just the pull of sharing a workplace or a fire.
    int driftInterval = com.quzzar.villagelife.relationships.RelationshipDrift.DRIFT_INTERVAL_SECONDS;
    if ((time + Math.floorMod(id.hashCode(), driftInterval)) % driftInterval == 0) {
      com.quzzar.villagelife.relationships.RelationshipDrift.tick(this);
    }

    // Every 100 seconds, phase-staggered per village like the attractiveness
    // recompute, so many villages don't all classify on the same tick.
    if ((time + Math.floorMod(id.hashCode(), 100)) % 100 == 0) {
      updateTier();
    }

    time++;
  }

  /** Reclassifies the village from its population; the tier only ever rises. */
  private void updateTier() {
    VillageTier classified = VillageTiers.classify(people.size());
    if (classified == null || classified.rank() <= VillageTiers.rankOf(tierId)) {
      return;
    }
    tierId = classified.id();
    Villagelife.LOGGER.info("Village '{}' promoted to {} (population {})", name, tierId, people.size());
    if (level != null) {
      level.getServer().getPlayerList().broadcastSystemMessage(
          Component.translatable("villagelife.tier.promotion", name,
              Component.translatable(classified.translationKey())),
          false);
    }
  }

  public String getTierId() {
    return tierId;
  }

  @Nullable
  public VillageTier getTier() {
    return VillageTiers.byId(tierId);
  }

  /** The campfire loop: attractiveness-gated arrivals and departures. */
  private void checkPopulation() {
    if (level == null || getTownCenter() == null) {
      return;
    }
    VillageAttractiveness report = getAttractiveness();
    switch (report.status()) {
      case GROWING -> tryArrival(report);
      case DECLINING -> tryEmigration();
      case HOLDING -> { }
    }
  }

  private int idleCap() {
    VillageTier tier = getTier();
    return tier != null ? tier.idleCap()
        : com.quzzar.villagelife.configuration.VillagelifeConfig.IdleCapFallback;
  }

  private int idleCount() {
    int count = 0;
    for (UUID person : people) {
      if (!jobAssignments.containsKey(person)) {
        count++;
      }
    }
    return count;
  }

  private void tryArrival(VillageAttractiveness report) {
    // The further above the grow threshold, the likelier an arrival this check.
    double arriveThreshold = com.quzzar.villagelife.configuration.VillagelifeConfig.AttractivenessArriveThreshold;
    double chance = (report.total() - arriveThreshold) / Math.max(1.0, 100.0 - arriveThreshold);
    if (random.nextDouble() > Math.max(0.05, chance)) {
      return;
    }

    // Caps, both counting walkers. Population may exceed beds by up to the tier's
    // idle cap: the campfire reservoir is where bedless newcomers wait, so housing
    // alone must not gate arrivals (docs/population-and-labor.md).
    int futurePopulation = people.size() + pendingArrivals.size();
    int totalBeds = bedAssignments.size() + unassignedBeds.size();
    if (futurePopulation >= totalBeds + idleCap()) {
      Villagelife.LOGGER.debug(
          "Village '{}' at population cap: {} here + {} walking vs {} beds + {} idle cap",
          name, people.size(), pendingArrivals.size(), totalBeds, idleCap());
      return;
    }
    if (idleCount() + pendingArrivals.size() >= idleCap()) {
      Villagelife.LOGGER.debug(
          "Village '{}' campfire reservoir full: {} idle + {} walking vs idle cap {}",
          name, idleCount(), pendingArrivals.size(), idleCap());
      return;
    }

    BlockPos spawnPos = edgeSpawnPos();
    if (spawnPos == null) {
      return; // edge chunks not loaded: nobody would see the arrival anyway
    }

    long deadline = level.getGameTime()
        + com.quzzar.villagelife.configuration.VillagelifeConfig.TravelTimeoutSeconds * 20L;
    BlockPos fire = getGatheringPoint();

    // Wanderers before spawns (#59): someone already roaming the world is
    // recruited ahead of conjuring anyone new, so the same souls circulate
    // between villages carrying their stats, memories, and relationships.
    RealPerson wanderer = findNearbyWanderer();
    if (wanderer != null) {
      wanderer.setVillage(id);
      wanderer.setOccupation(Occupation.IDLE);
      pendingArrivals.add(new PendingTraveler(wanderer.getUUID(), deadline, fire.asLong()));
      Villagelife.LOGGER.info("'{}' the wanderer was drawn to village '{}' and is coming to join",
          wanderer.getFullName(), name);
      return;
    }

    PersonaSpawner.trySpawn(level, spawnPos, person -> {
      person.setVillage(id);
      person.setOccupation(Occupation.IDLE);
    }).thenAccept(attempt -> attempt.spawned().ifPresent(person -> {
      pendingArrivals.add(new PendingTraveler(person.getUUID(), deadline, fire.asLong()));
      Villagelife.LOGGER.info("'{}' is arriving at village '{}'", person.getFullName(), name);
    }));
  }

  /** The nearest loaded person with no village within the recruit radius, or null. */
  @javax.annotation.Nullable
  private RealPerson findNearbyWanderer() {
    BlockPos center = BlockPos.of(getTownCenter().getCenterLocation());
    int radius = com.quzzar.villagelife.configuration.VillagelifeConfig.WandererRecruitRadius;
    net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(center).inflate(radius, 64, radius);
    List<RealPerson> wanderers = level.getEntitiesOfClass(RealPerson.class, box,
        p -> p.isAlive() && p.getVillage() == null);
    return wanderers.stream()
        .min(java.util.Comparator.comparingDouble(
            p -> p.distanceToSqr(center.getX(), center.getY(), center.getZ())))
        .orElse(null);
  }

  /** A surface point just beyond the outermost building, or null when unloaded. */
  @javax.annotation.Nullable
  private BlockPos edgeSpawnPos() {
    BlockPos center = BlockPos.of(getTownCenter().getCenterLocation());
    double radius = com.quzzar.villagelife.configuration.VillagelifeConfig.ArrivalEdgeMinDistance;
    for (Building building : buildings.values()) {
      BlockPos origin = BlockPos.of(building.getOriginLocation());
      double dist = Math.sqrt(center.distSqr(origin)) + 8.0;
      radius = Math.max(radius, dist);
    }
    double angle = random.nextDouble() * Math.PI * 2;
    int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
    int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
    BlockPos column = new BlockPos(x, center.getY(), z);
    if (!level.isLoaded(column)) {
      return null;
    }
    return level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, column);
  }

  private void tryEmigration() {
    // Idle people give up on the village first; then the employed abandon their jobs.
    UUID leaver = null;
    for (UUID person : people) {
      if (isPending(person)) {
        continue;
      }
      if (!jobAssignments.containsKey(person)) {
        leaver = person;
        break;
      }
      if (leaver == null) {
        leaver = person;
      }
    }
    if (leaver == null || !(level.getEntity(leaver) instanceof RealPerson person)) {
      return;
    }

    removePerson(leaver); // frees bed and job exactly like a death does
    long deadline = level.getGameTime()
        + com.quzzar.villagelife.configuration.VillagelifeConfig.TravelTimeoutSeconds * 20L;
    BlockPos exit = edgeSpawnPos();
    if (exit == null) {
      exit = BlockPos.of(getTownCenter().getCenterLocation())
          .offset(com.quzzar.villagelife.configuration.VillagelifeConfig.ArrivalEdgeMinDistance, 0, 0);
    }
    pendingDepartures.add(new PendingTraveler(leaver, deadline, exit.asLong()));
    Villagelife.LOGGER.info("'{}' is leaving village '{}' (attractiveness collapsed)", person.getFullName(), name);
  }

  private boolean isPending(UUID person) {
    return pendingArrivals.stream().anyMatch(p -> p.personId().equals(person))
        || pendingDepartures.stream().anyMatch(p -> p.personId().equals(person));
  }

  /** Every bed the village knows of, taken or free. */
  public int getTotalBeds() {
    return bedAssignments.size() + unassignedBeds.size();
  }

  /** True if this person is on the roster (a resident, not a mid-walk traveler). */
  public boolean hasResident(UUID person) {
    return people.contains(person);
  }

  /** Sets the village's permanent name and sweeps it onto current residents. */
  public void applyName(String newName) {
    this.name = newName;
    if (level == null) {
      return;
    }
    for (UUID personId : people) {
      RealPerson person = getPerson(level, personId);
      if (person != null) {
        person.setVillageName(newName);
      }
    }
  }

  /** True if this person is mid-walk toward or away from this village. */
  public boolean isTraveler(UUID person) {
    return isPending(person);
  }

  /** Every loaded person with no village, across the whole level. */
  private int loadedWandererCount() {
    return level.getEntities(
        net.minecraft.world.level.entity.EntityTypeTest.forClass(RealPerson.class),
        p -> p.isAlive() && p.getVillage() == null).size();
  }

  /** Shepherds everyone mid-walk: re-issues targets, confirms arrivals, completes departures. */
  private void tickTravelers() {
    if (level == null) {
      return;
    }
    long now = level.getGameTime();
    long graceTicks = com.quzzar.villagelife.configuration.VillagelifeConfig.TravelTimeoutSeconds * 20L * 9;

    for (var iterator = pendingArrivals.iterator(); iterator.hasNext();) {
      PendingTraveler pending = iterator.next();
      BlockPos fire = getGatheringPoint();
      if (!(level.getEntity(pending.personId()) instanceof RealPerson person)) {
        if (now > pending.deadline() + graceTicks) {
          iterator.remove(); // chunk never came back (or the walker died uncounted)
        }
        continue;
      }
      if (person.blockPosition().distSqr(fire) <= 16 || now > pending.deadline()) {
        if (now > pending.deadline()) {
          person.moveTo(fire.getX() + 0.5, fire.getY(), fire.getZ() + 0.5, person.getYRot(), 0);
        }
        confirmArrival(person);
        iterator.remove();
      } else {
        person.setTravelTarget(fire);
      }
    }

    for (var iterator = pendingDepartures.iterator(); iterator.hasNext();) {
      PendingTraveler pending = iterator.next();
      if (!(level.getEntity(pending.personId()) instanceof RealPerson person)) {
        if (now > pending.deadline() + graceTicks) {
          iterator.remove();
        }
        continue;
      }
      BlockPos exit = BlockPos.of(pending.target());
      if (person.blockPosition().distSqr(exit) <= 16 || now > pending.deadline()) {
        person.setTravelTarget(null);
        person.setVillage("");
        person.setVillageName("");
        person.reloadState();
        iterator.remove();
        // The world holds only so many wanderers (#59): past the cap, a leaver
        // walks over the horizon instead of lingering forever.
        if (loadedWandererCount() > com.quzzar.villagelife.configuration.VillagelifeConfig.WandererCap) {
          person.discard();
          Villagelife.LOGGER.info("'{}' left '{}' and moved on beyond the horizon", person.getFullName(), name);
        } else {
          Villagelife.LOGGER.info("'{}' left '{}' and now wanders the world", person.getFullName(), name);
        }
      } else {
        person.setTravelTarget(exit);
      }
    }
  }

  /** The walker reached the fire: they are population now, and they take a bed if one is free. */
  private void confirmArrival(RealPerson person) {
    person.setTravelTarget(null);
    person.setVillageName(this.name);
    people.add(person.getUUID());
    if (!unassignedBeds.isEmpty()) {
      BedAssignment bed = unassignedBeds.remove(0);
      bedAssignments.put(person.getUUID(), bed.setPersonUUID(person.getUUID()));
    }
    Villagelife.LOGGER.info("'{}' arrived at the campfire of '{}' (population {})",
        person.getFullName(), name, people.size());

    // One-time newcomer integration: relationships to existing residents,
    // generated async on the low-priority LLM queue (persona map issue #5).
    RelationshipService.integrateNewcomer(this, level, person);
  }

  // --- Relationship pair store: delegates to the brain, which persists it ---

  public void putRelationship(RelationshipPair pair) {
    this.brain.putRelationship(pair);
  }

  public RelationshipPair getRelationship(UUID first, UUID second) {
    return this.brain.getRelationship(first, second);
  }

  public List<RelationshipPair> relationshipsOf(UUID person) {
    return this.brain.relationshipsOf(person);
  }

  // --- #38 (stat-based placement and swaps) interface ---

  /** Snapshot of every booked assignment, for the swap pass. */
  public Map<UUID, JobAssignment> getJobAssignmentsView() {
    return Map.copyOf(jobAssignments);
  }

  /** Unbooks a worker's job and returns it, ready to hand to someone else. */
  public JobAssignment releaseJob(UUID personId) {
    JobAssignment job = jobAssignments.remove(personId);
    return job == null ? null : job.setPersonUUID(null);
  }

  /**
   * Per-person swap cooldown timestamps (game time), persisted inside the
   * brain's free-form strategy tag. Returned live: mutations stick.
   */
  public CompoundTag getSwapCooldowns() {
    CompoundTag strategy = this.brain.getStrategy();
    if (!strategy.contains("job_swap_cooldowns")) {
      strategy.put("job_swap_cooldowns", new CompoundTag());
    }
    return strategy.getCompound("job_swap_cooldowns");
  }

  // --- #18 (idle pool & job claiming) interface: the idle pool is DERIVED ---
  // (people minus jobAssignments minus mid-walk travelers), never stored.

  public List<JobAssignment> getUnassignedJobs() {
    return unassignedJobs;
  }

  /** People eligible for claiming, in FIFO (arrival) order. */
  public List<UUID> idlePeople() {
    List<UUID> idle = new ArrayList<>();
    for (UUID person : people) {
      if (!jobAssignments.containsKey(person) && !isPending(person)) {
        idle.add(person);
      }
    }
    return idle;
  }

  public void assignJob(UUID personId, JobAssignment job) {
    unassignedJobs.remove(job);
    jobAssignments.put(personId, job.setPersonUUID(personId));
  }

  public String getID() {
    return id;
  }

  public String getName() {
    return name;
  }

  public ArrayList<UUID> getPopulation() {
    return people;
  }

  public RealPerson getPerson(ServerLevel level, UUID entityUUID) {
    return (RealPerson) level.getEntity(entityUUID);
  }

  public void removePerson(UUID personUUID) {
    this.brain.removePerson(personUUID, people, bedAssignments, jobAssignments, unassignedBeds, unassignedJobs);
  }

  public Collection<Building> getBuildings() {
    return buildings.values();
  }

  public Building getBuilding(UUID buildingUUID) {
    return buildings.get(buildingUUID);
  }

  public Building getTownCenter() {
    return buildings.get(this.townCenterUUID);
  }

  public JobAssignment getJobAssignment(UUID personUUID) {
    return jobAssignments.get(personUUID);
  }

  public BedAssignment getBedAssignment(UUID personUUID) {
    return bedAssignments.get(personUUID);
  }

  public boolean hasClaimed(BlockPos pos) {
    return claimGrid.contains(BlockPos.asLong(pos.getX(), 0, pos.getZ()));
  }

  public BlockPos getNearestContainer(BlockPos location) {
    return this.brain.getNearestContainer(location);
  }

  public ItemStack gatherItemStackFromVillage(ItemStack itemStack) {
    return gatherItemStackFromVillage(itemStack, null);
  }

  public ItemStack gatherItemStackFromVillage(ItemStack itemStack, BlockPos preferNearestToLoc) {
    if (level == null) {
      return itemStack.copyWithCount(0);
    }
    return this.brain.gatherItemStackFromVillage(level, itemStack, preferNearestToLoc);
  }

  public boolean placeItemStackIntoVillage(ItemStack itemStack, Entity entity) {
    return placeItemStackIntoVillage(itemStack, entity, null);
  }

  public boolean placeItemStackIntoVillage(ItemStack itemStack, Entity entity, BlockPos preferNearestToLoc) {
    if (level == null) {
      return false;
    }
    return this.brain.placeItemStackIntoVillage(level, itemStack, entity, preferNearestToLoc);
  }

  public boolean hasItemStackInVillage(ItemStack itemStack) {
    return level != null && this.brain.hasItemStackInVillage(level, itemStack);
  }

  public ArrayList<ItemStack> getVillageInventory() {
    if (level == null) {
      return new ArrayList<>();
    }
    return this.brain.getVillageInventory(level);
  }

  public void logEvent(BookkeepingEvent event) {
    this.brain.logEvent(event);
  }

  public VillageBrain getBrain() {
    return brain;
  }

  /**
   * Computes a fresh attractiveness report from current village state. Use
   * {@link #getAttractiveness()} for the cached per-tick value.
   */
  public VillageAttractiveness computeAttractiveness() {
    int population = people.size();
    int foodCount = level != null ? brain.countFood(level) : 0;
    int totalBeds = bedAssignments.size() + unassignedBeds.size();
    int freeBeds = unassignedBeds.size();
    int homelessCount = Math.max(0, population - bedAssignments.size());
    return VillageAttractiveness.compute(population, foodCount, totalBeds, freeBeds, homelessCount,
        brain.totalImpact(com.quzzar.villagelife.village.bookkeeping.DeathBookkeepingEvent.class),
        brain.totalImpact(com.quzzar.villagelife.village.bookkeeping.HurtByPlayerBookkeepingEvent.class),
        brain.totalImpact(com.quzzar.villagelife.village.bookkeeping.NoResourceBookkeepingEvent.class));
  }

  public VillageAttractiveness getAttractiveness() {
    if (attractiveness == null) {
      attractiveness = computeAttractiveness();
    }
    return attractiveness;
  }

  /** Logs a resource-shortage event, rate-limited so a poor village complains steadily, not constantly. */
  private void maybeLogShortage(ItemStack missing) {
    if (missing == null || level == null) {
      return;
    }
    long now = level.getGameTime();
    long cooldownTicks = com.quzzar.villagelife.configuration.VillagelifeConfig.ShortageEventCooldownSeconds * 20L;
    if (now - lastShortageLogTime < cooldownTicks) {
      return;
    }
    lastShortageLogTime = now;
    logEvent(new com.quzzar.villagelife.village.bookkeeping.NoResourceBookkeepingEvent(missing.getItem(), missing.getCount()));
    Villagelife.LOGGER.debug("Village '{}' is short on {} x{}", name, missing.getItem(), missing.getCount());
  }

  public StructureInProgress getCurrentProject() {
    if (currentProject != null && level != null) {
      currentProject.attach(level);
    }
    return currentProject;
  }

}
