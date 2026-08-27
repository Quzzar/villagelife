package com.quzzar.villagelife.village;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.Map.Entry;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.relationships.RelationshipPair;
import com.quzzar.villagelife.village.bookkeeping.BookkeepingEvent;
import com.quzzar.villagelife.village.bookkeeping.InternalBookkeeper;
import com.quzzar.villagelife.village.buildings.Building;
import com.quzzar.villagelife.village.buildings.BuildingInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

public class VillageBrain {

  public static final Codec<VillageBrain> CODEC = RecordCodecBuilder.create(inst -> inst.group(
      Codec.INT.fieldOf("time").forGetter(b -> b.time),
      Codec.LONG.listOf().fieldOf("containers").forGetter(b -> List.copyOf(b.containerLocs)),
      InternalBookkeeper.CODEC.fieldOf("bookkeeper").forGetter(b -> b.bookkeeper),
      // Extension point for LLM-driven strategic state (current focus, reasoning,
      // timestamps). Stored verbatim so new keys never need a format change.
      CompoundTag.CODEC.optionalFieldOf("strategy", new CompoundTag()).forGetter(b -> b.strategy),
      RelationshipPair.CODEC.listOf().optionalFieldOf("relationships", List.of())
          .forGetter(b -> List.copyOf(b.relationships.values()))
  ).apply(inst, (time, containers, bookkeeper, strategy, relationships) -> {
    VillageBrain brain = new VillageBrain();
    brain.time = time;
    brain.containerLocs = new ArrayList<>(containers);
    brain.bookkeeper = bookkeeper;
    brain.strategy = strategy;
    relationships.forEach(pair -> brain.relationships.put(pair.key(), pair));
    return brain;
  }));

  private int time = 0;

  // These are the absolute coords to each container, not relative
  private ArrayList<Long> containerLocs = new ArrayList<>();

  private InternalBookkeeper bookkeeper = new InternalBookkeeper();

  private CompoundTag strategy = new CompoundTag();

  /**
   * The village-level relationship pair store (persona map issue #5): one
   * entry per non-neutral pair, keyed by the canonical unordered UUID pair.
   * Absence means neutral.
   */
  private HashMap<String, RelationshipPair> relationships = new HashMap<>();

  public VillageBrain() {

  }

  public void update() {

    if (time % 10 == 0) {// Every 10 seconds

      bookkeeper.update();

    }
    if (time % 100 == 0) {// Every 100 seconds
      // call validation method, confirm that there isn't anything missing
    }

    time++;
  }

  public void logEvent(BookkeepingEvent event) {
    this.bookkeeper.addEvent(event);
  }

  /** Free-form strategic state, reserved for the LLM decision layer. */
  public CompoundTag getStrategyData() {
    return strategy;
  }

  public void setStrategyData(CompoundTag strategy) {
    this.strategy = strategy;
  }

  public BlockPos getNearestContainer(BlockPos location) {
    return getNearestContainer(location, java.util.List.of());
  }

  /**
   * The nearest village container that is not one of {@code excluding}. The
   * exclusion is what lets a merchant carry goods OUT of the market: the
   * market's own chests are village storage like any other, so the nearest one
   * to a merchant standing at their stall is the chest they are emptying.
   */
  public BlockPos getNearestContainer(BlockPos location, java.util.Collection<BlockPos> excluding) {

    double smallestDist = Double.MAX_VALUE;
    BlockPos smallestLoc = BlockPos.ZERO;

    for (Long longLoc : containerLocs) {
      BlockPos containerLoc = BlockPos.of(longLoc);
      if (excluding.contains(containerLoc)) {
        continue;
      }
      double distance = location.distSqr(containerLoc);
      if (distance < smallestDist) {
        smallestDist = distance;
        smallestLoc = containerLoc;
      }
    }
    return smallestLoc;

  }

  /**
   * Registers any container the definition has that this building's ground does
   * not already have registered. Called when a building is rebuilt at a new
   * level: its old container positions stay in the list, which costs nothing —
   * a position that is no longer a container is skipped by every reader — while
   * the ones the new level added become part of village storage.
   */
  public void registerNewContainers(Building building) {
    for (long offset : building.getInfo() == null ? List.<Long>of() : building.getInfo().getContainerLocations()) {
      BlockPos location = BlockPos.of(building.getOriginLocation())
          .offset(BlockPos.of(offset).rotate(building.getRotation()));
      if (!containerLocs.contains(location.asLong())) {
        containerLocs.add(location.asLong());
      }
    }
  }

