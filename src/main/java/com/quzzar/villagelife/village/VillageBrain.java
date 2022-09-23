package com.quzzar.villagelife.village;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.ListIterator;
import java.util.UUID;
import java.util.Map.Entry;

import com.quzzar.villagelife.PersonEntityType;
import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.bookkeeping.BkEvent;
import com.quzzar.villagelife.village.bookkeeping.InternalBookkeeper;
import com.quzzar.villagelife.village.buildings.Building;
import com.quzzar.villagelife.village.buildings.BuildingInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

public class VillageBrain implements Serializable {

  private int time = 0;

  // These are the absolute coords to each container, not relative
  private ArrayList<Long> containerLocs = new ArrayList<>();

  private InternalBookkeeper bookkeeper = new InternalBookkeeper();

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

  public void logEvent(BkEvent event){
    this.bookkeeper.addEvent(event);
  }

  public BlockPos getNearestContainer(BlockPos location) {

    double smallestDist = Double.MAX_VALUE;
    BlockPos smallestLoc = BlockPos.ZERO;

    for (Long longLoc : containerLocs) {
      BlockPos containerLoc = BlockPos.of(longLoc);
      double distance = location.distSqr(containerLoc);
      if (distance < smallestDist) {
        smallestDist = distance;
        smallestLoc = containerLoc;
      }
    }
    return smallestLoc;

  }

  public boolean hasItemStackInVillage(ItemStack itemStack) {

    int amountRequired = itemStack.getCount();

    ArrayList<Long> containerLocations = containerLocs;

    for (Long longLoc : containerLocations) {
      BlockPos containerLoc = BlockPos.of(longLoc);

      BlockEntity entity = VillageManager.getLevelAccessor().getBlockEntity(containerLoc);
      Container container;
      if (entity instanceof Container) {
        container = (Container) entity;
      } else {
        continue;
      }

      amountRequired -= Utils.getAmountOfItemType(container, itemStack.getItem());

      if(amountRequired <= 0){
        return true;
      }

    }

    return false;

  }

  public ArrayList<ItemStack> getVillageInventory() {

    ArrayList<ItemStack> inventory = new ArrayList<>();

    ArrayList<Long> containerLocations = containerLocs;
    for (Long longLoc : containerLocations) {
      BlockPos containerLoc = BlockPos.of(longLoc);

      BlockEntity entity = VillageManager.getLevelAccessor().getBlockEntity(containerLoc);
      Container container;
      if (entity instanceof Container) {
        container = (Container) entity;
      } else {
        continue;
      }

      for (int i = 0; i < container.getContainerSize(); i++) {
        ItemStack itemstack = container.getItem(i);
        if(itemstack != null && !itemstack.isEmpty()){
          inventory.add(itemstack);
        }
      }

    }

    return inventory;

  }

  public ItemStack gatherItemStackFromVillage(ItemStack itemStack, BlockPos preferNearestToLoc) {

    int amountRequired = itemStack.getCount();

    ArrayList<Long> containerLocations;
    if(preferNearestToLoc != null){
      containerLocations = (ArrayList<Long>) containerLocs.clone();
      BlockPos containerLoc = getNearestContainer(preferNearestToLoc);
      if(containerLoc != BlockPos.ZERO){
        containerLocations.add(0, containerLoc.asLong());
      }
    } else {
      containerLocations = containerLocs;
    }

    for (Long longLoc : containerLocations) {
      BlockPos containerLoc = BlockPos.of(longLoc);

      BlockEntity entity = VillageManager.getLevelAccessor().getBlockEntity(containerLoc);
      Container container;
      if (entity instanceof Container) {
        container = (Container) entity;
      } else {
        continue;
      }

      ItemStack foundItems = Utils.removeItem(container, itemStack, amountRequired);
      amountRequired -= foundItems.getCount();

      if(amountRequired <= 0){
        break;
      }

    }

    ItemStack resultItem = itemStack.copy();
    resultItem.setCount(itemStack.getCount() - amountRequired);
    return resultItem;

  }

