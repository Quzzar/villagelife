package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.savedata.PlacedBlockStore;
import com.quzzar.kithkyn.village.BlockOwnership;
import com.quzzar.kithkyn.village.Occupation;
import com.quzzar.kithkyn.village.Village;
import com.quzzar.kithkyn.village.VillageManager;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

/** Generates complete construction proposals from blocked parcels without changing the world. */
public final class RedevelopmentPlanner {
  /** A bounded search, rotated across definitions so the budget cannot permanently hide a target. */
  private static final int MAX_SURVEYS = 96;
  private static final long SEARCH_BUDGET_NANOS = 50_000_000L;
  private static final int MAX_BLOCKS_PER_REMOVAL = 32_768;
  private static final Set<String> FOOD = Set.of("GRAIN", "MEAT", "BREAD");

  private RedevelopmentPlanner() {
  }

  /** Facts about a search, kept distinct from actual offers to a model. */
  public record Search(List<ConstructionChoice> choices, int examined, Map<String, Integer> refused,
      boolean budgetExhausted, long nanos) {
    public Search {
      choices = List.copyOf(choices);
      refused = Map.copyOf(refused);
    }
  }

  /** Finds alternatives for blocked upgrades and fresh footprints with a recent failed site search. */
  public static Search find(Village village) {
    long start = System.nanoTime();
    if (village.getLevel() == null || village.getCurrentProject() != null) {
      return new Search(List.of(), 0, Map.of(), false, System.nanoTime() - start);
    }
    List<BuildingInfo> catalogue = new ArrayList<>(Buildings.catalogue(village.getStyle()));
    catalogue.sort(Comparator.comparing(BuildingInfo::getName));
    Map<String, ConstructionChoice> distinct = new LinkedHashMap<>();
    Map<String, Integer> refused = new LinkedHashMap<>();
    int examined = 0;
    boolean timedOut = false;
    int offset = catalogue.isEmpty() ? 0 : Math.floorMod(village.getVillageTime() / 10, catalogue.size());
    search:
    for (int step = 0; step < catalogue.size(); step++) {
      BuildingInfo target = catalogue.get((step + offset) % catalogue.size());
      if (Buildings.COUPLE_COTTAGE_CATEGORY.equals(target.getCategory())) {
        continue;
      }
      boolean upgradeFits = target.getUpgradesFrom() != null
          && BuildingUpgrade.standingSource(village, target) != null;
      boolean blockedFresh = !Buildings.VILLAGE_CENTER_CATEGORY.equals(target.getCategory())
          && village.getSiteMemory().ruledOut(SiteMemory.footprintOf(village.getLevel(), target.getName()),
              village.getVillageTime());
      for (Building anchor : village.getBuildings()) {
        boolean upgrade = !upgradeFits && anchor.getName().equals(target.getUpgradesFrom());
        if (!upgrade && !blockedFresh) {
          continue;
        }
        if (anchor.getInfo() == null) {
          continue;
        }
        List<Rotation> rotations = upgrade ? List.of(anchor.getRotation()) : List.of(Rotation.values());
        for (Rotation rotation : rotations) {
          BoundingBox local = targetBounds(village, target, rotation);
          BoundingBox anchorBounds = worldBounds(village, anchor);
          if (local == null || anchorBounds == null) {
            continue;
          }
          TownLayout.Footprint standing = footprint(anchorBounds);
          TownLayout.Footprint wanted = footprint(local);
          List<TownLayout.Origin> origins = upgrade
              ? TownLayout.containingOrigins(standing, wanted) : replacementOrigins(standing, wanted);
          for (TownLayout.Origin origin : origins) {
            if (upgrade && Buildings.FOUNDING_MINE_CATEGORY.equals(anchor.getInfo().getCategory())
                && (origin.x() != BlockPos.of(anchor.getOriginLocation()).getX()
                    || origin.z() != BlockPos.of(anchor.getOriginLocation()).getZ())) {
              continue;
            }
            if (examined >= MAX_SURVEYS || examined > 0 && System.nanoTime() - start >= SEARCH_BUDGET_NANOS) {
              timedOut = examined < MAX_SURVEYS;
              break search;
            }
            examined++;
            BlockPos ground = new BlockPos(origin.x(), BlockPos.of(anchor.getOriginLocation()).getY()
                + anchor.getInfo().getSink(), origin.z());
            Assessment assessment = assess(village, target, upgrade ? anchor : null, ground, rotation);
            if (assessment.plan().isEmpty()) {
              refused.merge(assessment.reason(), 1, Integer::sum);
              continue;
            }
            RedevelopmentPlan plan = assessment.plan().orElseThrow();
            String key = target.getName() + "/" + plan.mode() + "/" + plan.source() + "/"
                + plan.removed().stream().map(building -> building.getUUID().toString()).sorted().toList();
            ConstructionChoice prior = distinct.get(key);
            if (prior == null || work(plan) < work(prior.redevelopment())) {
              distinct.put(key, new ConstructionChoice(target, plan.mode(), plan));
            }
          }
        }
      }
    }
    return new Search(new ArrayList<>(distinct.values()), examined, refused, timedOut || examined >= MAX_SURVEYS,
        System.nanoTime() - start);
  }

