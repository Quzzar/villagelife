package com.quzzar.villagelife.entities.ai.goals.work;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.FarmedStock;
import com.quzzar.villagelife.village.LocationManager;
import com.quzzar.villagelife.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * Keeping the herd at its size: the butcher's SLAUGHTER step, and the reason a
 * pen stops growing.
 *
 * The herder breeds the pen ({@link HerdStep}) and nobody used to thin it: the
 * hunter is coded to ignore farmed stock and the herder is non-lethal by
 * design, so a butchery with wheat in store bred until the animals filled the
 * pen wall to wall and the people working in it could no longer reach its
 * door. The herd's size is two numbers (FarmedStock): the herder breeds a kind
 * up to {@link FarmedStock#BREED_CAP}, and once it has grown to that many this
 * step thins it to {@link FarmedStock#KEEP}, one grown animal at a time,
 * nearest first, and the herder breeds it back up. A kind between the two
 * numbers is left alone, so the pen swells and is thinned in cycles rather
 * than losing a cow the moment a calf is born.
 *
 * A slaughter is one blow, not a fight. A butcher's animal is not game to be
 * chased round the pen, and a wounded cow bolting through a crowd is exactly
 * the chaos the cap exists to prevent. The drops fall where it stood, and the
 * butcher steps onto them to gather them, since nothing is picked up from a
 * distance. Raw meat then stays in the pack for the cook loop, which cooks
 * from the pack before it fetches ({@link CraftStep}); the hide, feathers and
 * wool are carried to a chest once the round is done, so the leather grant
 * reaches the stores instead of riding in a pocket until the general haul trip
 * ({@link HaulStep}) has enough to be worth the walk.
 */
public final class CullStep implements WorkStep<CullStep.Job> {

  /**
   * The kinds a cull is under way on: entered when a kind reaches the breeding
   * cap, left when it is down to what the pen keeps. The state is this
   * butcher's own and starts empty, so after a restart a half-thinned pen
   * simply breeds up to the cap again before the next cull.
   */
  private final Set<Class<?>> thinning = new HashSet<>();

  /** How far from the kill the butcher will step to gather what fell. */
  private static final double GATHER_RADIUS = 3.0D;

  /** Acts spent gathering before whatever is left is left to lie. */
  private static final int GATHER_ACTS = 6;

  /** Arm's length: the blow is dealt beside the animal, never across the pen. */
  private static final double SLAUGHTER_REACH_SQR = 4.0D;

  /** On the drop itself, or the block beside it: that is where a pickup happens. */
  private static final double GATHER_REACH_SQR = 1.0D;

  /**
   * One round of the butcher's work: an animal to slaughter, or a chest to
   * carry the by-products of the last round to.
   */
  public static final class Job {
    @Nullable
    private final Animal animal;
    @Nullable
    private final BlockPos chest;
    @Nullable
    private BlockPos killSpot;
    @Nullable
    private ItemEntity gathering;
    private int gatherActs;

    private Job(@Nullable Animal animal, @Nullable BlockPos chest) {
      this.animal = animal;
      this.chest = chest;
    }
  }

  @Override
  @Nullable
  public Job select(RealPerson person) {
    BlockPos pen = LocationManager.getJobLocation(person);
    Village village = person.getVillage();
    if (pen == BlockPos.ZERO || village == null) {
      return null;
    }
    Animal surplus = surplus(person, FarmedStock.herds(person.level(), pen));
    if (surplus != null) {
      return new Job(surplus, null);
    }
    // The round is done: whatever the slaughter left in the pack that the cook
    // loop will not use goes to a chest.
    ItemStack byProduct = carriedByProduct(person);
    if (byProduct != null) {
      BlockPos chest = PackLogistics.chestWithRoomFor(person, village, byProduct);
      if (chest != null) {
        return new Job(null, chest);
      }
    }
    return null;
  }

  @Override
  public BlockPos positionOf(Job job) {
    if (job.chest != null) {
      return job.chest;
    }
    if (job.gathering != null && job.gathering.isAlive()) {
      return job.gathering.blockPosition();
    }
    return job.animal.blockPosition();
  }

  @Override
  public boolean act(RealPerson person, Job job) {
    if (job.chest != null) {
      Container chest = PackLogistics.containerAt(person, job.chest);
      if (chest != null) {
        PackLogistics.depositMatching(person, chest, CullStep::isByProduct,
            person.getOccupation().name());
      }
      return false; // one visit; re-select finds the next surplus or nothing
    }
    Animal animal = job.animal;
    if (animal.isAlive()) {
      BlockPos pen = LocationManager.getJobLocation(person);
      if (pen == BlockPos.ZERO || !FarmedStock.pasture(pen).contains(animal.position())) {
        return false; // bolted off the pasture: let go rather than chased
      }
      person.getLookControl().setLookAt(animal, 30.0F, 30.0F);
      person.swing(InteractionHand.MAIN_HAND);
      job.killSpot = animal.blockPosition();
      // The whole of its health in one blow, so it goes down where it stands.
      animal.hurt(person.damageSources().mobAttack(person), animal.getMaxHealth() * 2.0F);
      if (animal.isAlive()) {
        return true; // shrugged off (invulnerability frames): strike again next act
      }
    }
    return gather(person, job);
  }

  @Override
  public String describe() {
    return "the pen";
  }

  @Override
  public String activity() {
    return "thinning the herd in the pen";
  }

  /** One blow a second, and the same pace stepping from drop to drop. */
  @Override
  public int actEveryTicks() {
    return 20;
  }

  @Override
  public double reachSqr(RealPerson person) {
    return 9.0D; // a chest, by default; the pen's targets answer per job below
  }

  @Override
  public boolean inReach(RealPerson person, Job job) {
    double reach = job.chest != null ? reachSqr(person)
        : job.gathering != null && job.gathering.isAlive() ? GATHER_REACH_SQR
        : SLAUGHTER_REACH_SQR;
    return person.blockPosition().distSqr(positionOf(job)) <= reach;
  }

  /**
   * The nearest grown animal of a kind being thinned, or null when no kind is.
   * Kinds enter the cull at the breeding cap and leave it at the keep number,
   * so the two numbers are only ever compared here.
   */
  @Nullable
  private Animal surplus(RealPerson person, Map<Class<?>, List<Animal>> herds) {
    for (Map.Entry<Class<?>, List<Animal>> herd : herds.entrySet()) {
      if (herd.getValue().size() >= FarmedStock.BREED_CAP) {
        this.thinning.add(herd.getKey());
      }
    }
    this.thinning.removeIf(kind -> herds.getOrDefault(kind, List.of()).size() <= FarmedStock.KEEP);
    Animal best = null;
    double nearest = Double.MAX_VALUE;
    for (Class<?> kind : this.thinning) {
      for (Animal animal : herds.getOrDefault(kind, List.of())) {
        if (animal.isBaby()) {
          continue;
        }
        double distance = person.distanceToSqr(animal);
        if (distance < nearest) {
          nearest = distance;
          best = animal;
        }
      }
    }
    return best;
  }

  /**
   * Point the loop at the nearest thing that fell, until nothing is left near
   * the kill or the butcher has spent long enough looking. The pickup itself
   * is the ordinary one every person does by walking over an item.
   */
  private boolean gather(RealPerson person, Job job) {
    if (job.killSpot == null || ++job.gatherActs > GATHER_ACTS) {
      return false;
    }
    ItemEntity nearest = null;
    double best = Double.MAX_VALUE;
    for (ItemEntity drop : person.level().getEntitiesOfClass(ItemEntity.class,
        new AABB(job.killSpot).inflate(GATHER_RADIUS))) {
      if (!drop.isAlive() || drop.getItem().isEmpty()) {
        continue;
      }
      double distance = person.distanceToSqr(drop);
      if (distance < best) {
        best = distance;
        nearest = drop;
      }
    }
    job.gathering = nearest;
    return nearest != null;
  }

  /** What a slaughter leaves that no cook loop will take: hide, feathers, fleece. */
  private static boolean isByProduct(ItemStack stack) {
    return stack.is(Items.LEATHER) || stack.is(Items.FEATHER) || stack.is(ItemTags.WOOL);
  }

  @Nullable
  private static ItemStack carriedByProduct(RealPerson person) {
    Container pack = person.personMainInv;
    for (int slot = 0; slot < pack.getContainerSize(); slot++) {
      ItemStack stack = pack.getItem(slot);
      if (!stack.isEmpty() && isByProduct(stack)) {
        return stack;
      }
    }
    return null;
  }
}