  /**
   * Moves a stack into village storage without anyone carrying it, skipping the
   * containers named in {@code excluding}. Used when a building is emptied
   * before being rebuilt (docs/building-spec.md): the items have to leave the
   * building itself, and there is no worker standing there to hand them to.
   * Returns whatever would not fit, which the caller must not throw away.
   */
  public ItemStack storeAwayFrom(ServerLevelAccessor levelAccess, ItemStack stack,
      java.util.Collection<BlockPos> excluding) {
    ItemStack remaining = stack;
    for (Long longLoc : containerLocs) {
      if (excluding.contains(BlockPos.of(longLoc))) {
        continue;
      }
      Container container = containerAt(levelAccess, longLoc);
      if (container == null) {
        continue;
      }
      remaining = HopperBlockEntity.addItem(null, container, remaining, null);
      if (remaining.isEmpty()) {
        return ItemStack.EMPTY;
      }
    }
    return remaining;
  }

  public boolean hasItemStackInVillage(ServerLevelAccessor levelAccess, ItemStack itemStack) {

    int amountRequired = itemStack.getCount();

    for (Long longLoc : containerLocs) {
      Container container = containerAt(levelAccess, longLoc);
      if (container == null) {
        continue;
      }

      amountRequired -= Utils.getAmountOfItemType(container, itemStack.getItem());

      if (amountRequired <= 0) {
        return true;
      }

    }

    return false;

  }

  public ArrayList<ItemStack> getVillageInventory(ServerLevelAccessor levelAccess) {

    ArrayList<ItemStack> inventory = new ArrayList<>();

    for (Long longLoc : containerLocs) {
      Container container = containerAt(levelAccess, longLoc);
      if (container == null) {
        continue;
      }

      for (int i = 0; i < container.getContainerSize(); i++) {
        ItemStack itemstack = container.getItem(i);
        if (itemstack != null && !itemstack.isEmpty()) {
          inventory.add(itemstack);
        }
      }

    }

    return inventory;

  }

  public ItemStack gatherItemStackFromVillage(ServerLevelAccessor levelAccess, ItemStack itemStack, BlockPos preferNearestToLoc) {

    int amountRequired = itemStack.getCount();

    ArrayList<Long> containerLocations = orderedContainers(preferNearestToLoc);

    for (Long longLoc : containerLocations) {
      Container container = containerAt(levelAccess, longLoc);
      if (container == null) {
        continue;
      }

      ItemStack foundItems = Utils.removeItem(container, itemStack, amountRequired);
      amountRequired -= foundItems.getCount();

      if (amountRequired <= 0) {
        break;
      }

    }

    ItemStack resultItem = itemStack.copy();
    resultItem.setCount(itemStack.getCount() - amountRequired);
    return resultItem;

  }

  public boolean placeItemStackIntoVillage(ServerLevelAccessor levelAccess, ItemStack itemStack, Entity entity, BlockPos preferNearestToLoc) {

    boolean addedAll = false;

    ItemEntity itemEntity = entity.spawnAtLocation(itemStack);
    if (itemEntity == null) {
      return addedAll;
    }

    ArrayList<Long> containerLocations = orderedContainers(preferNearestToLoc);

    for (Long longLoc : containerLocations) {
      Container container = containerAt(levelAccess, longLoc);
      if (container == null) {
        continue;
      }

      addedAll = HopperBlockEntity.addItem(container, itemEntity);
      if (addedAll) {
        break;
      }

    }

    return addedAll;

  }

  /** Total count of edible items across all village containers. */
  public int countFood(ServerLevelAccessor levelAccess) {
    int count = 0;
    for (Long longLoc : containerLocs) {
      Container container = containerAt(levelAccess, longLoc);
      if (container == null) {
        continue;
      }
      for (int i = 0; i < container.getContainerSize(); i++) {
        ItemStack stack = container.getItem(i);
        if (!stack.isEmpty() && stack.has(net.minecraft.core.component.DataComponents.FOOD)) {
          count += stack.getCount();
        }
      }
    }
    return count;
  }

  public float totalImpact(Class<? extends BookkeepingEvent> type) {
    return bookkeeper.totalImpact(type);
  }

  /** Where the village keeps things, as positions rather than packed longs. */
  public java.util.Set<BlockPos> containerPositions() {
    java.util.Set<BlockPos> positions = new java.util.HashSet<>();
    for (Long longLoc : containerLocs) {
      positions.add(BlockPos.of(longLoc));
    }
    return positions;
  }