  private static int work(RedevelopmentPlan plan) {
    return plan.blocks().size() + plan.prepBreak().size() + plan.prepFill().size();
  }

  private static List<TownLayout.Origin> replacementOrigins(TownLayout.Footprint anchor,
      TownLayout.Footprint target) {
    List<TownLayout.Origin> origins = new ArrayList<>();
    for (int x : new int[] {anchor.minX() - target.minX(), anchor.maxX() - target.maxX(),
        Math.floorDiv(anchor.minX() + anchor.maxX() - target.minX() - target.maxX(), 2)}) {
      for (int z : new int[] {anchor.minZ() - target.minZ(), anchor.maxZ() - target.maxZ(),
          Math.floorDiv(anchor.minZ() + anchor.maxZ() - target.minZ() - target.maxZ(), 2)}) {
        TownLayout.Origin origin = new TownLayout.Origin(x, z);
        if (!origins.contains(origin)) {
          origins.add(origin);
        }
      }
    }
    return origins;
  }

  public record Assessment(Optional<RedevelopmentPlan> plan, String reason) {
    static Assessment refused(String reason) {
      return new Assessment(Optional.empty(), reason);
    }
  }

  /** Surveys one exact site. The same method revalidates the selected proposal before commitment. */
  public static Assessment assess(Village village, BuildingInfo target, Building source, BlockPos ground,
      Rotation rotation) {
    BoundingBox bounds = targetBounds(village, target, rotation);
    if (bounds == null) {
      return Assessment.refused("missing target structure");
    }
    TownLayout.Footprint targetFootprint = footprint(bounds).moved(new TownLayout.Origin(ground.getX(), ground.getZ()));
    if (source != null) {
      BoundingBox standing = worldBounds(village, source);
      BlockPos origin = BlockPos.of(source.getOriginLocation());
      if (source != village.getBuilding(source.getUUID()) || !source.getName().equals(target.getUpgradesFrom())
          || rotation != source.getRotation() || standing == null
          || ground.getY() != origin.getY() + source.getInfo().getSink()
          || !contains(targetFootprint, standing.minX(), standing.minZ())
          || !contains(targetFootprint, standing.maxX(), standing.maxZ())
          || Buildings.FOUNDING_MINE_CATEGORY.equals(source.getInfo().getCategory())
              && (ground.getX() != origin.getX() || ground.getZ() != origin.getZ())) {
        return Assessment.refused("incompatible upgrade source or placement");
      }
    } else if (Buildings.VILLAGE_CENTER_CATEGORY.equals(target.getCategory())
        || Buildings.COUPLE_COTTAGE_CATEGORY.equals(target.getCategory())) {
      return Assessment.refused("target requires its existing construction workflow");
    }
    StructureInProgress active = village.getCurrentProject();
    boolean ownReservation = active != null && active.isGathering() && active.getRedevelopment() != null
        && active.getRedevelopment().plan().target().equals(target.getName())
        && active.getRedevelopment().plan().ground() == ground.asLong()
        && active.getRedevelopment().plan().rotation() == rotation;
    List<Building> removed = new ArrayList<>();
    Set<Long> exemptClaims = new HashSet<>();
    for (Building building : village.getBuildings()) {
      BoundingBox box = worldBounds(village, building);
      if (box == null) {
        return Assessment.refused("unknown standing footprint");
      }
      if (source != null && building.getUUID().equals(source.getUUID())) {
        addColumns(exemptClaims, box);
      } else if (intersects(targetFootprint, footprint(box), LocationValidator.MIN_GAP)) {
        if (building.getInfo() == null
            || Buildings.VILLAGE_CENTER_CATEGORY.equals(building.getInfo().getCategory())
            || Buildings.FOUNDING_MINE_CATEGORY.equals(building.getInfo().getCategory())) {
          return Assessment.refused("protected center or mine");
        }
        removed.add(building);
        addColumns(exemptClaims, box);
      }
    }
    if (removed.isEmpty()) {
      return Assessment.refused("no buildings need removal");
    }
    if (ownReservation) {
      addColumns(exemptClaims, bounds.moved(ground.getX(), 0, ground.getZ()));
    }
    for (int x = targetFootprint.minX() - LocationValidator.MIN_GAP;
        x <= targetFootprint.maxX() + LocationValidator.MIN_GAP; x++) {
      for (int z = targetFootprint.minZ() - LocationValidator.MIN_GAP;
          z <= targetFootprint.maxZ() + LocationValidator.MIN_GAP; z++) {
        BlockPos at = new BlockPos(x, 0, z);
        if (village.hasClaimed(at) && !exemptClaims.contains(at.asLong())) {
          return Assessment.refused("unattributed claim");
        }
        for (Village other : VillageManager.get(village.getLevel().getLevel()).getVillages().values()) {
          if (other != village && other.hasClaimed(at)) {
            return Assessment.refused("another village's claim");
          }
        }
      }
    }
    List<Building> affected = new ArrayList<>(removed);
    if (source != null) {
      affected.add(source);
    }
    String consequence = eligibility(village, affected);
    if (!consequence.isEmpty()) {
      return Assessment.refused(consequence);
    }
    if (RedevelopmentDemand.reason(village, target, affected).isEmpty()) {
      return Assessment.refused("no current need for the net capacity or capability gained");
    }
    List<RedevelopmentPlan.RemovalBlock> blocks = new ArrayList<>();
    Set<Long> removedPositions = new HashSet<>();
    Set<Long> vacatedColumns = new HashSet<>();
    if (ownReservation) {
      addColumns(vacatedColumns, bounds.moved(ground.getX(), 0, ground.getZ()));
    }
    List<MaterialAmount> salvage = List.of();
    for (Building victim : removed) {
      BoundingBox box = worldBounds(village, victim);
      addColumns(vacatedColumns, box);
      String refusal = scanRemoval(village, victim, blocks, removedPositions);
      if (!refusal.isEmpty()) {
        return Assessment.refused(refusal);
      }
      // A damaged building cannot refund a complete paid structure after its blocks were harvested.
      if (intactForSalvage(village, victim)) {
        salvage = MaterialAmount.combine(salvage, MaterialAmount.salvage(victim.getInvestment()));
      }
    }
    BoundingBox occupied = source == null ? null : worldBounds(village, source).moved(-ground.getX(), 0, -ground.getZ());
    SitePreparation.PrepWork prep = SitePreparation.planAfterRemoval(village.getLevel(), village, ground,
        bounds, occupied, removedPositions, vacatedColumns);
    if (!prep.possible()) {
      return Assessment.refused("ground remains unsuitable after removal");
    }
    PlacedBlockStore ownership = PlacedBlockStore.get(village.getLevel().getLevel());
    for (BlockPos offset : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(),
        bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
      BlockPos at = ground.below(target.getSink()).offset(offset);
      if (!village.getLevel().getLevel().hasChunkAt(at) || ownership.isPlayerPlaced(at)) {
        return Assessment.refused("unloaded or player-modified target");
      }
      if (!removedPositions.contains(at.asLong()) && (source == null || !worldBounds(village, source).isInside(at))
          && at.getY() > ground.getY() && !village.getLevel().getBlockState(at).isAir()
          && !village.getLevel().getBlockState(at).is(SitePreparation.CLEARABLE)) {
        return Assessment.refused("protected obstruction above target");
      }
    }
    for (long position : prep.toBreak()) {
      BlockPos at = BlockPos.of(position);
      if (ownership.isPlayerPlaced(at) || ownership.isVillagePlaced(at) && !removedPositions.contains(position)) {
        return Assessment.refused("protected ground preparation");
      }
    }
    List<Long> fill = new ArrayList<>(prep.toFill());
    // Removed foundations outside the new footprint also get restored, using paid dirt.
    for (Building victim : removed) {
      int floor = BlockPos.of(victim.getOriginLocation()).getY() + victim.getInfo().getSink();
      for (RedevelopmentPlan.RemovalBlock block : blocks) {
        BlockPos at = BlockPos.of(block.position());
        if (worldBounds(village, victim).isInside(at) && at.getY() <= floor
            && !contains(targetFootprint, at.getX(), at.getZ()) && !fill.contains(at.asLong())) {
          fill.add(at.asLong());
        }
      }
    }
    if (!StorageEvacuation.canEvacuate(village, affected)) {
      return Assessment.refused("nowhere for displaced contents");
    }
    ConstructionMode mode = source == null ? ConstructionMode.FRESH : ConstructionMode.UPGRADE;
    List<ItemStack> required = new ArrayList<>(ConstructionQuote.requiredFor(target, mode));
    if (!fill.isEmpty()) {
      required.add(new ItemStack(Items.DIRT, fill.size()));
    }
    blocks.sort(Comparator.comparingInt((RedevelopmentPlan.RemovalBlock block) -> BlockPos.of(block.position()).getY())
        .reversed().thenComparingLong(RedevelopmentPlan.RemovalBlock::position));
    return new Assessment(Optional.of(new RedevelopmentPlan(UUID.randomUUID(), target.getName(), mode,
        source == null ? Optional.empty() : Optional.of(source.getUUID()), ground.asLong(), rotation, targetFingerprint(village, target),
        removed, blocks, MaterialAmount.fromStacks(required), salvage, prep.toBreak(), fill)), "");
  }