  public boolean placeItemStackIntoVillage(ItemStack itemStack, Entity entity, BlockPos preferNearestToLoc) {

    boolean addedAll = false;

    ItemEntity itemEntity = entity.spawnAtLocation(itemStack);
    if(itemEntity == null) { return addedAll; }

    ArrayList<Long> containerLocations;
    if(preferNearestToLoc != null){
      containerLocations = (ArrayList<Long>) containerLocs.clone();
      BlockPos containerLoc = getNearestContainer(preferNearestToLoc);
      if(containerLoc != BlockPos.ZERO){
        containerLocations.add(0, containerLoc.asLong());
      }
    } else {
      containerLocations = containerLocs;
    }

    for (Long longLoc : containerLocations) {
      BlockPos containerLoc = BlockPos.of(longLoc);

      BlockEntity conEntity = VillageManager.getLevelAccessor().getBlockEntity(containerLoc);
      Container container;
      if (conEntity instanceof Container) {
        container = (Container) conEntity;
      } else {
        continue;
      }

      addedAll = HopperBlockEntity.addItem(container, itemEntity);
      if(addedAll){ break; }

    }

    return addedAll;

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

    for (Entry<UUID, BedAssignment> entry : bedAssignments.entrySet()) {
      if (entry.getValue().getBuildingUUID().equals(buildingUUID)) {
        bedAssignments.remove(entry.getKey());
      }
    }
    for (Entry<UUID, JobAssignment> entry : jobAssignments.entrySet()) {
      if (entry.getValue().getBuildingUUID().equals(buildingUUID)) {
        jobAssignments.remove(entry.getKey());
      }
    }
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

  }

  public boolean testPersonCreation(ServerLevelAccessor levelAccess, long location, ArrayList<UUID> people,
      HashMap<UUID, BedAssignment> bedAssignments, HashMap<UUID, JobAssignment> jobAssignments, String villageUUID,
      ArrayList<BedAssignment> unassignedBeds, ArrayList<JobAssignment> unassignedJobs, boolean createAll) {

    boolean createdPerson = false;

    ListIterator<JobAssignment> unassignedJobsIter = unassignedJobs.listIterator();
    while (!unassignedJobs.isEmpty() && unassignedJobsIter.hasNext()) {
      JobAssignment job = unassignedJobsIter.next();
      if (job.getOccupation() == Occupation.GUARD) {

        RealPerson person = createPerson(levelAccess, BlockPos.of(location), villageUUID, job.getOccupation());

        people.add(person.getUUID());
        jobAssignments.put(person.getUUID(), job.setPersonUUID(person.getUUID()));

        person.reloadState();
        createdPerson = true;

        unassignedJobsIter.remove();

        if (!createAll) {
          return createdPerson;
        }
      }
    }

    while (!unassignedBeds.isEmpty()) {
      BedAssignment bed = unassignedBeds.remove(0);
      JobAssignment job = (unassignedJobs.isEmpty()) ? null : unassignedJobs.remove(0);

      Occupation occupation = (job != null) ? job.getOccupation() : Occupation.NITWIT;
      RealPerson person = createPerson(levelAccess, BlockPos.of(location), villageUUID, occupation);

      people.add(person.getUUID());
      bedAssignments.put(person.getUUID(), bed.setPersonUUID(person.getUUID()));
      if (job != null) {
        jobAssignments.put(person.getUUID(), job.setPersonUUID(person.getUUID()));
      }

      person.reloadState();
      createdPerson = true;

      if (!createAll) {
        return createdPerson;
      }
    }

    return createdPerson;
  }

