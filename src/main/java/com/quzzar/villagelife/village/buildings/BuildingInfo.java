package com.quzzar.villagelife.village.buildings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.quzzar.villagelife.utils.VillagelifeCodecs;
import com.quzzar.villagelife.village.Occupation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class BuildingInfo {

  /** A work station inside a building: a position (relative to the structure origin) plus the job worked there. */
  public record WorkStation(BlockPos pos, Occupation occupation) {
    public static final Codec<WorkStation> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        BlockPos.CODEC.fieldOf("pos").forGetter(WorkStation::pos),
        VillagelifeCodecs.forEnum(Occupation.class).fieldOf("occupation").forGetter(WorkStation::occupation)
    ).apply(inst, WorkStation::new));
  }

  /** A material cost entry, kept simple on purpose (item id + count). */
  public record ItemCost(Item item, int count) {
    public static final Codec<ItemCost> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(ItemCost::item),
        Codec.INT.optionalFieldOf("count", 1).forGetter(ItemCost::count)
    ).apply(inst, ItemCost::new));
  }

  public static final Codec<BuildingInfo> CODEC = RecordCodecBuilder.create(inst -> inst.group(
      Codec.STRING.fieldOf("structure").forGetter(BuildingInfo::getName),
      BlockPos.CODEC.listOf().optionalFieldOf("beds", List.of()).forGetter(BuildingInfo::bedPositions),
      WorkStation.CODEC.listOf().optionalFieldOf("work_stations", List.of()).forGetter(BuildingInfo::workStations),
      BlockPos.CODEC.listOf().optionalFieldOf("containers", List.of()).forGetter(BuildingInfo::containerPositions),
      ItemCost.CODEC.listOf().optionalFieldOf("cost", List.of()).forGetter(BuildingInfo::itemCosts),
      Codec.STRING.listOf().optionalFieldOf("grants", List.of()).forGetter(BuildingInfo::getGrants),
      Grant.CODEC.listOf().optionalFieldOf("grants_if", List.of()).forGetter(BuildingInfo::getConditionalGrants),
      BlockPos.CODEC.optionalFieldOf("gathering_point").forGetter(info ->
          java.util.Optional.ofNullable(info.gatheringPoint).map(BlockPos::of)),
      Codec.STRING.optionalFieldOf("category").forGetter(info -> java.util.Optional.ofNullable(info.explicitCategory)),
      Codec.STRING.optionalFieldOf("variant").forGetter(info -> java.util.Optional.ofNullable(info.explicitVariant)),
      Codec.STRING.optionalFieldOf("upgrades_from").forGetter(info -> java.util.Optional.ofNullable(info.upgradesFrom)),
      Codec.INT.optionalFieldOf("sink", 0).forGetter(BuildingInfo::getSink)
  ).apply(inst, BuildingInfo::fromCodec));

  private static BuildingInfo fromCodec(String structure, List<BlockPos> beds, List<WorkStation> workStations,
      List<BlockPos> containers, List<ItemCost> costs, List<String> grants, List<Grant> conditionalGrants,
      java.util.Optional<BlockPos> gatheringPoint, java.util.Optional<String> category,
      java.util.Optional<String> variant, java.util.Optional<String> upgradesFrom, int sink) {
    BuildingInfo info = new BuildingInfo(structure);
    beds.forEach(pos -> info.addBedLocation(pos.getX(), pos.getY(), pos.getZ()));
    workStations.forEach(station -> info.addWorkLocation(
        station.pos().getX(), station.pos().getY(), station.pos().getZ(), station.occupation()));
    containers.forEach(pos -> info.addContainerLocation(pos.getX(), pos.getY(), pos.getZ()));
    info.setMaterialCost(costs.stream().map(cost -> new ItemStack(cost.item(), cost.count())).toList());
    info.grants = List.copyOf(grants);
    info.conditionalGrants = List.copyOf(conditionalGrants);
    gatheringPoint.ifPresent(pos -> info.gatheringPoint = pos.asLong());
    info.explicitCategory = category.orElse(null);
    info.explicitVariant = variant.orElse(null);
    info.upgradesFrom = upgradesFrom.orElse(null);
    info.sink = sink;
    return info;
  }

  private String path;
  private ArrayList<Long> bedLocs;
  // Insertion-ordered: JobAssignment station indexes rely on a stable iteration order.
  private LinkedHashMap<Long, Occupation> workLocs;
  private ArrayList<Long> containerLocs;

  private List<ItemStack> materialCost;
  /** Capabilities this building always grants, as datapack strings (#55). */
  private List<String> grants = List.of();
  /** Capabilities it grants only while a condition holds. */
  private List<Grant> conditionalGrants = List.of();
  // Packed BlockPos of the building's gathering point (the campfire), or null.
  private Long gatheringPoint;
  // Explicit JSON category/variant, validated against the id-derived values; null = derive.
  private String explicitCategory;
  private String explicitVariant;
  // The id this building replaces in place; required for every level above 1.
  private String upgradesFrom;
  private int sink;

  public BuildingInfo(String path) {

    this.path = path;
    this.bedLocs = new ArrayList<>();
    this.workLocs = new LinkedHashMap<>();
    this.containerLocs = new ArrayList<>();
    this.materialCost = new ArrayList<>();

  }

  public String getName() {
    return path;
  }

  public String getPath() {
    return path;
  }

  /**
   * The id scheme is {@code <category>_<variant>_<level>} (docs/building-spec.md):
   * the last token is the level, the token before it the variant, everything
   * before that the category (which may itself contain underscores).
   */
  private String[] idTokens() {
    return path.split("_");
  }

  /** True when the id parses as {@code <category>_<variant>_<level>}. */
  public boolean hasWellFormedId() {
    String[] tokens = idTokens();
    if (tokens.length < 3) {
      return false;
    }
    try {
      Integer.parseInt(tokens[tokens.length - 1]);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  public String getCategory() {
    if (explicitCategory != null) {
      return explicitCategory;
    }
    String[] tokens = idTokens();
    return String.join("_", java.util.Arrays.copyOf(tokens, tokens.length - 2));
  }

  public String getVariant() {
    if (explicitVariant != null) {
      return explicitVariant;
    }
    String[] tokens = idTokens();
    return tokens[tokens.length - 2];
  }

  public int getLevel() {
    String[] tokens = idTokens();
    return Integer.parseInt(tokens[tokens.length - 1]);
  }

  /** The id this building upgrades from in place, or null for a level-1 building. */
  @javax.annotation.Nullable
  /**
   * How many of the structure's bottom layers sit below the ground plane. A
   * building is seated with its layer 0 on the ground's top block; a well
   * declares one so its water and rim lie flush with the ground and its base
   * course is buried, which is also what keeps the pool walled in by earth
   * while the builder fills it.
   */
  public int getSink() {
    return sink;
  }

  public String getUpgradesFrom() {
    return upgradesFrom;
  }

  /**
   * Definition-consistency check, run by the loader: a malformed id, an explicit
   * category/variant contradicting the id, or a level above 1 with no
   * {@code upgrades_from} (levels are never built from scratch) is an error.
   * Returns the problem, or null when the definition is consistent.
   */
  @javax.annotation.Nullable
  public String validate() {
    if (!hasWellFormedId()) {
      return "id '" + path + "' does not match <category>_<variant>_<level>";
    }
    String[] tokens = idTokens();
    String derivedCategory = String.join("_", java.util.Arrays.copyOf(tokens, tokens.length - 2));
    String derivedVariant = tokens[tokens.length - 2];
    if (explicitCategory != null && !explicitCategory.equals(derivedCategory)) {
      return "category '" + explicitCategory + "' contradicts id-derived '" + derivedCategory + "'";
    }
    if (explicitVariant != null && !explicitVariant.equals(derivedVariant)) {
      return "variant '" + explicitVariant + "' contradicts id-derived '" + derivedVariant + "'";
    }
    if (getLevel() >= 2 && upgradesFrom == null) {
      return "level " + getLevel() + " building has no upgrades_from (levels are never built from scratch)";
    }
    return null;
  }

  public ArrayList<Long> getBedLocations() {
    return bedLocs;
  }

  public Map<Long, Occupation> getWorkLocations() {
    return workLocs;
  }

  public ArrayList<Long> getContainerLocations() {
    return containerLocs;
  }

  public BuildingInfo addBedLocation(int x, int y, int z) {
    bedLocs.add(BlockPos.asLong(x, y, z));
    return this;
  }

  public BuildingInfo addWorkLocation(int x, int y, int z, Occupation occupation) {
    workLocs.put(BlockPos.asLong(x, y, z), occupation);
    return this;
  }

  public BuildingInfo addContainerLocation(int x, int y, int z) {
    containerLocs.add(BlockPos.asLong(x, y, z));
    return this;
  }

  public List<ItemStack> getMaterialCost() {
    return materialCost;
  }

  public BuildingInfo setMaterialCost(List<ItemStack> materialCost) {
    this.materialCost = materialCost;
    return this;
  }

  @javax.annotation.Nullable
  public Long getGatheringPoint() {
    return gatheringPoint;
  }

  public List<String> getGrants() {
    return grants;
  }

  public List<Grant> getConditionalGrants() {
    return conditionalGrants;
  }

  private List<BlockPos> bedPositions() {
    return bedLocs.stream().map(BlockPos::of).toList();
  }

  private List<WorkStation> workStations() {
    return workLocs.entrySet().stream()
        .map(entry -> new WorkStation(BlockPos.of(entry.getKey()), entry.getValue())).toList();
  }

  private List<BlockPos> containerPositions() {
    return containerLocs.stream().map(BlockPos::of).toList();
  }

  private List<ItemCost> itemCosts() {
    return materialCost.stream().map(stack -> new ItemCost(stack.getItem(), stack.getCount())).toList();
  }

}