  /** Only admissible transition states reach the model; worth remains its decision. */
  static String eligibility(Village village, Collection<Building> affected) {
    Set<UUID> ids = affected.stream().map(Building::getUUID).collect(java.util.stream.Collectors.toSet());
    boolean builderSurvives = village.getJobAssignmentsView().values().stream()
        .anyMatch(job -> job.getOccupation() == Occupation.BUILDER && !ids.contains(job.getBuildingUUID()));
    if (!builderSurvives) {
      return "no surviving builder workplace";
    }
    if (!village.canRehouseForRedevelopment(ids)) {
      return "insufficient general beds for displaced residents";
    }
    boolean losesFood = affected.stream().anyMatch(RedevelopmentPlanner::foodBuilding);
    boolean foodSurvives = village.getBuildings().stream().filter(building -> !ids.contains(building.getUUID()))
        .filter(RedevelopmentPlanner::foodBuilding).anyMatch(building -> village.getJobAssignmentsView().values()
            .stream().anyMatch(job -> job.getBuildingUUID().equals(building.getUUID())));
    if (losesFood && !foodSurvives) {
      return "no staffed food production remains during construction";
    }
    return "";
  }

  private static boolean foodBuilding(Building building) {
    return building.getInfo() != null && building.getInfo().getGrants().stream().anyMatch(FOOD::contains);
  }

