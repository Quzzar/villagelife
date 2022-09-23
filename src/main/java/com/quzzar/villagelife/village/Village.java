package com.quzzar.villagelife.village;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import com.quzzar.villagelife.Utils;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.bookkeeping.BkEvent;
import com.quzzar.villagelife.village.buildings.BuildProgress;
import com.quzzar.villagelife.village.buildings.Building;
import com.quzzar.villagelife.village.buildings.BuildingInfo;
import com.quzzar.villagelife.village.buildings.Buildings;
import com.quzzar.villagelife.village.buildings.InstantBuildStructure;
import com.quzzar.villagelife.village.buildings.LocationValidator;
import com.quzzar.villagelife.village.buildings.StructureInProgress;
import com.quzzar.villagelife.village.buildings.UrbanPlanner;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public class Village implements Serializable {

  private final int CHECK_PROJECT_PROGRESS = 60; // 60 // seconds
  private final float PERSON_SPAWN_CHANCE = 0.3F; // 0.3F // 30% every 100 seconds, aka once per 333 seconds-ish

  private int time = 0;

  private final String id;
  private String name;

  private VillageBrain brain;
  private UUID townCenterUUID;

  private Random random;

  // To Add //
  // ArrayList<DeathRecord> for records of who has died and how (DamageSource) and
  // who killed them (getKillCredit()) LivingDeathEvent
  // track what gifts have been given
  // what projects have been worked on and who joined
  // what villagers have been attacked and by who
  // track number of required resources left to make a building, then have
  // dialogue for if number changes dramtically (aka someone give or steals a
  // large amount)

  private HashSet<Long> claimGrid = new HashSet<>();

  private StructureInProgress currentProject;

  private ArrayList<UUID> people;
  private HashMap<UUID, Building> buildings;
  private HashMap<UUID, JobAssignment> jobAssignments;
  private HashMap<UUID, BedAssignment> bedAssignments;

  private ArrayList<JobAssignment> unassignedJobs;
  private ArrayList<BedAssignment> unassignedBeds;

  public Village(String name) {

    this.id = UUID.randomUUID().toString();
    this.name = name;

    this.brain = new VillageBrain();

    this.random = new Random();

    this.people = new ArrayList<>();
    this.buildings = new HashMap<>();
    this.jobAssignments = new HashMap<>();
    this.bedAssignments = new HashMap<>();
    this.unassignedJobs = new ArrayList<>();
    this.unassignedBeds = new ArrayList<>();

  }

  public void initNew(BlockPos centerLoc) {
    if (this.townCenterUUID != null || this.buildings.size() > 0) {
      return;
    }

    InstantBuildStructure townCenterStruct = new InstantBuildStructure(
        new Building(Buildings.TOWN_CENTER_1.getName(), Rotation.getRandom(random)), random)
        .setOriginLocation(centerLoc, claimGrid);
    townCenterStruct.buildInstantly();

    Building townCenterBuilding = townCenterStruct.getBuilding();

    this.townCenterUUID = townCenterBuilding.getUUID();
    addBuilding(townCenterBuilding);

    testPersonCreation(true);

    // TODO, test for village center
    /*
    VillageManager.getLevelAccessor().setBlock(BlockPos.of(getTownCenter().getOriginLocation()),
        Blocks.LAPIS_BLOCK.defaultBlockState(), 2);
    VillageManager.getLevelAccessor().setBlock(BlockPos.of(getTownCenter().getCenterLocation()),
        Blocks.SEA_LANTERN.defaultBlockState(), 2);
    */

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

      Villagelife.LOGGER.debug("No current project, getting new one.");

      BuildingInfo buildingInfo = UrbanPlanner.getNextProject(this);
      if(buildingInfo == null){ return; }

      Villagelife.LOGGER.debug("Found new project: "+buildingInfo.getName());

      Building building = new Building(buildingInfo.getName(), Rotation.getRandom(random));
      StructureInProgress project = new StructureInProgress(building, random);
      BoundingBox bounds = project.getStructureTemplate().getBoundingBox(project.getStructurePlaceSettings(),
          BlockPos.ZERO);

      BlockPos projectLocation = LocationValidator.findValidLocation(BlockPos.of(getTownCenter().getCenterLocation()).below(), bounds, this, random);

      if (projectLocation != BlockPos.ZERO) {
        // Beginning construction of new project.

        UrbanPlanner.payForBuilding(this, project.getBuilding().getInfo());

        currentProject = project.setOriginLocation(projectLocation);

        BlockPos centerOffset = new BlockPos(bounds.getCenter().getX(), 0, bounds.getCenter().getZ());
        currentProject.getBuilding().setCenterLocation(projectLocation.above().offset(centerOffset).asLong());
        currentProject.getBuilding().setRadius(LocationValidator.getBuildingRadius(bounds));

        for(int x = bounds.minX(); x <= bounds.maxX(); x++){
          for(int z = bounds.minZ(); z <= bounds.maxZ(); z++){
            VillageManager.getLevelAccessor().setBlock(projectLocation.offset(x, 0, z), Blocks.CLAY.defaultBlockState(), 2); // TODO, remove
            claimGrid.add(BlockPos.asLong(projectLocation.getX()+x, 0, projectLocation.getZ()+z));
          }
        }

        // TODO, test blocks for locations
        for (long loc : project.getBuilding().getInfo().getBedLocations()) {
          VillageManager.getLevelAccessor().setBlock(BlockPos.of(project.getBuilding().getOriginLocation())
              .offset(BlockPos.of(loc).rotate(building.getRotation())), Blocks.EMERALD_BLOCK.defaultBlockState(), 2);
        }
        for (long loc : project.getBuilding().getInfo().getWorkLocations().keySet()) {
          VillageManager.getLevelAccessor().setBlock(BlockPos.of(project.getBuilding().getOriginLocation())
              .offset(BlockPos.of(loc).rotate(building.getRotation())), Blocks.IRON_BLOCK.defaultBlockState(), 2);
        }
        for (long loc : project.getBuilding().getInfo().getContainerLocations()) {
          VillageManager.getLevelAccessor().setBlock(BlockPos.of(project.getBuilding().getOriginLocation())
              .offset(BlockPos.of(loc).rotate(building.getRotation())), Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        }

        /*
        VillageManager.getLevelAccessor().setBlock(BlockPos.of(currentProject.getBuilding().getOriginLocation()),
        Blocks.LAPIS_BLOCK.defaultBlockState(), 2);
        VillageManager.getLevelAccessor().setBlock(BlockPos.of(currentProject.getBuilding().getCenterLocation()),
        Blocks.SEA_LANTERN.defaultBlockState(), 2);
        */

      } else {
        Villagelife.LOGGER.debug("Failed to find a valid location for the new building.");
      }

    }

  }

  public void update() { // Every 1 second
    this.brain.update();

    if (time % CHECK_PROJECT_PROGRESS == 0) {// Every X seconds

      checkCurrentProject();

    }

    if (time % 100 == 0) {// Every 100 seconds

      if (random.nextFloat() < PERSON_SPAWN_CHANCE) { // X% every 100 seconds

        testPersonCreation(false);

      }

    }

    time++;
  }

  private void testPersonCreation(boolean createAll) {
    this.brain.testPersonCreation(VillageManager.getLevelAccessor(),
        getTownCenter().getCenterLocation(),
        this.people,
        this.bedAssignments,
        this.jobAssignments,
        this.id,
        this.unassignedBeds,
        this.unassignedJobs,
        createAll);
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

  public boolean hasClaimed(BlockPos pos){
    return claimGrid.contains(BlockPos.asLong(pos.getX(), 0, pos.getZ()));
  }

  public BlockPos getNearestContainer(BlockPos location) {
    return this.brain.getNearestContainer(location);
  }

  public ItemStack gatherItemStackFromVillage(ItemStack itemStack) {
    return this.brain.gatherItemStackFromVillage(itemStack, null);
  }

  public ItemStack gatherItemStackFromVillage(ItemStack itemStack, BlockPos preferNearestToLoc) {
    return this.brain.gatherItemStackFromVillage(itemStack, preferNearestToLoc);
  }

  public boolean placeItemStackIntoVillage(ItemStack itemStack, Entity entity) {
    return this.brain.placeItemStackIntoVillage(itemStack, entity, null);
  }

  public boolean placeItemStackIntoVillage(ItemStack itemStack, Entity entity, BlockPos preferNearestToLoc) {
    return this.brain.placeItemStackIntoVillage(itemStack, entity, preferNearestToLoc);
  }

  public boolean hasItemStackInVillage(ItemStack itemStack){
    return this.brain.hasItemStackInVillage(itemStack);
  }

  public ArrayList<ItemStack> getVillageInventory(){
    return this.brain.getVillageInventory();
  }

  public void logEvent(BkEvent event){
    this.brain.logEvent(event);
  }

  public StructureInProgress getCurrentProject() {
    return currentProject;
  }

}
