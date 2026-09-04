package com.quzzar.kithkyn.entities.ai.goals.work;

import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Provisioning for a job that spends supplies away from any chest: a CARRY
 * step that walks to a real chest and fills the pack to a working level
 * (docs/worker-loops.md, "Nothing teleports").
 *
 * The general fetch half that {@link FetchBonemealStep} is the composter-shaped
 * special case of. A worker whose loop consumes an item far from storage - the
 * herder spending wheat in the pasture - runs this ahead of that loop: it
 * engages only when the pack is empty of the item, so it never competes with
 * the work itself, and it fetches a batch so one trip covers many spends. A
 * village with none in store leaves the step dormant and the spending loop
 * finds its own pack empty, which is what a real shortage looks like.
 */
public final class FetchStep implements BlockWorkStep {

  private final Item item;
  private final int packTarget;
  private final Predicate<RealPerson> when;

  /**
   * @param item       what to keep in the pack
   * @param packTarget how many one trip tops the pack up to
   */
  public FetchStep(Item item, int packTarget) {
    this(item, packTarget, person -> true);
  }

  /**
   * A fetch a worker only runs while a condition holds: the butcher fetches
   * breeding wheat only while it is standing in for a missing herder, so it
   * never hauls grain it has no herd loop to spend (HerdStep#tends).
   *
   * @param item       what to keep in the pack
   * @param packTarget how many one trip tops the pack up to
   * @param when       tested each round; a false answer leaves the step dormant
   */
  public FetchStep(Item item, int packTarget, Predicate<RealPerson> when) {
    this.item = item;
    this.packTarget = packTarget;
    this.when = when;
  }

  @Override
  @Nullable
  public BlockPos select(RealPerson person) {
    Village village = person.getVillage();
    if (village == null || !this.when.test(person)) {
      return null;
    }
    // Only an empty pocket is worth the trip; a part-load keeps working.
    if (PackLogistics.carried(person, this.item) > 0) {
      return null;
    }
    return PackLogistics.chestHolding(person, village,
        List.of(new ItemStack(this.item, this.packTarget)));
  }

  @Override
  public boolean act(RealPerson person, BlockPos target) {
    Container chest = PackLogistics.containerAt(person, target);
    if (chest == null) {
      return false; // the chest was taken out from under them
    }
    PackLogistics.pullWanted(person, chest,
        List.of(new ItemStack(this.item, this.packTarget)),
        person.getOccupation().name());
    return false; // one visit per select: full or not, back to the work
  }

  @Override
  public String describe() {
    return "fetching supplies";
  }

  @Override
  public String activity() {
    return "fetching " + this.item.getDescription().getString().toLowerCase(java.util.Locale.ROOT)
        + " from the stores";
  }
}