  private static String scanRemoval(Village village, Building victim,
      List<RedevelopmentPlan.RemovalBlock> blocks, Set<Long> positions) {
    BoundingBox box = worldBounds(village, victim);
    if ((long) box.getXSpan() * box.getYSpan() * box.getZSpan() > MAX_BLOCKS_PER_REMOVAL) {
      return "removal survey exceeds block budget";
    }
    PlacedBlockStore ownership = PlacedBlockStore.get(village.getLevel().getLevel());
    int ground = BlockPos.of(victim.getOriginLocation()).getY() + victim.getInfo().getSink();
    for (BlockPos at : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
      if (!village.getLevel().getLevel().hasChunkAt(at) || ownership.isPlayerPlaced(at)) {
        return "unloaded or player-modified building";
      }
      for (Village other : VillageManager.get(village.getLevel().getLevel()).getVillages().values()) {
        if (other != village && other.hasClaimed(at)) {
          return "another village's claim";
        }
      }
      var state = village.getLevel().getBlockState(at);
      if (state.isAir()) {
        continue;
      }
      boolean removable = ownership.isVillagePlaced(at) || BlockOwnership.isPlanted(state);
      if (!removable && at.getY() > ground) {
        return "unknown structural ownership";
      }
      if (removable && positions.add(at.asLong())) {
        blocks.add(new RedevelopmentPlan.RemovalBlock(at.asLong(), state));
      }
    }
    return "";
  }