  /**
   * Forgets container positions whose block is gone: broken by a player, built
   * over, or replaced when a building was upgraded. Every reader already skips
   * them, so this is not a correctness fix on its own — it stops the list
   * growing forever, and stops workers being sent to chests that are not there.
   *
   * Only positions in loaded chunks are judged. A container in a chunk nobody
   * is near is not missing, it is merely out of sight, and pruning it would
   * delete real storage the moment a village went quiet.
   */
  public void pruneMissingContainers(ServerLevelAccessor levelAccess) {
    containerLocs.removeIf(longLoc -> {
      BlockPos pos = BlockPos.of(longLoc);
      if (!levelAccess.getLevel().hasChunkAt(pos)) {
        return false;
      }
      boolean gone = !(levelAccess.getBlockEntity(pos) instanceof Container);
      if (gone) {
        Villagelife.LOGGER.debug("Forgetting a container at {}: nothing is there any more",
            pos.toShortString());
      }
      return gone;
    });
  }

  private Container containerAt(ServerLevelAccessor levelAccess, long longLoc) {
    BlockEntity entity = levelAccess.getBlockEntity(BlockPos.of(longLoc));
    return entity instanceof Container container ? container : null;
  }

  private ArrayList<Long> orderedContainers(BlockPos preferNearestToLoc) {
    if (preferNearestToLoc == null) {
      return containerLocs;
    }
    ArrayList<Long> ordered = new ArrayList<>(containerLocs);
    BlockPos nearest = getNearestContainer(preferNearestToLoc);
    if (nearest != BlockPos.ZERO) {
      ordered.add(0, nearest.asLong());
    }
    return ordered;
  }

  public void processNewBuilding(Building building, ArrayList<BedAssignment> unassignedBeds,
      ArrayList<JobAssignment> unassignedJobs) {

    BuildingInfo info = building.getInfo();

    for (int i = 0; i < info.getBedLocations().size(); i++) {
      unassignedBeds.add(new BedAssignment(null, building.getUUID(), i));
    }

    int entryI = 0;
    for (Entry<Long, Occupation> entry : info.getWorkLocations().entrySet()) {
      unassignedJobs.add(new JobAssignment(null, entry.getValue(), building.getUUID(), entryI));
      entryI++;
    }

    for (long longloc : info.getContainerLocations()) {
      BlockPos location = BlockPos.of(building.getOriginLocation())
          .offset(BlockPos.of(longloc).rotate(building.getRotation()));
      containerLocs.add(location.asLong());
    }

  }

  public void removeBuilding(UUID buildingUUID, HashMap<UUID, Building> buildings,
      HashMap<UUID, BedAssignment> bedAssignments, HashMap<UUID, JobAssignment> jobAssignments,
      ArrayList<BedAssignment> unassignedBeds, ArrayList<JobAssignment> unassignedJobs) {

    buildings.remove(buildingUUID);

    bedAssignments.entrySet().removeIf(entry -> entry.getValue().getBuildingUUID().equals(buildingUUID));
    jobAssignments.entrySet().removeIf(entry -> entry.getValue().getBuildingUUID().equals(buildingUUID));
    unassignedBeds.removeIf(b -> b.getBuildingUUID().equals(buildingUUID));
    unassignedJobs.removeIf(b -> b.getBuildingUUID().equals(buildingUUID));

  }

  public void removePerson(UUID personUUID, ArrayList<UUID> people, HashMap<UUID, BedAssignment> bedAssignments,
      HashMap<UUID, JobAssignment> jobAssignments, ArrayList<BedAssignment> unassignedBeds,
      ArrayList<JobAssignment> unassignedJobs) {

    BedAssignment bed = bedAssignments.get(personUUID);
    if (bed != null) {
      unassignedBeds.add(bed.setPersonUUID(null));
      bedAssignments.remove(personUUID);
    }

    JobAssignment job = jobAssignments.get(personUUID);
    if (job != null) {
      unassignedJobs.add(job.setPersonUUID(null));
      jobAssignments.remove(personUUID);
    }

    people.remove(personUUID);

    relationships.values().removeIf(pair -> pair.involves(personUUID));

  }

  /** The free-form strategic state tag; callers may mutate what they put in it. */
  public CompoundTag getStrategy() {
    return strategy;
  }

  // --- Relationship pair store (persona map issue #5) ---

  public void putRelationship(RelationshipPair pair) {
    relationships.put(pair.key(), pair);
  }

  public RelationshipPair getRelationship(UUID first, UUID second) {
    return relationships.get(RelationshipPair.keyOf(first, second));
  }

  public List<RelationshipPair> relationshipsOf(UUID person) {
    List<RelationshipPair> result = new ArrayList<>();
    for (RelationshipPair pair : relationships.values()) {
      if (pair.involves(person)) {
        result.add(pair);
      }
    }
    return result;
  }



}
