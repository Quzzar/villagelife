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
import com.quzzar.villagelife.village.buildings.BuildingUpgrade;
import com.quzzar.villagelife.village.buildings.Buildings;
import com.quzzar.villagelife.village.buildings.InstantBuildStructure;
import com.quzzar.villagelife.village.buildings.LocationValidator;
import com.quzzar.villagelife.village.buildings.SitePreparation;
import com.quzzar.villagelife.village.buildings.StructureInProgress;
import com.quzzar.villagelife.village.buildings.WallProject;
import com.quzzar.villagelife.village.buildings.WallTier;
import com.quzzar.villagelife.village.buildings.UrbanPlanner;
import com.quzzar.villagelife.village.buildings.VillageStyle;
import com.quzzar.villagelife.persona.PersonaSpawner;
import com.quzzar.villagelife.village.tiers.VillageTier;
import com.quzzar.villagelife.village.tiers.VillageTiers;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
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
      ActiveProjects.CODEC.optionalFieldOf("project", ActiveProjects.NONE)
          .forGetter(v -> new ActiveProjects(Optional.ofNullable(v.currentProject), Optional.ofNullable(v.wallProject))),
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

  /**
   * The village's one active project, if any: either a building rising or the
   * wall being raised. They are mutually exclusive (one builder, one job at a
   * time), so they share a single slot in the save.
   */
  public record ActiveProjects(Optional<StructureInProgress> building, Optional<WallProject> wall) {
    public static final ActiveProjects NONE = new ActiveProjects(Optional.empty(), Optional.empty());
    public static final Codec<ActiveProjects> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        StructureInProgress.CODEC.optionalFieldOf("building").forGetter(ActiveProjects::building),
        WallProject.CODEC.optionalFieldOf("wall").forGetter(ActiveProjects::wall)
    ).apply(inst, ActiveProjects::new));
  }

  private static Village fromCodec(String id, String name, int time, VillageBrain brain, Optional<UUID> townCenter,
      List<Long> claimGrid, ActiveProjects project, List<UUID> people, List<Building> buildings,
      Map<UUID, JobAssignment> jobAssignments, Map<UUID, BedAssignment> bedAssignments,
      List<JobAssignment> unassignedJobs, List<BedAssignment> unassignedBeds, String tierId,
      List<PendingTraveler> pendingArrivals, List<PendingTraveler> pendingDepartures) {
    Village village = new Village(id, name);
    village.time = time;
    village.brain = brain;
    village.townCenterUUID = townCenter.orElse(null);
    village.claimGrid = new HashSet<>(claimGrid);
    village.currentProject = project.building().orElse(null);
    village.wallProject = project.wall().orElse(null);
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
  /** Checks a gather gets when its materials have genuinely left the village, before abandoning. */
  private static final int MAX_GATHERING_CHECKS = 15;
  /** The hard ceiling: checks a gather gets even while its materials sit in transit, so a
   *  delivery that never lands cannot freeze a project forever. */
  private static final int MAX_TRANSIT_CHECKS = 60;

  private int time = 0;

  private final String id;
  private String name;

  private VillageBrain brain;
  private UUID townCenterUUID;

  // Runtime-only state, re-attached by the VillageManager save data. Never persisted.
  private transient ServerLevel level;
  private transient Random random = new Random();
  private transient VillageAttractiveness attractiveness;
  // What the village has learned about its own room. Never persisted.
  private transient final com.quzzar.villagelife.village.buildings.SiteMemory siteMemory =
      new com.quzzar.villagelife.village.buildings.SiteMemory();
  // Sentinel far in the past, but safe from overflow in (now - last) arithmetic.
  private transient long lastShortageLogTime = -1_000_000_000L;
  // True while the brain is deciding what to build; keeps one decision in flight.
  private transient boolean projectDecisionPending;
  // True while the brain is deciding who takes an open post; keeps one job
  // decision in flight (JobClaiming), the same discipline as the project one.
  private transient boolean jobDecisionPending;
  // What the village can do, derived from its buildings (#55). Never persisted:
  // recomputed on building change and on the slow tick, because supply-gated
  // grants depend on what the chests hold right now.
  private transient java.util.Set<String> capabilities;
  /** No planning until this second: a village that cannot build must not keep asking. */
  private transient int planningQuietUntil;
  private transient int planningBackoffSeconds;

  private HashSet<Long> claimGrid = new HashSet<>();

  // Bounding box of the claim (x/z), cached and recomputed only when the claim grows. Backs
  // hasClaimedWithin so the padded entry-title check stays O(1). Derived from claimGrid, not persisted.
  private int claimBoundsAtSize = -1;
  private int claimMinX, claimMaxX, claimMinZ, claimMaxZ;

  private StructureInProgress currentProject;

  /**
   * The wall being raised around the village, or null when there is none. Not a
   * StructureInProgress: a wall is a route rather than a footprint (docs/walls.md).
   * A completed wall stays here as the record that the village is walled, so its
   * tier can be read when the stone upgrade is offered.
   */
  private WallProject wallProject;

  /**
   * Set when the quartermaster cannot fit a haul into the storehouse, read by
   * the planner as a reason to want more storage. Transient on purpose: it is a
   * live symptom, not saved state, and the quartermaster re-raises it on the
   * next overflow, so it need not survive a reload.
   */
  private transient boolean storageStrained;

  /** Consecutive project checks the current build has spent gathering; abandons past the cap. */
  private transient int gatheringChecks;

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

  /** Where the founding style lives: in the brain's strategy tag, beside the goal and the shelving plan. */
  private static final String STYLE_KEY = "style";

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

  /**
   * The regional family this village builds in, fixed at founding from the
   * biome (docs/buildings.md). Villages saved before styles existed read as
   * plains, which is what they were built in.
   */
  public VillageStyle getStyle() {
    return VillageStyle.fromId(brain.getStrategy().getString(STYLE_KEY));
  }

  /** Sets the style once, before founding places the first building. */
  public void setStyle(VillageStyle style) {
    brain.getStrategy().putString(STYLE_KEY, style.id());
  }

  public void initNew(BlockPos centerLoc) {
    if (this.townCenterUUID != null || this.buildings.size() > 0) {
      return;
    }
    if (this.level == null) {
      Villagelife.LOGGER.error("Village {} cannot init without an attached level", id);
      return;
    }

    BuildingInfo centerInfo = Buildings.resolve(Buildings.VILLAGE_CENTER_CATEGORY, 1, getStyle());
    if (centerInfo == null) {
      Villagelife.LOGGER.error("No village center is loaded for the {} style or for plains; cannot found a village", getStyle().id());
      return;
    }

    // The whole camp sits on ONE plane, so it reads as a level camp rather than
    // three buildings snapped to three different surface heights - which is what
    // founding did before, and why a camp on any slope came out as scattered
    // buildings at scattered elevations (docs/building-spec.md, "The camp is
    // placed as one plat"). The plane is the ground at the founding point; the
    // centre sits at the middle with a companion a clearance out on each side.
    int centerSpan = templateSpan(centerInfo);
    int clearance = centerSpan + 4;
    int half = (maxFoundingSpan(centerSpan) + 1) / 2 + 1;

    // A village cannot reshape ground it has not loaded. Load every chunk the
    // camp will touch first, so the heightmap, the site check and the levelling
    // read real terrain rather than an unloaded default - which otherwise reads
    // as the world floor and founds the whole camp far underground.
    forceLoadCamp(centerLoc, clearance + half);

    // MOTION_BLOCKING_NO_LEAVES gives the top block that stops movement but ISN'T a leaf,
    // i.e. the real ground UNDER a tree canopy -- WORLD_SURFACE counts leaves/branches as
    // surface, so founding under a tree seated the whole camp at canopy height and then
    // dirt-filled a pillar down to the ground (Aaron's "sky village"). Seat the camp AT the
    // heightmap -- the first course above the top ground block -- so the whole camp is raised
    // one block: the founder, who runs create-village standing on the ground, lands at their
    // own level instead of a block into it. Seating a course lower dropped them a block on
    // create-village; raising the entire camp one block is what Aaron asked for.
    int planeY = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centerLoc).getY();
    BlockPos platCenter = new BlockPos(centerLoc.getX(), planeY, centerLoc.getZ());

    // Anchor the whole camp on the CAMPFIRE, not the centre building's midpoint, so a
    // village generates FROM the fire: whoever founds one stands at the gathering point
    // with the buildings arrayed around them, rather than inside the centre building. The
    // gathering point sits off-centre in the centre, so a throwaway first placement measures
    // where the fire would land, then platCenter shifts by that offset so the fire lands
    // exactly on the requested point. Rotation is fixed up front so probe and real agree;
    // if the centre defines no gathering point, the probe returns platCenter and nothing shifts.
    Building centerBuilding = new Building(centerInfo.getName(),
        Rotation.values()[random.nextInt(Rotation.values().length)]);
    Rotation centreRot = centerBuilding.getRotation();
    BlockPos probeFire = campfireWorldPos(
        new InstantBuildStructure(centerBuilding, random, level).setOriginLocation(platCenter, new java.util.HashSet<>()),
        centerInfo, centreRot, platCenter);
    platCenter = platCenter.offset(centerLoc.getX() - probeFire.getX(), 0, centerLoc.getZ() - probeFire.getZ());

    // Plan the centre at the anchored platCenter so we know its ACTUAL placed footprint
    // and where its campfire lands, before deciding how much ground to level and where
    // the companions sit.
    InstantBuildStructure centerStruct =
        new InstantBuildStructure(centerBuilding, random, level).setOriginLocation(platCenter, claimGrid);
    BoundingBox centreBounds = centerStruct.getBounds();

    // Flank the CAMPFIRE, not the centre building's midpoint. The gathering point
    // sits toward one end of the long centre (local [4,1,4]), so seating the mine
    // and store either side of the fire pulls them to that end rather than the dead
    // middle of the wall. They flank across the centre's SHORT axis, aligned with
    // the fire on the long axis, two blocks off the wall - unstuck from the centre
    // but still one tight cluster.
    BlockPos fire = campfireWorldPos(centerStruct, centerInfo, centreRot, platCenter);
    boolean flankAlongX = centreBounds.getXSpan() <= centreBounds.getZSpan();
    int centreHalfShort = (flankAlongX ? centreBounds.getXSpan() : centreBounds.getZSpan()) / 2;
    InstantBuildStructure mineStruct = planFlankingCompanion(
        Buildings.FOUNDING_MINE_CATEGORY, fire, platCenter, flankAlongX, +1, centreHalfShort, FOUNDING_FLANK_GAP);
    InstantBuildStructure storeStruct = planFlankingCompanion(
        Buildings.FOUNDING_STOREHOUSE_CATEGORY, fire, platCenter, flankAlongX, -1, centreHalfShort, FOUNDING_FLANK_GAP);

    // Level only the ground the three buildings actually stand on, plus a small
    // margin - not a generous strip. Founding used to flatten a ~71-wide field for
    // a ~35-wide cluster, which read in-world as the camp "clearing land far out in
    // every direction". The union of the placed footprints is exactly what is built on.
    BoundingBox plat = campFootprint(planeY, centerStruct, mineStruct, storeStruct);

    // One site check over the whole footprint (docs: "one composite footprint,
    // one site check"), taken BEFORE anything is placed so it reads the terrain and
    // not the village's own fresh claims. Founding is pickier than growth because it
    // needs the entire plat at once, and pays preparation an ordinary placement would
    // refuse - so this reports how rough the ground was but does not refuse a camp a
    // caller asked to found here.
    SitePreparation.SiteCost cost = SitePreparation.score(level, this, platCenter, plat);
    // The score is advisory - founding levels and builds regardless - so an
    // "impossible" here is a note about the ground, not a failure. In practice
    // it only reads impossible when a plat column reports unloaded, which the
    // levelling then works on anyway (the block ops load on demand where the
    // stricter isLoaded check does not).
    Villagelife.LOGGER.info("Founding '{}' in the {} style on a {}x{} plat at {}: {}", name, getStyle().id(),
        plat.getXSpan(), plat.getZSpan(), platCenter.toShortString(),
        cost.impossible() ? "settling rough or unassessed ground (" + cost.reason() + ")" : cost.describe());

    // Level ONLY the ground each building stands on -- not a shared plat -- so the camp
    // reads as buildings plopped on the natural surface, with no grass/dirt platform
    // around them (Aaron: "just plop the buildings... no grass platform"). Each footprint
    // is levelled then hidden under its building; the ground BETWEEN buildings is left
    // natural. Companions keep normal recipes; founding skips payment as buildInstantly.
    for (InstantBuildStructure struct : new InstantBuildStructure[] { centerStruct, mineStruct, storeStruct }) {
      if (struct != null) {
        levelPlatTo(buildingFootprint(struct, planeY), planeY);
      }
    }

    // The storehouse goes up first: the camp circle's chest is the campers' own,
    // not village storage, so the logs of any tree felled over the circle or the
    // mine need the barrels standing to have somewhere to go.
    buildFoundingCompanion(storeStruct);
    centerStruct.buildInstantly();
    this.townCenterUUID = centerBuilding.getUUID();
    addBuilding(centerBuilding);
    placeCampfireIfMissing();
    buildFoundingCompanion(mineStruct);

    // No founding crew is spawned directly: personas are generated before any
    // spawn (persona map #4), so the first villagers arrive through the
    // campfire loop like everyone else.

  }

  /**
   * Loads every chunk within {@code reach} blocks of the camp centre, so the
   * heightmap read, the site score and the levelling all see generated terrain.
   * Reading a heightmap from an unloaded chunk returns the world floor, which
   * is what founded a camp at y=-60 in a fresh region.
   */
  private void forceLoadCamp(BlockPos center, int reach) {
    int minCx = (center.getX() - reach) >> 4;
    int maxCx = (center.getX() + reach) >> 4;
    int minCz = (center.getZ() - reach) >> 4;
    int maxCz = (center.getZ() + reach) >> 4;
    for (int cx = minCx; cx <= maxCx; cx++) {
      for (int cz = minCz; cz <= maxCz; cz++) {
        level.getChunk(cx, cz); // FULL status: generates and loads if absent
      }
    }
  }

  /** The widest of the founding buildings, so the plat is deep enough for all three. */
  private int maxFoundingSpan(int centerSpan) {
    int span = centerSpan;
    for (String category : new String[] { Buildings.FOUNDING_MINE_CATEGORY, Buildings.FOUNDING_STOREHOUSE_CATEGORY }) {
      BuildingInfo info = Buildings.resolve(category, 1, getStyle());
      if (info != null) {
        span = Math.max(span, templateSpan(info));
      }
    }
    return span;
  }

  /** The larger horizontal span of a building's template, for laying out the plat. */
  private int templateSpan(BuildingInfo info) {
    var template = level.getStructureManager().getOrCreate(
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Villagelife.MODID, info.getPath()));
    var size = template.getSize();
    return Math.max(size.getX(), size.getZ());
  }

  /** Blocks of breathing room left between the centre wall and each flanking companion. */
  private static final int FOUNDING_FLANK_GAP = 2;

  /**
   * The campfire's world position for a freshly planned centre, mirroring
   * {@link #gatheringPointPos()} but reading the struct we hold rather than the
   * registered town centre (which is not added until after levelling).
   */
  private BlockPos campfireWorldPos(InstantBuildStructure centerStruct, BuildingInfo centerInfo,
      Rotation rotation, BlockPos fallback) {
    Long offset = centerInfo.getGatheringPoint();
    if (offset == null) {
      return fallback;
    }
    return BlockPos.of(centerStruct.getBuilding().getOriginLocation())
        .offset(BlockPos.of(offset).rotate(rotation));
  }

  /**
   * A founding companion turned a quarter-turn and seated beside the campfire: out
   * along the centre's short axis (its own half-span plus the gap past the centre
   * wall), aligned with the fire on the long axis. {@code side} is +1 or -1 for the
   * two sides. Null when the definition is not loaded, so the camp founds without it.
   */
  @javax.annotation.Nullable
  private InstantBuildStructure planFlankingCompanion(String category, BlockPos fire, BlockPos platCenter,
      boolean flankAlongX, int side, int centreHalfShort, int gap) {
    BuildingInfo info = Buildings.resolve(category, 1, getStyle());
    if (info == null) {
      Villagelife.LOGGER.warn("No {} is loaded for the {} style or for plains; the camp founds without it", category, getStyle().id());
      return null;
    }
    // Turn each companion so its DOOR faces the campfire. The mine's front is local
    // -Z (verified in-world) and the storehouse's is local +Z (authored opposite) --
    // so from opposite sides of the fire they take the SAME rotation to point inward.
    // (The store came out 180 off when this assumed a shared -Z front; hence no `side`
    // term.) Both companions are odd-dimensioned, so rotation only changes facing.
    Rotation facing = flankAlongX ? Rotation.COUNTERCLOCKWISE_90 : Rotation.NONE;
    InstantBuildStructure struct =
        new InstantBuildStructure(new Building(info.getName(), facing), random, level);
    BoundingBox bounds = struct.getBounds();
    int companionHalfShort = (flankAlongX ? bounds.getXSpan() : bounds.getZSpan()) / 2;
    int out = centreHalfShort + gap + companionHalfShort;
    BlockPos at = flankAlongX
        ? new BlockPos(platCenter.getX() + side * out, platCenter.getY(), fire.getZ())
        : new BlockPos(fire.getX(), platCenter.getY(), platCenter.getZ() + side * out);
    return struct.setOriginLocation(at, claimGrid);
  }

  /**
   * A single building's own footprint at the plane, no margin -- the exact ground it
   * stands on. Levelling each companion by its own footprint (rather than the union)
   * keeps the terrain BETWEEN buildings natural, so no platform shows.
   */
  private BoundingBox buildingFootprint(InstantBuildStructure struct, int planeY) {
    BlockPos center = BlockPos.of(struct.getBuilding().getCenterLocation());
    BoundingBox bounds = struct.getBounds();
    int halfX = (bounds.getXSpan() + 1) / 2;
    int halfZ = (bounds.getZSpan() + 1) / 2;
    return new BoundingBox(center.getX() - halfX, planeY, center.getZ() - halfZ,
        center.getX() + halfX, planeY, center.getZ() + halfZ);
  }

  /**
   * The union footprint of the founding buildings at the plane, with a small margin,
   * so levelling touches only the ground the camp actually stands on. Skips any
   * companion that failed to plan.
   */
  private BoundingBox campFootprint(int planeY, InstantBuildStructure... structs) {
    int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
    for (InstantBuildStructure struct : structs) {
      if (struct == null) {
        continue;
      }
      BlockPos center = BlockPos.of(struct.getBuilding().getCenterLocation());
      BoundingBox bounds = struct.getBounds();
      int halfX = (bounds.getXSpan() + 1) / 2;
      int halfZ = (bounds.getZSpan() + 1) / 2;
      minX = Math.min(minX, center.getX() - halfX);
      maxX = Math.max(maxX, center.getX() + halfX);
      minZ = Math.min(minZ, center.getZ() - halfZ);
      maxZ = Math.max(maxZ, center.getZ() + halfZ);
    }
    return new BoundingBox(minX - FOUNDING_FLANK_GAP, planeY, minZ - FOUNDING_FLANK_GAP,
        maxX + FOUNDING_FLANK_GAP, planeY, maxZ + FOUNDING_FLANK_GAP);
  }

  /** Raises a planned companion on the prepared plat, or does nothing when it was skipped. */
  private void buildFoundingCompanion(@javax.annotation.Nullable InstantBuildStructure struct) {
    if (struct == null) {
      return;
    }
    struct.buildInstantly();
    addBuilding(struct.getBuilding());
  }

  /** How far below the plane founding will fill a dip before giving up on it. */
  private static final int FOUNDING_MAX_FILL = 6;
  /** How far above the plane founding will cut a mound before leaving the rest: a cliff, not a plat. */
  private static final int FOUNDING_MAX_CUT = 24;

  /**
   * Flattens the camp footprint to a single plane: clears whatever stands above
   * it and fills whatever falls below, so all three buildings sit flush on one
   * surface. Founding pays this for free, which is how it can settle ground that
   * ordinary placement would refuse. Columns holding a block entity are left
   * alone rather than destroying someone's chest.
   */
  private void levelPlatTo(BoundingBox plat, int planeY) {
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (int x = plat.minX(); x <= plat.maxX(); x++) {
      for (int z = plat.minZ(); z <= plat.maxZ(); z++) {
        // Cut everything above the plane, following the real surface up rather
        // than a fixed height: a mound taller than one building's headroom must
        // still come off, or the buildings raise INTO it and end up buried. A
        // sane ceiling stops a column under a mountain from running away.
        int clearTo = planeY + SitePreparation.CLEARANCE_HEIGHT;
        int surface = planeY;
        while (surface < planeY + FOUNDING_MAX_CUT
            && !level.getBlockState(pos.set(x, surface, z)).isAir()) {
          surface++;
        }
        int top = Math.max(clearTo, surface);
        // Cut everything above the plane down to it, clearing the footprint to a
        // single course before the buildings drop on. (Raising the whole camp a
        // block is done by planeY above, not by leaving this course uncleared.)
        for (int y = planeY; y < top; y++) {
          pos.set(x, y, z);
          if (!level.getBlockState(pos).isAir() && level.getBlockEntity(pos) == null) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
          }
        }
        // Fill everything below the plane down to solid, following the surface
        // DOWN, so the low side of a slope does not leave a building floating over
        // a gap. Bounded so a void does not fill forever.
        for (int y = planeY - 1; y >= planeY - FOUNDING_MAX_FILL; y--) {
          pos.set(x, y, z);
          var state = level.getBlockState(pos);
          if (state.isAir() || !state.getFluidState().isEmpty()) {
            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 3);
          } else {
            break; // solid ground reached; the rest of the column is fine
          }
        }
      }
    }
  }

  /** Places the gathering-point campfire if the datapack defines one and the block isn't there yet. */
  private void placeCampfireIfMissing() {
    BlockPos fire = gatheringPointPos();
    if (fire != null && level != null && !level.getBlockState(fire).is(Blocks.CAMPFIRE)) {
      level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState(), 3);
      com.quzzar.villagelife.savedata.PlacedBlockStore.get(level).markVillagePlaced(fire);
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

  /**
   * The lit gathering-point campfire block itself, or null when it is missing or
   * doused. Unlike {@link #getGatheringPoint} this is the fire, not the standing
   * spot beside it: the idle cook roasts food on it (CookStep).
   */
  @javax.annotation.Nullable
  public BlockPos getCampfire() {
    BlockPos fire = gatheringPointPos();
    if (fire == null || level == null) {
      return null;
    }
    var state = level.getBlockState(fire);
    return state.is(Blocks.CAMPFIRE) && state.getValue(CampfireBlock.LIT) ? fire : null;
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
    InstantBuildStructure struct = new InstantBuildStructure(
        new Building(buildingName, Rotation.NONE), random, level);

    // The same site search a village uses for itself, rather than a fixed
    // offset from the centre. The fixed offset dropped every dev-placed
    // building on the same spot and ignored the claim grid entirely, so one
    // landed inside the footprint of a project already under way and was
    // demolished by it — taking a market's chest, and its treasury, with it.
    BlockPos site = LocationValidator.findValidLocation(level,
        BlockPos.of(getTownCenter().getCenterLocation()).below(), struct.getBounds(), this, random);
    if (site.equals(BlockPos.ZERO)) {
      Villagelife.LOGGER.info("Village '{}' has nowhere to put a {}", name, buildingName);
      return false;
    }

    BuildingInfo placedInfo = struct.getBuilding().getInfo();
    if (!struct.setOriginLocation(site.below(placedInfo == null ? 0 : placedInfo.getSink()), claimGrid)
        .buildInstantly()) {
      return false;
    }
    addBuilding(struct.getBuilding());
    return true;
  }

  protected void addBuilding(Building building) {
    this.buildings.put(building.getUUID(), building);
    this.brain.processNewBuilding(building, unassignedBeds, unassignedJobs);
    fellTreesOver(building);
    this.capabilities = null;
    // Raising something changes what will fit next, so what the village had
    // learned about its room no longer applies.
    this.siteMemory.clear();
  }

  /** What the village has learned about its own room (never persisted). */
  public com.quzzar.villagelife.village.buildings.SiteMemory getSiteMemory() {
    return this.siteMemory;
  }

  /**
   * The same building at a new level. Deliberately not {@link #addBuilding}:
   * registering it as new would hand out a second set of beds, jobs and
   * containers on top of the ones its workers are already holding. What the
   * definition GAINED is picked up by reconciliation instead — stations by
   * JobClaiming, beds and stores here — which is the only safe direction,
   * because what it lost is released by the same pass (docs/building-spec.md).
   */
  protected void replaceBuilding(Building upgraded) {
    this.buildings.put(upgraded.getUUID(), upgraded);
    this.brain.registerNewContainers(upgraded);
    JobClaiming.registerMissingStations(this);
    JobClaiming.registerMissingBeds(this);
    fellTreesOver(upgraded);
    this.capabilities = null;
    Villagelife.LOGGER.info("Village '{}' finished upgrading to {}", name, upgraded.getName());
  }

  /**
   * Whatever natural tree still stands over a building the moment it goes up
   * comes down with it, whole (docs/site-selection.md). Founding cuts only the
   * footprint's own columns and the prepare phase clears a fixed headroom, so
   * what survives either is the top of a tall tree, a trunk remnant, or a
   * branch reaching in from a neighbour: all of it a tree, none of it the
   * village's, and none of it wanted floating over a roof. Every way a
   * building arrives passes through here, founding, the dev command, a
   * finished project and an upgrade alike. The wood goes to village storage,
   * as clearing yields do; what will not fit lies where the tree stood, as it
   * would for anyone else who felled it.
   */
  private void fellTreesOver(Building building) {
    if (level == null) {
      return;
    }
    BoundingBox footprint = BuildingUpgrade.footprintOf(level, building);
    if (footprint == null) {
      return;
    }
    BlockPos origin = BlockPos.of(building.getOriginLocation());
    BoundingBox volume = new BoundingBox(
        origin.getX() + footprint.minX(), origin.getY() + footprint.minY(), origin.getZ() + footprint.minZ(),
        origin.getX() + footprint.maxX(), origin.getY() + footprint.maxY(), origin.getZ() + footprint.maxZ());
    List<TreeFelling.FelledTree> felled = TreeFelling.fellOver(level, volume);
    if (felled.isEmpty()) {
      return;
    }
    int logs = 0;
    for (TreeFelling.FelledTree tree : felled) {
      for (ItemStack drop : tree.drops()) {
        logs += drop.getCount();
        ItemStack left = storeAwayFrom(drop, List.of());
        if (!left.isEmpty()) {
          Block.popResource(level, tree.struck(), left);
        }
      }
    }
    Villagelife.LOGGER.info("Village '{}' felled {} tree(s) standing over its new {}: {} logs to store",
        name, felled.size(), building.getName(), logs);
  }

  /**
   * What this village can currently do (#55): derived from the buildings
   * standing right now, never stored. Cached between recomputes because
   * resolving walks every building.
   */
  public java.util.Set<String> getCapabilities() {
    if (capabilities == null) {
      capabilities = com.quzzar.villagelife.village.buildings.VillageCapabilities.resolve(this);
    }
    return capabilities;
  }

  /** True when the village can do the named thing, whatever a datapack calls it. */
  public boolean canDo(String capability) {
    return getCapabilities().contains(capability);
  }

  /** The wall being raised around the village, or null when there is none. */
  public WallProject getWallProject() {
    return wallProject;
  }

  /** How far beyond the built area the wall rings the village (docs/walls.md). */
  private static final int WALL_PADDING = 8;
  /** Buildings a village needs before it is worth walling at all. */
  private static final int WALL_MIN_BUILDINGS = 5;
  /** Buildings past which a village walls itself even absent any recent threat. */
  private static final int WALL_LARGE_BUILDINGS = 8;
  /** Recent-death weight above which a village wants defending. */
  private static final float WALL_SAFETY_THRESHOLD = 0.25F;

  /**
   * Considers raising or upgrading the village wall, and starts it if the moment
   * is right (docs/walls.md). A wall is a large safety project, so it waits until
   * the village is established, then goes up when there has been a threat or the
   * town is simply large enough to want one. Returns true when a wall was
   * started, so the caller does not also spend an LLM call choosing a building.
   */
  private boolean maybeStartWall() {
    if (level == null || getTownCenter() == null || buildings.size() < WALL_MIN_BUILDINGS) {
      return false;
    }
    VillageAttractiveness report = getAttractiveness();
    boolean threatened = report != null && report.deathImpact() > WALL_SAFETY_THRESHOLD;
    if (!threatened && buildings.size() < WALL_LARGE_BUILDINGS) {
      return false;
    }
    // Wood first; once a wooden wall stands, the same safety pull upgrades it to
    // stone on the very same ring.
    WallTier desired = wallProject == null ? WallTier.WOOD : wallProject.getTier().next();
    if (desired == null) {
      return false; // already walled in stone; there is nothing more to raise
    }
    return startWall(desired);
  }

  /**
   * Rings the village with a wall of the given tier: traces the ring from the
   * claim (a fresh line for a new wall, the standing one for an upgrade), checks
   * the village can afford it, and opens the project the builder then raises.
   * Public so a command can raise a wall on demand to watch it happen.
   */
  public boolean startWall(WallTier tier) {
    if (level == null || getTownCenter() == null) {
      return false;
    }
    List<Long> ring;
    java.util.Set<Long> gates;
    List<Integer> ground;
    if (wallProject != null && tier == wallProject.getTier().next()) {
      ring = wallProject.getRing(); // an upgrade re-walks the standing ring
      gates = wallProject.getGates();
      ground = wallProject.getGround(); // and its captured ground, not the wall now standing there
    } else {
      ring = traceWallRing(WALL_PADDING);
      gates = wallGates(WALL_PADDING);
      ground = com.quzzar.villagelife.village.buildings.WallRaiser.groundProfile(level, ring);
    }
    if (ring.isEmpty()) {
      return false;
    }
    // A rough bill, a course or two of slack per column for the slopes it steps
    // down. The exact draw happens column by column as it builds.
    int estimate = ring.size() * (tier.height() + 2);
    if (com.quzzar.villagelife.village.buildings.Materials.counted(stockTally(), tier.material()) < estimate) {
      maybeLogShortage(new ItemStack(tier.material(), estimate));
      return false;
    }
    wallProject = new WallProject(new ArrayList<>(ring), new HashSet<>(gates),
        new ArrayList<>(ground), tier);
    Villagelife.LOGGER.info("Village '{}' raises a {} wall around itself: {} segments",
        name, tier.name().toLowerCase(), ring.size());
    return true;
  }

  /**
   * Rings the village with a finished wall in one pass, free of materials: a dev
   * command's way to see the geometry (terrain-following, gateways, tier) at once,
   * without waiting for the builder to walk the ring.
   */
  public int devBuildWall(WallTier tier) {
    if (level == null) {
      return 0;
    }
    List<Long> ring = traceWallRing(WALL_PADDING);
    java.util.Set<Long> gates = wallGates(WALL_PADDING);
    if (ring.isEmpty()) {
      return 0;
    }
    List<Integer> ground =
        com.quzzar.villagelife.village.buildings.WallRaiser.groundProfile(level, ring);
    for (int i = 0; i < ring.size(); i++) {
      long column = ring.get(i);
      int x = BlockPos.getX(column);
      int z = BlockPos.getZ(column);
      boolean gate = gates.contains(column);
      int base = ground.get(i);
      int floor = com.quzzar.villagelife.village.buildings.WallRaiser.seamFloor(ground, i);
      int[] range = com.quzzar.villagelife.village.buildings.WallRaiser
          .segmentRange(level, x, z, tier, gate, base, floor);
      if (range != null) {
        com.quzzar.villagelife.village.buildings.WallRaiser.place(level, x, z, range, tier);
        if (gate && getTownCenter() != null) {
          com.quzzar.villagelife.village.buildings.WallRaiser.placeGateDoor(level, x, z, range[2],
              com.quzzar.villagelife.village.buildings.WallRaiser.gateFacing(x, z,
                  BlockPos.of(getTownCenter().getCenterLocation())));
        }
      }
    }
    wallProject = WallProject.completed(new ArrayList<>(ring), new HashSet<>(gates),
        new ArrayList<>(ground), tier);
    Villagelife.LOGGER.info("Village '{}' ringed with a {} wall (dev): {} segments",
        name, tier.name().toLowerCase(), ring.size());
    return ring.size();
  }

  /**
   * The ring the wall follows: the claim's bounding box pushed out by padding and
   * walked as one continuous loop, so the builder treads it without backtracking.
   * Y is left at zero; each column's real height is read from the surface when it
   * is raised (docs/walls.md).
   */
  public List<Long> traceWallRing(int padding) {
    ensureClaimBounds();
    if (claimGrid.isEmpty()) {
      return List.of();
    }
    int x0 = claimMinX - padding;
    int x1 = claimMaxX + padding;
    int z0 = claimMinZ - padding;
    int z1 = claimMaxZ + padding;
    List<Long> ring = new ArrayList<>();
    for (int x = x0; x <= x1; x++) {
      ring.add(BlockPos.asLong(x, 0, z0)); // north edge, west to east
    }
    for (int z = z0 + 1; z <= z1; z++) {
      ring.add(BlockPos.asLong(x1, 0, z)); // east edge, north to south
    }
    for (int x = x1 - 1; x >= x0; x--) {
      ring.add(BlockPos.asLong(x, 0, z1)); // south edge, east to west
    }
    for (int z = z1 - 1; z > z0; z--) {
      ring.add(BlockPos.asLong(x0, 0, z)); // west edge, south to north
    }
    return ring;
  }

  /** A gateway at the midpoint of each edge, so every side of the town has a way in. */
  private java.util.Set<Long> wallGates(int padding) {
    ensureClaimBounds();
    if (claimGrid.isEmpty()) {
      return java.util.Set.of();
    }
    int mx = (claimMinX + claimMaxX) / 2;
    int mz = (claimMinZ + claimMaxZ) / 2;
    int x0 = claimMinX - padding;
    int x1 = claimMaxX + padding;
    int z0 = claimMinZ - padding;
    int z1 = claimMaxZ + padding;
    return java.util.Set.of(
        BlockPos.asLong(mx, 0, z0),
        BlockPos.asLong(mx, 0, z1),
        BlockPos.asLong(x1, 0, mz),
        BlockPos.asLong(x0, 0, mz));
  }

  private void checkCurrentProject() {

    if (wallProject != null && !wallProject.isComplete()) {
      return; // the wall holds the village while it rises, like any project
    }

    if (currentProject != null) {
      if (currentProject.getProgress() == BuildProgress.COMPLETE) {
        Villagelife.LOGGER.debug("Since current project is complete, adding it and setting to null");

        Building finished = currentProject.getBuilding();
        // An upgrade finishes as the SAME building at a new level, so it is put
        // back under the id it always had rather than added as a new one: the
        // workers holding assignments against that id keep them.
        if (buildings.containsKey(finished.getUUID())) {
          replaceBuilding(finished);
        } else {
          addBuilding(finished);
        }
        currentProject = null;

      } else if (currentProject.isGathering()) {
        // A gathering project holds the village - it plans nothing new while it
        // waits - so a gather that never completes has to be abandoned. But
        // "waiting" and "stuck" are different: a stack of logs in the
        // quartermaster's pack on its way to the storehouse is a delivery in
        // flight, not a missing recipe, and the builder (dormant on an empty
        // chest) re-engages the instant it is shelved. So the clock runs slow
        // while the materials exist SOMEWHERE in the village and fast once they
        // are genuinely gone - traded away, or never gathered. Either way a hard
        // ceiling abandons a project whose materials can be seen but never reach
        // a chest, so a worker that cannot path to a delivery cannot freeze a
        // build for good. Any partial recipe stays in the builder's pack toward
        // whatever they gather next.
        gatheringChecks++;
        int cap = recipeMateriallyPossible(currentProject)
            ? MAX_TRANSIT_CHECKS
            : MAX_GATHERING_CHECKS;
        if (gatheringChecks > cap) {
          Villagelife.LOGGER.info("Village '{}' abandons {}: its recipe never came together",
              name, currentProject.getBuilding().getInfo().getName());
          currentProject = null;
          gatheringChecks = 0;
        }

      } else {
        // Construction has begun; a gather that committed does not linger.
        gatheringChecks = 0;
      }
    } else {

      // Choosing is asynchronous: the rules filter to legal, affordable options
      // and the brain picks among them, which may take a moment or never
      // answer. One decision is in flight at a time; the project starts when
      // the answer lands.
      if (projectDecisionPending || time < planningQuietUntil) {
        return;
      }
      // Nothing can be sited on ground that is not loaded, and asking the brain
      // to choose would spend a model call on an answer no one can act on. A
      // village out of everyone's sight waits instead of planning.
      Building centre = getTownCenter();
      if (level != null && centre != null
          && !level.hasChunkAt(BlockPos.of(centre.getCenterLocation()))) {
        return;
      }
      // A safety-triggered wall is decided by rules, not the brain, so it is
      // weighed before an LLM call is spent choosing a building (docs/walls.md).
      if (maybeStartWall()) {
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
      if (buildingInfo.getUpgradesFrom() != null) {
        return startUpgrade(buildingInfo);
      }
      Building building = new Building(buildingInfo.getName(), Rotation.values()[random.nextInt(Rotation.values().length)]);
      StructureInProgress project = new StructureInProgress(building, random);
      project.attach(level);
      BoundingBox bounds = project.getStructureTemplate().getBoundingBox(project.getStructurePlaceSettings(),
          BlockPos.ZERO);

      BlockPos projectLocation = LocationValidator.findValidLocation(level,
          BlockPos.of(getTownCenter().getCenterLocation()).below(), bounds, this, random);

      if (projectLocation == BlockPos.ZERO) {
        // Remember it, or the planner will keep choosing this and failing here
        // for as long as the village stands hemmed in.
        siteMemory.noSiteFor(bounds, time);
        Villagelife.LOGGER.debug("No site for '{}' ({}x{}); the village will not offer it again for now",
            buildingInfo.getName(), bounds.getXSpan(), bounds.getZSpan());
        return false;
      }
      return beginProject(project, bounds, projectLocation);
  }

  /**
   * Rebuilds a standing building one level up, on its own ground and in its own
   * orientation (docs/building-spec.md). No site search: an upgrade goes where
   * the building already is, which is also why the fit check happened when the
   * option was offered rather than now.
   *
   * The old building's stores are carried out to the rest of the village first.
   * If they will not fit anywhere, that is a storage shortage and the upgrade
   * waits rather than proceeding, because nothing is ever destroyed to make
   * room for construction.
   */
  private boolean startUpgrade(BuildingInfo buildingInfo) {
    Building standing = com.quzzar.villagelife.village.buildings.BuildingUpgrade
        .standingSource(this, buildingInfo);
    if (standing == null) {
      Villagelife.LOGGER.debug("Village '{}' has nothing standing to upgrade into {}",
          name, buildingInfo.getName());
      return false;
    }
    if (!com.quzzar.villagelife.village.buildings.BuildingUpgrade.clearStorage(this, standing)) {
      // Not a missing material: the village is out of somewhere to put things,
      // which is what a village short of chests actually feels.
      maybeLogShortage(new ItemStack(net.minecraft.world.item.Items.CHEST, 1));
      Villagelife.LOGGER.debug("Village '{}' cannot empty its {} yet, so the upgrade waits",
          name, standing.getName());
      return false;
    }

    Building upgraded = Building.upgradeOf(standing, buildingInfo.getName());
    StructureInProgress project = new StructureInProgress(upgraded, random);
    project.attach(level);
    BoundingBox bounds = project.getStructureTemplate()
        .getBoundingBox(project.getStructurePlaceSettings(), BlockPos.ZERO);
    Villagelife.LOGGER.info("Village '{}' is upgrading its {} to {}",
        name, standing.getName(), buildingInfo.getName());
    return beginProject(project, bounds, BlockPos.of(standing.getOriginLocation()));
  }

  /**
   * Starts construction of an already-chosen building on already-chosen ground.
   * Separate from the site search so the same path can be driven from a
   * command: preparation only ever runs on ground the village did not pick for
   * itself if someone asks for it, which is the only way to watch it happen on
   * purpose rather than waiting for a village to run out of free sites.
   */
  public boolean startProjectAt(BuildingInfo buildingInfo, BlockPos location) {
    if (currentProject != null || level == null) {
      return false;
    }
    // An upgrade has only one legal site — where the building already stands —
    // so a chosen position cannot apply to it.
    if (buildingInfo.getUpgradesFrom() != null) {
      return startUpgrade(buildingInfo);
    }
    Building building = new Building(buildingInfo.getName(),
        Rotation.values()[random.nextInt(Rotation.values().length)]);
    StructureInProgress project = new StructureInProgress(building, random);
    project.attach(level);
    BoundingBox bounds = project.getStructureTemplate().getBoundingBox(project.getStructurePlaceSettings(),
        BlockPos.ZERO);
    return beginProject(project, bounds, location);
  }

  private boolean beginProject(StructureInProgress project, BoundingBox bounds, BlockPos projectLocation) {
    BuildingInfo buildingInfo = project.getBuilding().getInfo();

    // Ground the site owes before a single structure block is placed. A free
    // site returns nothing; ground that cannot be prepared at all returns
    // nothing too unless it is asked, and building into it would place a
    // structure inside a cliff.
    //
    // An upgrade is the exception: the building already standing here is not
    // ground to prepare, it is what the new template replaces, so only the ring
    // the larger footprint reaches into is measured.
    Building standing = buildings.get(project.getBuilding().getUUID());
    BoundingBox occupied = standing == null ? null
        : com.quzzar.villagelife.village.buildings.BuildingUpgrade.footprintOf(level, standing);
    // An upgrade is handed the standing building's origin, which for a sunk
    // building is below the ground; the ground itself is what gets prepared,
    // and the new template is seated by its own sink from there.
    if (standing != null && standing.getInfo() != null) {
      projectLocation = projectLocation.above(standing.getInfo().getSink());
    }
    var prep = com.quzzar.villagelife.village.buildings.SitePreparation
        .planWork(level, this, projectLocation, bounds, occupied);
    if (!prep.possible()) {
      Villagelife.LOGGER.info("Village '{}' cannot prepare the ground at {} for {}",
          name, projectLocation.toShortString(), buildingInfo.getName());
      return false;
    }

    Villagelife.LOGGER.info("Village '{}' will build {} at {}: gathering materials",
        name, buildingInfo.getName(), projectLocation.toShortString());

    // No payment here any more. The project opens in GATHERING, and the builder
    // carries the recipe home and has it consumed at commit (StructureInProgress
    // .commitFromBuilder), so the materials leave the village through a pack
    // rather than vanishing from a chest the instant a site is chosen.
    currentProject = project.setOriginLocation(projectLocation.below(buildingInfo.getSink()));
    gatheringChecks = 0;

    if (!prep.isEmpty()) {
      currentProject.setPrepWork(prep);
      Villagelife.LOGGER.info("Village '{}' is clearing ground for {}: {} blocks to move",
          name, buildingInfo.getName(), prep.size());
    }

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
    VillageProfile.tickCounted(time);
    attach(level);

    long tb = VillageProfile.start();
    this.brain.update();
    VillageProfile.end("brain bookkeeping", tb);

    // Hand any free beds to residents who arrived bedless (e.g. before the centre
    // registered its beds), so a village with spare beds never leaves someone a
    // permanent campfire camper. Self-gating: no-ops when no beds are free.
    this.brain.reconcileBeds(people, bedAssignments, unassignedBeds);

    // Recompute attractiveness every 10 seconds, phase-staggered per village so
    // many villages don't all scan their containers on the same tick.
    if (attractiveness == null || (time + Math.floorMod(id.hashCode(), 10)) % 10 == 0) {
      long t = VillageProfile.start();
      attractiveness = computeAttractiveness();
      VillageProfile.end("attractiveness", t);
    }

    // Phase-staggered per village, like every other pass here. Without the
    // stagger all loaded villages decide what to build on the SAME tick every
    // 60s, and 'decide' is a model call - so a dozen villages fire a dozen LLM
    // requests at the same instant, once a minute, saturating the one model
    // they share with the player. This is the pile-up the priority fix would
    // otherwise have to absorb; spreading the checks stops it forming.
    if ((time + Math.floorMod(id.hashCode(), CHECK_PROJECT_PROGRESS)) % CHECK_PROJECT_PROGRESS == 0) {

      long t = VillageProfile.start();
      checkCurrentProject();
      VillageProfile.end("project", t);
      // Chests come and go — broken, built over, replaced by an upgrade — and
      // the village should not keep sending people to the ones that went.
      long tp = VillageProfile.start();
      this.brain.pruneMissingContainers(level);
      VillageProfile.end("prune containers", tp);

    }

    // A village with a market it can trade from does business on its own
    // account, unattended, at the bank's punishing rate (docs/economy.md).
    if (time % com.quzzar.villagelife.economy.VillageTrading.TRADE_INTERVAL_SECONDS == 0) {
      long t = VillageProfile.start();
      com.quzzar.villagelife.economy.VillageTrading.consider(this, level);
      VillageProfile.end("unattended trade", t);
    }

    int populationInterval = Math.max(5, com.quzzar.villagelife.configuration.VillagelifeConfig.PopulationCheckIntervalSeconds);
    if ((time + Math.floorMod(id.hashCode(), populationInterval)) % populationInterval == 0) {
      long t = VillageProfile.start();
      checkPopulation();
      VillageProfile.end("population", t);

      // Backfill the display-only village name onto residents that predate the
      // field (it is normally set at arrival).
      for (UUID personId : people) {
        RealPerson person = getPerson(level, personId);
        if (person != null && person.getVillageName().isBlank()) {
          person.setVillageName(this.name);
        }
      }
    }

    long tt = VillageProfile.start();
    tickTravelers();
    VillageProfile.end("travelers", tt);

    long tj = VillageProfile.start();
    JobClaiming.tick(this, level);
    VillageProfile.end("jobs", tj);

    // Supply-gated capabilities follow what the chests hold, so the derived set
    // is dropped periodically rather than only when buildings change.
    if ((time + Math.floorMod(id.hashCode(), 30)) % 30 == 0) {
      capabilities = null;
    }

    // Villagers making up their minds about what has happened to them lately.
    int reflectInterval = com.quzzar.villagelife.relationships.ReflectionService.REFLECT_INTERVAL_SECONDS;
    if ((time + Math.floorMod(id.hashCode(), reflectInterval)) % reflectInterval == 0) {
      long t = VillageProfile.start();
      com.quzzar.villagelife.relationships.ReflectionService.tick(this, level);
      VillageProfile.end("reflection", t);
    }

    // Familiarity: not a judgement, just the pull of sharing a workplace or a fire.
    int driftInterval = com.quzzar.villagelife.relationships.RelationshipDrift.DRIFT_INTERVAL_SECONDS;
    if ((time + Math.floorMod(id.hashCode(), driftInterval)) % driftInterval == 0) {
      long t = VillageProfile.start();
      com.quzzar.villagelife.relationships.RelationshipDrift.tick(this);
      VillageProfile.end("relationship drift", t);
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
    // A village that has not managed to look inside its own chests yet holds
    // still rather than acting on a cold ledger (#65). This lasts only until
    // the first time any of its storage is resident.
    if (!brain.hasReadStores()) {
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

    long deadline = level.getGameTime()
        + com.quzzar.villagelife.configuration.VillagelifeConfig.TravelTimeoutSeconds * 20L;
    // Newcomers appear AT the campfire rather than trekking in from the village edge:
    // they spawn on the gathering spot beside the fire and are counted on the very next
    // traveler tick, which already sees them within arrival range of the fire. Wanderers
    // still walk in from wherever they roam; departures still head out to the edge.
    BlockPos fire = getGatheringPoint();
    BlockPos spawnPos = fire;

    // Wanderers before spawns (#59): someone already roaming the world is
    // recruited ahead of conjuring anyone new, so the same souls circulate
    // between villages carrying their stats, memories, and relationships.
    RealPerson wanderer = findNearbyWanderer();
    if (wanderer != null) {
      wanderer.setVillage(id);
      wanderer.setOccupation(Occupation.WANDERER);
      wanderer.endRoaming();
      pendingArrivals.add(new PendingTraveler(wanderer.getUUID(), deadline, fire.asLong()));
      Villagelife.LOGGER.info("'{}' the wanderer was drawn to village '{}' and is coming to join",
          wanderer.getFullName(), name);
      return;
    }

    // Then the road: someone who passed beyond the horizon from some other
    // village comes back into the world at this one's edge and walks in, or at
    // the fire itself when the edge is not loaded. Only with nobody on the road
    // is anyone conjured (docs/population-and-labor.md).
    BlockPos edge = edgeSpawnPos();
    RealPerson returning = VillageManager.get(level).getWanderers().draw(level, edge != null ? edge : fire);
    if (returning != null) {
      returning.setVillage(id);
      returning.setOccupation(Occupation.WANDERER);
      returning.endRoaming();
      level.addFreshEntity(returning);
      pendingArrivals.add(new PendingTraveler(returning.getUUID(), deadline, fire.asLong()));
      Villagelife.LOGGER.info("'{}' came in off the road to village '{}' and is walking to the fire",
          returning.getFullName(), name);
      return;
    }

    PersonaSpawner.trySpawn(level, spawnPos, person -> {
      person.setVillage(id);
      person.setOccupation(Occupation.WANDERER);
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
    return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
  }

  /** Dev command hook: one person leaves as if the mood had collapsed. Who, or null if nobody loaded could. */
  @Nullable
  public RealPerson forceEmigration() {
    return tryEmigration();
  }

  @Nullable
  private RealPerson tryEmigration() {
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
      return null;
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
    return person;
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
        // Whatever they were here, out there they are a wanderer: the title
        // changes at the edge. They keep the pack and tools they walked out
        // with, and set out straight away from the village they are leaving.
        person.setOccupation(Occupation.WANDERER);
        Building center = getTownCenter();
        double heading = random.nextDouble() * Math.PI * 2;
        if (center != null) {
          BlockPos centerPos = BlockPos.of(center.getCenterLocation());
          heading = Math.atan2(exit.getZ() - centerPos.getZ(), exit.getX() - centerPos.getX());
        }
        person.beginRoaming(exit, heading, now);
        person.reloadState();
        iterator.remove();
        // The world holds only so many wanderers on foot (#59): past the cap, a
        // leaver passes beyond the horizon from the edge instead of walking there.
        if (loadedWandererCount() > com.quzzar.villagelife.configuration.VillagelifeConfig.WandererCap) {
          Villagelife.LOGGER.info("'{}' left '{}' with too many already on the roads nearby", person.getFullName(), name);
          person.crossHorizon();
        } else {
          Villagelife.LOGGER.info("'{}' left '{}' and set out {}", person.getFullName(), name,
              person.roamHeadingName());
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

  /** True while a brain pick for an open post is in flight (see {@link JobClaiming}). */
  public boolean isJobDecisionPending() {
    return jobDecisionPending;
  }

  public void setJobDecisionPending(boolean pending) {
    this.jobDecisionPending = pending;
  }

  /** Beds nobody sleeps in yet. Returned live: reconciliation adds to it. */
  public List<BedAssignment> getUnassignedBeds() {
    return unassignedBeds;
  }

  /** Snapshot of every bed that belongs to someone. */
  public Map<UUID, BedAssignment> getBedAssignmentsView() {
    return Map.copyOf(bedAssignments);
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
    preferWorkplaceBed(personId, job.getBuildingUUID());
  }

  /**
   * A worker moves in above the shop. When someone takes a job in a building
   * that has a free bed, they claim it and release whatever bed they held
   * before, so a live-in workplace (a bed in its definition) reliably houses
   * its own worker rather than handing that bed to the first newcomer in line.
   *
   * A no-op for the common case: a workplace with no beds leaves the worker
   * wherever they already sleep. Only the building's own residents ever compete
   * for its beds, and the first worker assigned wins a single bed, so a market
   * with three merchants and one bed houses one of them and no more.
   */
  private void preferWorkplaceBed(UUID personId, UUID buildingUUID) {
    int free = -1;
    for (int i = 0; i < unassignedBeds.size(); i++) {
      if (unassignedBeds.get(i).getBuildingUUID().equals(buildingUUID)) {
        free = i;
        break;
      }
    }
    if (free < 0) {
      return; // the workplace has no spare bed; keep the one they have
    }
    BedAssignment current = bedAssignments.get(personId);
    if (current != null && current.getBuildingUUID().equals(buildingUUID)) {
      return; // already sleeping at work
    }
    BedAssignment workplaceBed = unassignedBeds.remove(free);
    bedAssignments.put(personId, workplaceBed.setPersonUUID(personId));
    if (current != null) {
      unassignedBeds.add(current.setPersonUUID(null)); // their old bed reopens
    }
  }

  /** The quartermaster raises this when the storehouse overflows; the planner reads it. */
  public void setStorageStrained(boolean strained) {
    this.storageStrained = strained;
  }

  public boolean isStorageStrained() {
    return this.storageStrained;
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

  /**
   * Whether the recipe for the current build still exists anywhere in the
   * village - in its chests OR in a villager's pack - so a gather waiting on a
   * delivery in flight is not mistaken for one whose materials are gone.
   *
   * Kept cheap the way the stock tally is: chests answer first, from a single
   * sweep, and packs are only counted for the items chests could not cover.
   * Most builds never touch the pack scan at all.
   */
  public boolean recipeMateriallyPossible(StructureInProgress project) {
    if (level == null) {
      return true; // cannot judge without a world; never abandon on a guess
    }
    Map<Item, Integer> inChests = brain.stockTally(level);
    Map<Item, Integer> inPacks = null; // computed lazily, and only once
    // The delta for an upgrade, the whole recipe otherwise: an upgrade is not
    // "materially impossible" just because the village lacks a second building's
    // worth, only if it lacks what the upgrade still adds.
    for (ItemStack cost : com.quzzar.villagelife.village.buildings.BuildingUpgrade
        .effectiveCost(this, project.getBuilding().getInfo())) {
      int have = inChests.getOrDefault(cost.getItem(), 0);
      if (have >= cost.getCount()) {
        continue; // the chests alone cover this one; no pack scan needed
      }
      if (inPacks == null) {
        inPacks = tallyVillagerPacks();
      }
      if (have + inPacks.getOrDefault(cost.getItem(), 0) < cost.getCount()) {
        return false; // genuinely short: this material has left the village
      }
    }
    return true;
  }

  /** Everything the villagers are carrying, counted once - the in-transit half of stock. */
  private Map<Item, Integer> tallyVillagerPacks() {
    Map<Item, Integer> tally = new HashMap<>();
    if (level == null) {
      return tally;
    }
    for (UUID id : people) {
      RealPerson person = getPerson(level, id);
      if (person == null) {
        continue;
      }
      for (int slot = 0; slot < person.personMainInv.getContainerSize(); slot++) {
        ItemStack stack = person.personMainInv.getItem(slot);
        if (stack != null && !stack.isEmpty()) {
          tally.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
      }
    }
    return tally;
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

  /**
   * Whether {@code pos} is within {@code padding} blocks of the claim - i.e. inside the claim's
   * bounding box grown by padding. Used to announce the village-entry title a little before the
   * player actually reaches a building. O(1) after a lazy bounds recompute.
   */
  public boolean hasClaimedWithin(BlockPos pos, int padding) {
    if (claimGrid.isEmpty()) {
      return false;
    }
    ensureClaimBounds();
    return pos.getX() >= claimMinX - padding && pos.getX() <= claimMaxX + padding
        && pos.getZ() >= claimMinZ - padding && pos.getZ() <= claimMaxZ + padding;
  }

  /** Recomputes the claim's x/z bounds, but only when the claim has changed size (it only grows). */
  private void ensureClaimBounds() {
    if (claimBoundsAtSize == claimGrid.size()) {
      return;
    }
    claimBoundsAtSize = claimGrid.size();
    claimMinX = Integer.MAX_VALUE; claimMaxX = Integer.MIN_VALUE;
    claimMinZ = Integer.MAX_VALUE; claimMaxZ = Integer.MIN_VALUE;
    for (long packed : claimGrid) {
      BlockPos p = BlockPos.of(packed);
      claimMinX = Math.min(claimMinX, p.getX());
      claimMaxX = Math.max(claimMaxX, p.getX());
      claimMinZ = Math.min(claimMinZ, p.getZ());
      claimMaxZ = Math.max(claimMaxZ, p.getZ());
    }
  }

  public BlockPos getNearestContainer(BlockPos location) {
    return this.brain.getNearestContainer(location);
  }

  /** The nearest village container that is not one of the given positions. */
  public BlockPos getNearestContainer(BlockPos location, java.util.Collection<BlockPos> excluding) {
    return this.brain.getNearestContainer(location, excluding);
  }

  /**
   * Every container this village counts as its own, for anyone asking whose it
   * is: the shared stores and the homes' own chests alike, since taking from
   * either is theft.
   */
  public java.util.Set<BlockPos> getVillageContainerPositions() {
    java.util.Set<BlockPos> positions = this.brain.containerPositions();
    positions.addAll(PersonalChest.allChests(this));
    return positions;
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

  /** Puts a stack anywhere in village storage except the containers listed. */
  public ItemStack storeAwayFrom(ItemStack stack, java.util.Collection<BlockPos> excluding) {
    if (level == null) {
      return stack;
    }
    return this.brain.storeAwayFrom(level, stack, excluding);
  }

  public boolean hasItemStackInVillage(ItemStack itemStack) {
    return level != null && this.brain.hasItemStackInVillage(level, itemStack);
  }

  /** What the village holds, tallied in one sweep of its storage (#65). */
  public java.util.Map<net.minecraft.world.item.Item, Integer> stockTally() {
    return level == null ? new java.util.HashMap<>() : this.brain.stockTally(level);
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

  /** Seconds this village has been alive, its own clock for goals and staggering. */
  public int getVillageTime() {
    return time;
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
        brain.totalImpact(com.quzzar.villagelife.village.bookkeeping.NoResourceBookkeepingEvent.class),
        brain.totalImpact(com.quzzar.villagelife.village.bookkeeping.TheftBookkeepingEvent.class));
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

  /**
   * True while this building is the one being rebuilt at a new level. Its
   * stations and beds are rubble for the duration, so whoever holds them keeps
   * the assignment and waits it out rather than standing in a building site
   * (docs/building-spec.md).
   */
  public boolean isBeingRebuilt(UUID buildingId) {
    return currentProject != null
        && currentProject.getProgress() != BuildProgress.COMPLETE
        && currentProject.getBuilding().getUUID().equals(buildingId);
  }

  public StructureInProgress getCurrentProject() {
    if (currentProject != null && level != null) {
      currentProject.attach(level);
    }
    return currentProject;
  }

}