  /** A new answer cannot authorize different buildings, refunds, or terrain work than its offer. */
  public static boolean stillValid(Village village, RedevelopmentPlan plan) {
    BuildingInfo target = Buildings.getByName(plan.target());
    Building source = plan.source().map(village::getBuilding).orElse(null);
    if (target == null || plan.source().isPresent() && source == null) {
      return false;
    }
    Assessment current = assess(village, target, source, BlockPos.of(plan.ground()), plan.rotation());
    if (current.plan().isEmpty()) {
      return false;
    }
    RedevelopmentPlan checked = current.plan().orElseThrow();
    return checked.targetFingerprint() == plan.targetFingerprint() && checked.mode() == plan.mode()
        && checked.removed().stream().map(Building::getUUID).collect(java.util.stream.Collectors.toSet())
        .equals(plan.removed().stream().map(Building::getUUID).collect(java.util.stream.Collectors.toSet()))
        && checked.salvage().equals(plan.salvage()) && checked.required().equals(plan.required())
        && sameRemovalBlocks(checked.blocks(), plan.blocks()) && checked.prepBreak().equals(plan.prepBreak())
        && checked.prepFill().equals(plan.prepFill());
  }

  /** Crop age and door orientation may change naturally without changing the authorized material. */
  private static boolean sameRemovalBlocks(List<RedevelopmentPlan.RemovalBlock> first,
      List<RedevelopmentPlan.RemovalBlock> second) {
    if (first.size() != second.size()) {
      return false;
    }
    for (int index = 0; index < first.size(); index++) {
      if (first.get(index).position() != second.get(index).position()
          || first.get(index).state().getBlock() != second.get(index).state().getBlock()) {
        return false;
      }
    }
    return true;
  }

  public static String label(RedevelopmentPlan plan) {
    BuildingInfo info = Buildings.getByName(plan.target());
    BlockPos at = BlockPos.of(plan.ground());
    return "redevelop at " + at.getX() + "," + at.getZ() + " for "
        + (info == null ? plan.target() : "level " + info.getLevel() + " " + info.displayLabel());
  }

  public static String describe(Village village, RedevelopmentPlan plan) {
    BuildingInfo target = Buildings.getByName(plan.target());
    List<Building> affected = new ArrayList<>(plan.removed());
    plan.source().map(village::getBuilding).ifPresent(affected::add);
    Set<UUID> ids = affected.stream().map(Building::getUUID).collect(java.util.stream.Collectors.toSet());
    int lostBeds = affected.stream().mapToInt(building -> building.getInfo().getBedLocations().size()).sum();
    int lostJobs = affected.stream().mapToInt(building -> building.getInfo().getWorkLocations().size()).sum();
    int lostStorage = affected.stream().mapToInt(building -> building.getInfo().getContainerLocations().size()).sum();
    long displaced = village.getBedAssignmentsView().values().stream()
        .filter(bed -> ids.contains(bed.getBuildingUUID())).count();
    long remainingFood = village.getBuildings().stream().filter(building -> !ids.contains(building.getUUID()))
        .filter(RedevelopmentPlanner::foodBuilding).filter(building -> village.getJobAssignmentsView().values()
            .stream().anyMatch(job -> job.getBuildingUUID().equals(building.getUUID()))).count();
    String removal = plan.removed().stream().map(building -> {
      BlockPos at = BlockPos.of(building.getOriginLocation());
      return "level " + building.getInfo().getLevel() + " " + building.getInfo().displayLabel()
          + " at " + at.getX() + "," + at.getZ();
    }).collect(java.util.stream.Collectors.joining("; "));
    Building source = plan.source().map(village::getBuilding).orElse(null);
    return (source != null ? "Upgrade the existing level " + source.getInfo().getLevel() + " "
        + source.getInfo().displayLabel() + " to " : "Build separate ") + "level " + target.getLevel() + " " + target.displayLabel() + ". Remove: " + removal
        + ". Net change after completion: " + (target.getBedLocations().size() - lostBeds) + " beds, "
        + (target.getWorkLocations().size() - lostJobs) + " workplaces, "
        + (target.getContainerLocations().size() - lostStorage) + " shared containers, "
        + RedevelopmentDemand.netFoodPlots(village, target, affected) + " crop plots. Target capabilities: "
        + target.getGrants() + ". Current need: " + RedevelopmentDemand.reason(village, target, affected) + ". During work: " + displaced + " residents rehoused, " + lostJobs
        + " workplaces unavailable, " + remainingFood + " staffed food buildings remain; output not forecast. Recover: "
        + MaterialAmount.describe(plan.salvage()) + ". Still pay: " + MaterialAmount.describe(plan.netRequired())
        + ". Removal and ground work: " + work(plan) + " blocks, followed by construction.";
  }

