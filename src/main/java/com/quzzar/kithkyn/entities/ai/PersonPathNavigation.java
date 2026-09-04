package com.quzzar.kithkyn.entities.ai;

import java.util.Set;

import javax.annotation.Nullable;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.village.buildings.MineShaft;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

/**
 * Ground navigation for a person: vanilla walking with two answers corrected.
 *
 * <b>Closed fence gates are closed wooden doors.</b> Vanilla reads a closed
 * gate as a fence, so no mob ever plans a route through one: a butchery pen
 * with two gates and a door was, to its own butcher, a yard with one exit, and
 * every trip to the storehouse three blocks beyond the fence became a
 * thirty-block walk out through the building and round it, which his
 * pathfinder could not see the end of. He stood at the fence until the loop
 * gave up on him. A closed gate is now a node the route may pass through at
 * the door's cost, with the opening left to
 * {@link com.quzzar.kithkyn.entities.ai.goals.OpenFenceGateGoal} when they
 * reach it, and the door rules (no diagonal moves through it) apply to gates
 * unchanged because they hang off the node type, not the block.
 *
 * <b>Ladders are places to be.</b> Vanilla mobs can climb - a zombie pressed
 * against a ladder goes up - but never plan to: the search only ever looks
 * sideways, one step up, and down a drop, so a ladder shaft is invisible to it
 * and a watchtower's bed at the top of one was a bed nobody could reach. Here
 * a rung (anything in the climbable tag) is a node the feet can stand in, its
 * floor is its own height rather than whatever lies under the ladder, and it
 * is joined to the rungs above and below it. Reaching the top is vanilla's
 * step-up onto the landing; leaving from the top is vanilla's one-block drop
 * into the top rung. What vanilla will not do on its own is the climb itself
 * with nobody pressing into the wall, so {@link #tick()} supplies the vanilla
 * climbing speed while the next node is straight up, and holds the walk still
 * meanwhile: walking into the wall is what vanilla reads as "climb up", which
 * is the wrong answer on the way down.
 *
 * <b>Route sight is not eyesight.</b> Vanilla uses the follow-range attribute
 * both for sensing and as the maximum distance a path search may expand. That
 * made ordinary routes end after about twenty blocks even though the search
 * still had nodes left. People search at least forty-eight blocks instead,
 * while their genetically varied follow range remains their perception range.
 * The vanilla node ceiling still bounds the work, and its navigation region
 * still substitutes empty chunks rather than loading missing ones.
 *
 * <b>A mine shaft is walked by its ramp.</b> A long vertical target also makes
 * vanilla's best partial path end at the surface directly above it. Any route
 * that starts or ends down a shaft is therefore routed by the ramp a hop at a
 * time ({@link MineShaft#waypoint}), under {@code moveTo}, so every goal gets
 * it.
 */
public final class PersonPathNavigation extends GroundPathNavigation {

  /** Vanilla's climb, which LivingEntity grants only to a mob pressing into the ladder. */
  private static final double CLIMB_SPEED = 0.2D;

  /** Squared: the next node is in this ladder's column, not off to one side of it. */
  private static final double SAME_COLUMN_SQR = 0.25D;

  /** Bounded route-planning horizon, independent of a person's perception range. */
  private static final float MINIMUM_SEARCH_RANGE = 48.0F;

  public PersonPathNavigation(Mob mob, Level level) {
    super(mob, level);
  }

  @Override
  protected PathFinder createPathFinder(int maxVisitedNodes) {
    PersonNodeEvaluator evaluator = new PersonNodeEvaluator();
    evaluator.setCanPassDoors(true);
    this.nodeEvaluator = evaluator;
    return new MeasuredPathFinder(evaluator, maxVisitedNodes);
  }