  private RealPerson createPerson(ServerLevelAccessor levelAccess, BlockPos location, String villageUUID,
      Occupation occupation) {

    RealPerson person = PersonEntityType.PERSON.get().create(levelAccess.getLevel());
    person.setVillage(villageUUID);
    person.moveTo(location, 0.0F, 0.0F);
    person.setOccupation(occupation);
    levelAccess.addFreshEntity(person);

    // setOccupation(Occupation.values()[rand.nextInt(Occupation.values().length)]);

    Villagelife.LOGGER.debug("New person (" + person.getFullName() + ") created!");
    /*
     * if(Minecraft.getInstance().getConnection() != null){
     * Minecraft.getInstance().getConnection().setActionBarText(new
     * ClientboundSetActionBarTextPacket(new
     * TextComponent(person.getFullName()+" has spawned.")));
     * }
     */

    return person;

  }

  /*
   * private boolean validateAssignments(ArrayList<UUID> people, HashMap<UUID,
   * Building> buildings, HashMap<UUID, BedAssignment> bedAssignments,
   * HashMap<UUID, JobAssignment> jobAssignments){
   * 
   * boolean isValid = true;
   * 
   * this.containerLocs.clear();
   * HashMap<UUID, ArrayList<BedAssignment>> foundBeds = new HashMap<>();
   * HashMap<UUID, ArrayList<JobAssignment>> foundJobs = new HashMap<>();
   * 
   * for(UUID personUUID : people){
   * 
   * // Is each person assigned to a bed?
   * BedAssignment bed = bedAssignments.get(personUUID);
   * if(bed == null){
   * Villagelife.LOGGER.error("Person missing bed!");
   * isValid = false;
   * } else {
   * 
   * // Does the bed exist in a registered building?
   * Building building = buildings.get(bed.getBuildingUUID());
   * if(building == null){
   * Villagelife.LOGGER.
   * error("Bed assignment in nonexistent building! Removing...");
   * bedAssignments.remove(personUUID);
   * isValid = false;
   * } else {
   * // Add found bed to record
   * ArrayList<BedAssignment> buildingBeds = foundBeds.get(building.getUUID());
   * if(buildingBeds != null){
   * buildingBeds.add(bed);
   * } else {
   * buildingBeds = new ArrayList<>();
   * }
   * foundBeds.put(building.getUUID(), buildingBeds);
   * }
   * 
   * }
   * 
   * // Is each person assigned to a job?
   * JobAssignment job = jobAssignments.get(personUUID);
   * if(job == null){
   * Villagelife.LOGGER.error("Person missing job!");
   * isValid = false;
   * } else {
   * 
   * // Does the job exist in a registered building?
   * Building building = buildings.get(job.getBuildingUUID());
   * if(building == null){
   * Villagelife.LOGGER.
   * error("Job assignment in nonexistent building! Removing...");
   * jobAssignments.remove(personUUID);
   * isValid = false;
   * } else {
   * // Add found job to record
   * ArrayList<JobAssignment> buildingJobs = foundJobs.get(building.getUUID());
   * if(buildingJobs != null){
   * buildingJobs.add(job);
   * } else {
   * buildingJobs = new ArrayList<>();
   * }
   * foundJobs.put(building.getUUID(), buildingJobs);
   * }
   * 
   * }
   * 
   * 
   * // Find missing assignments (and locate containers)
   * 
   * 
   * for(Entry<UUID, Building> entry : buildings.entrySet()){
   * 
   * ArrayList<BedAssignment> buildingBeds = foundBeds.get(entry.getKey());
   * if(buildingBeds == null){
   * 
   * 
   * 
   * } else {
   * 
   * }
   * 
   * 
   * ArrayList<JobAssignment> buildingJobs = foundJobs.get(entry.getKey());
   * 
   * 
   * 
   * }
   * 
   * 
   * }
   * 
   * return isValid;
   * 
   * 
   * Remember: not everyone has a bed and not everyone has a job
   * 
   * Go through each person and confirm that their occupation matches their job
   * assignement
   * 
   * }
   * 
   * 
   * function() {
   * 
   * opptomize where people work (based on virtues)
   * 
   * then opptomize where they live (put them in the closest bed to their work
   * location)
   * 
   * }
   * 
   * 
   */

}