  private static int targetFingerprint(Village village, BuildingInfo target) {
    var template = village.getLevel().getLevel().getStructureManager()
        .getOrCreate(ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, target.getPath()));
    return java.util.Objects.hash(template.save(new net.minecraft.nbt.CompoundTag()), target.getSink());
  }

  /** Non-plant template blocks must still exist; harvestable crops and natural state changes are excluded. */
  private static boolean intactForSalvage(Village village, Building building) {
    var template = village.getLevel().getLevel().getStructureManager()
        .getOrCreate(ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, building.getInfo().getPath()));
    BlockPos origin = BlockPos.of(building.getOriginLocation());
    PlacedBlockStore ownership = PlacedBlockStore.get(village.getLevel().getLevel());
    // Multiple palettes are alternatives, so matching any complete palette is sufficient.
    for (var palette : template.palettes) {
      boolean intact = true;
      for (var block : palette.blocks()) {
        if (block.state().isAir() || BlockOwnership.isPlanted(block.state())) {
          continue;
        }
        BlockPos at = origin.offset(block.pos().rotate(building.getRotation()));
        var actual = village.getLevel().getBlockState(at);
        // Farm ground naturally alternates between farmland and dirt.
        boolean same = actual.is(block.state().getBlock())
            || block.state().is(Blocks.FARMLAND) && (actual.is(Blocks.DIRT) || actual.is(Blocks.GRASS_BLOCK));
        if (!same || !ownership.isVillagePlaced(at)) {
          intact = false;
          break;
        }
      }
      if (intact) {
        return true;
      }
    }
    return false;
  }

  public static BoundingBox targetBounds(Village village, BuildingInfo target, Rotation rotation) {
    return village.getLevel().getLevel().getStructureManager()
        .get(ResourceLocation.fromNamespaceAndPath(Kithkyn.MODID, target.getPath()))
        .map(template -> template.getBoundingBox(new StructurePlaceSettings().setRotation(rotation), BlockPos.ZERO))
        .orElse(null);
  }

  public static BoundingBox worldBounds(Village village, Building building) {
    BoundingBox local = BuildingUpgrade.footprintOf(village.getLevel(), building);
    BlockPos origin = BlockPos.of(building.getOriginLocation());
    return local == null ? null : local.moved(origin.getX(), origin.getY(), origin.getZ());
  }

  private static TownLayout.Footprint footprint(BoundingBox box) {
    return new TownLayout.Footprint(box.minX(), box.minZ(), box.maxX(), box.maxZ());
  }

  private static boolean intersects(TownLayout.Footprint first, TownLayout.Footprint second, int padding) {
    return first.minX() - padding <= second.maxX() && first.maxX() + padding >= second.minX()
        && first.minZ() - padding <= second.maxZ() && first.maxZ() + padding >= second.minZ();
  }

  private static boolean contains(TownLayout.Footprint box, int x, int z) {
    return x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ();
  }

  private static void addColumns(Set<Long> columns, BoundingBox box) {
    for (int x = box.minX(); x <= box.maxX(); x++) {
      for (int z = box.minZ(); z <= box.maxZ(); z++) {
        columns.add(BlockPos.asLong(x, 0, z));
      }
    }
  }
}
