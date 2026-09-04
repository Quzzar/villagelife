package com.quzzar.kithkyn.village.buildings;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.quzzar.kithkyn.configuration.KithkynConfig;
import com.quzzar.kithkyn.village.Occupation;
import com.quzzar.kithkyn.village.Village;

/** Current demand is a precondition for destructive development, independent of model persuasion. */
public final class RedevelopmentDemand {
  private static final Set<Occupation> FOOD_JOBS = Set.of(Occupation.FARMER, Occupation.FISHER, Occupation.HUNTER);
  private static final Set<String> FOOD_GRANTS = Set.of("GRAIN", "MEAT", "BREAD");

  private RedevelopmentDemand() {
  }

  public record Needs(int missingGeneralBeds, boolean storageFull, boolean foodBelowTarget, int idlePeople,
      Set<Occupation> vacantJobs, Set<String> presentCapabilities) {
  }

  public record Change(int generalBeds, int sharedContainers, Map<Occupation, Integer> jobs, Set<String> capabilities,
      int foodPlots) {
    public Change(int generalBeds, int sharedContainers, Map<Occupation, Integer> jobs, Set<String> capabilities) {
      this(generalBeds, sharedContainers, jobs, capabilities, 0);
    }
  }

  /** Empty means the proposal buys no capacity or capability currently needed. */
  public static String reason(Needs needs, Change change) {
    if (needs.missingGeneralBeds() > 0 && change.generalBeds() > 0) {
      return "independent housing is short by " + needs.missingGeneralBeds() + " beds";
    }
    if (needs.storageFull() && change.sharedContainers() > 0) {
      return "deliveries have overflowed existing storage";
    }
    if (needs.foodBelowTarget() && change.foodPlots() > 0
        && needs.vacantJobs().stream().noneMatch(FOOD_JOBS::contains)) {
      return "food is below target and the plan adds " + change.foodPlots() + " crop plots";
    }
    for (var job : change.jobs().entrySet()) {
      if (job.getValue() <= 0 || needs.vacantJobs().contains(job.getKey())) {
        continue;
      }
      if (FOOD_JOBS.contains(job.getKey())) {
        if (needs.foodBelowTarget()) {
          return "food is below target and existing food posts are filled";
        }
      } else if (needs.idlePeople() > 0) {
        return "idle residents have no open " + job.getKey().name().toLowerCase() + " post";
      }
    }
    for (String capability : change.capabilities()) {
      if (needs.presentCapabilities().contains(capability) || capability.equals("STORAGE")) {
        continue;
      }
      if (FOOD_GRANTS.contains(capability) && !needs.foodBelowTarget()) {
        continue;
      }
      return "the village lacks " + capability.toLowerCase();
    }
    return "";
  }

  public static String reason(Village village, BuildingInfo target, Collection<Building> affected) {
    int beds = generalBeds(target);
    int storage = target.getContainerLocations().size();
    Map<Occupation, Integer> jobs = new HashMap<>();
    target.getWorkLocations().values().forEach(job -> jobs.merge(job, 1, Integer::sum));
    for (Building building : affected) {
      beds -= generalBeds(building.getInfo());
      storage -= building.getInfo().getContainerLocations().size();
      building.getInfo().getWorkLocations().values().forEach(job -> jobs.merge(job, -1, Integer::sum));
    }
    Needs needs = new Needs(Math.max(0, village.getUnhousedAdultResidentCount() + village.getPendingArrivalCount()
        - village.getFreeGeneralBedCount()), village.isStorageStrained(),
        village.getAttractiveness().foodPerCapita() < KithkynConfig.AttractivenessFoodTargetPerCapita,
        village.idlePeople().size(), village.claimableJobs().stream().map(job -> job.getOccupation()).collect(Collectors.toSet()),
        target.getGrants().stream().filter(village::canDo).collect(Collectors.toSet()));
    return reason(needs, new Change(beds, storage, Map.copyOf(jobs), Set.copyOf(target.getGrants()),
        netFoodPlots(village, target, affected)));
  }

  /** Authored crop capacity is measurable; food yield and completion time are not predicted. */
  public static int netFoodPlots(Village village, BuildingInfo target, Collection<Building> affected) {
    return foodPlots(village, target) - affected.stream().mapToInt(building -> foodPlots(village, building.getInfo())).sum();
  }

  private static int foodPlots(Village village, BuildingInfo info) {
    if (info.getGrants().stream().noneMatch(FOOD_GRANTS::contains)) {
      return 0;
    }
    var template = village.getLevel().getLevel().getStructureManager().getOrCreate(
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.quzzar.kithkyn.Kithkyn.MODID, info.getPath()));
    return template.palettes.isEmpty() ? 0 : (int) template.palettes.getFirst().blocks().stream()
        .filter(block -> block.state().is(net.minecraft.tags.BlockTags.CROPS)).count();
  }

  private static int generalBeds(BuildingInfo info) {
    return info.getWorkLocations().isEmpty() || Buildings.VILLAGE_CENTER_CATEGORY.equals(info.getCategory())
        ? info.getBedLocations().size() : 0;
  }
}