  /**
   * Keep perception local without forcing route planning to stop at the same
   * radius. The five-argument vanilla implementation retains both its node
   * budget and its loaded-chunk-only {@link PathNavigationRegion}.
   */
  @Override
  @Nullable
  protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy) {
    float perceptionRange = (float)this.mob.getAttributeValue(Attributes.FOLLOW_RANGE);
    return super.createPath(targets, regionOffset, offsetUpward, accuracy,
        Math.max(MINIMUM_SEARCH_RANGE, perceptionRange));
  }

  /**
   * The path to {@code pos}, or to the next ramp waypoint on the way when this
   * walk starts or ends down one of the village's mine shafts. The goal keeps
   * asking for its real target, and each answer is the next hop from wherever
   * the walker has got to, so the shaft is descended and climbed by the ramp.
   */
  @Override
  @Nullable
  public Path createPath(BlockPos pos, int accuracy) {
    if (this.mob instanceof RealPerson person && person.getVillage() != null) {
      if (MineShaft.belowExcavation(person.getVillage(), this.mob.blockPosition())) {
        Kithkyn.LOGGER.info(
            "[mine] {} fell below the planned shaft and has been brought back to the village center",
            person.getFullName());
        person.tpToHome();
        return null;
      }
      BlockPos hop = MineShaft.waypoint(person.getVillage(), this.mob.blockPosition(), pos);
      if (hop != null) {
        return super.createPath(hop, accuracy);
      }
    }
    return super.createPath(pos, accuracy);
  }

  /** A climb is a path in progress, though the feet are off the ground. */
  @Override
  protected boolean canUpdatePath() {
    return super.canUpdatePath() || this.mob.onClimbable();
  }

  /** On a rung the feet go at the rung, not on the floor beneath the ladder. */
  @Override
  protected double getGroundY(Vec3 vec) {
    return isClimbable(this.level.getBlockState(BlockPos.containing(vec))) ? vec.y : super.getGroundY(vec);
  }

  @Override
  public void tick() {
    super.tick();
    if (this.path == null || this.isDone()) {
      return;
    }
    Vec3 next = this.path.getNextEntityPos(this.mob);
    double dx = next.x - this.mob.getX();
    double dz = next.z - this.mob.getZ();
    if (dx * dx + dz * dz > SAME_COLUMN_SQR) {
      return; // leaving the ladder sideways is the move control's ordinary walk
    }
    boolean onLadder = this.mob.onClimbable();
    if (!onLadder && !isClimbable(this.level.getBlockState(this.path.getNextNodePos()))) {
      return;
    }
    // A rung straight above or below: hold the walk still and let the ladder
    // do it. Pointed at the feet, the move control asks for no stride at all,
    // which matters more than it looks. The first cut held only while on the
    // ladder, and a person hovering in the air cell just over the top rung
    // was not: the move control strode them into the shaft wall, vanilla read
    // the wall-press as "climb up" the moment they dipped into the rung, and
    // they bobbed at the ladder's top for good. Held from here, they simply
    // fall into the shaft and slide.
    this.mob.getMoveControl().setWantedPosition(this.mob.getX(), this.mob.getY(), this.mob.getZ(), 0.0D);
    if (onLadder && next.y > this.mob.getY() + 0.05D) {
      Vec3 motion = this.mob.getDeltaMovement();
      this.mob.setDeltaMovement(motion.x, CLIMB_SPEED, motion.z);
    }
    // Downward needs nothing: a body on a ladder slides at the ladder's own rate.
  }

  private static boolean isClimbable(BlockState state) {
    return state.is(BlockTags.CLIMBABLE);
  }

  /** Debug-only measurements around the stock weighted A* search. */
  private static final class MeasuredPathFinder extends PathFinder {

    private final PersonNodeEvaluator evaluator;
    private final int baseMaxVisitedNodes;

    private MeasuredPathFinder(PersonNodeEvaluator evaluator, int maxVisitedNodes) {
      super(evaluator, maxVisitedNodes);
      this.evaluator = evaluator;
      this.baseMaxVisitedNodes = maxVisitedNodes;
    }

    @Override
    @Nullable
    public Path findPath(PathNavigationRegion region, Mob mob, Set<BlockPos> targets, float maxRange,
        int accuracy, float searchDepthMultiplier) {
      if (!Kithkyn.LOGGER.isDebugEnabled()) {
        return super.findPath(region, mob, targets, maxRange, accuracy, searchDepthMultiplier);
      }

      this.evaluator.resetSearchMetrics();
      long started = System.nanoTime();
      Path result = super.findPath(region, mob, targets, maxRange, accuracy, searchDepthMultiplier);
      long elapsedMicros = (System.nanoTime() - started) / 1_000L;
      int expandedNodes = this.evaluator.expandedNodeCount();
      int nodeLimit = (int)(this.baseMaxVisitedNodes * searchDepthMultiplier);
      boolean nodeLimitHit = expandedNodes >= nodeLimit - 1;
      Node end = result == null ? null : result.getEndNode();
      String outcome = result == null ? "none" : result.canReach() ? "reached" : "partial";

      Kithkyn.LOGGER.debug(
          "[path] {} at {} to {}: {}, end {}, remaining {}, path nodes {}, expanded {}/{}, discovered {}, "
              + "{} us, range {}, accuracy {}, node limit hit {}",
          mob.getName().getString(), mob.blockPosition(), targets, outcome,
          end == null ? null : end.asBlockPos(), result == null ? null : result.getDistToTarget(),
          result == null ? 0 : result.getNodeCount(), expandedNodes, nodeLimit,
          this.evaluator.discoveredNodeCount(), elapsedMicros, maxRange, accuracy, nodeLimitHit);
      return result;
    }
  }

  /** Vanilla's walking evaluator, with gates read as doors and rungs as ground. */
  private static final class PersonNodeEvaluator extends WalkNodeEvaluator {

    private int expandedNodeCount;

    private void resetSearchMetrics() {
      this.expandedNodeCount = 0;
    }

    private int discoveredNodeCount() {
      return this.nodes.size();
    }

    private int expandedNodeCount() {
      return this.expandedNodeCount;
    }

    @Override
    public Node getStart() {
      // Mid-climb the feet are off the ground, and vanilla would start the
      // route from the floor under the ladder: a person re-planning halfway up
      // would be walked back down to begin again. The cell just over the top
      // rung counts too: that is where a person stands for a tick between the
      // landing and the ladder.
      BlockPos feet = this.mob.blockPosition();
      if (this.mob.onClimbable()) {
        return this.getStartNode(feet);
      }
      if (!this.mob.onGround() && isClimbable(this.currentContext.getBlockState(feet.below()))) {
        return this.getStartNode(feet.below());
      }
      return super.getStart();
    }

    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
      PathType type = super.getPathType(context, x, y, z);
      BlockState state = context.getBlockState(new BlockPos(x, y, z));
      if (type == PathType.FENCE && state.getBlock() instanceof FenceGateBlock) {
        return PathType.DOOR_WOOD_CLOSED;
      }
      if (type == PathType.OPEN && isClimbable(state)) {
        return PathType.WALKABLE; // a rung is somewhere the feet can be
      }
      return type;
    }

    /** A rung's floor is the rung, not whatever is under the ladder. */
    @Override
    protected double getFloorLevel(BlockPos pos) {
      return isClimbable(this.currentContext.getBlockState(pos)) ? pos.getY() : super.getFloorLevel(pos);
    }

    @Override
    public int getNeighbors(Node[] outputArray, Node node) {
      this.expandedNodeCount++;
      int count = super.getNeighbors(outputArray, node);
      if (!isClimbable(this.currentContext.getBlockState(new BlockPos(node.x, node.y, node.z)))) {
        return count;
      }
      for (int step : new int[] {1, -1}) {
        Node rung = rung(node.x, node.y + step, node.z);
        if (this.isNeighborValid(rung, node)) {
          outputArray[count++] = rung;
        }
      }
      return count;
    }

    /** The rung at this cell as a node, or null where there is none or no room above it. */
    @Nullable
    private Node rung(int x, int y, int z) {
      BlockPos pos = new BlockPos(x, y, z);
      if (!isClimbable(this.currentContext.getBlockState(pos))) {
        return null;
      }
      // A closed trapdoor over the top rung is a lid, not a hatch; the mob
      // type check below reads any trapdoor as passable.
      BlockState above = this.currentContext.getBlockState(pos.above());
      if (above.getBlock() instanceof TrapDoorBlock && !above.getValue(TrapDoorBlock.OPEN)) {
        return null;
      }
      PathType type = this.getCachedPathType(x, y, z);
      float malus = this.mob.getPathfindingMalus(type);
      if (malus < 0.0F) {
        return null; // no head room, or something worse in the cell
      }
      Node rung = this.getNode(x, y, z);
      rung.type = type;
      rung.costMalus = Math.max(rung.costMalus, malus);
      return rung;
    }
  }
}
