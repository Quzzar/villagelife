package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;

import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * The road out. A wanderer with no village walks the heading they set out on,
 * leg by leg, until they are far enough from where they started to pass beyond
 * the horizon ({@link RealPerson#crossHorizon}): out of the loaded world and
 * into the pool every growing village recruits from
 * (docs/population-and-labor.md). That is how "travel the world" works in a
 * world that only exists near players: the visible part is the walk away, the
 * rest is bookkeeping.
 *
 * <p>The walk is one leg at a time, each aimed a follow-range step along the
 * heading from where the wanderer stands. Legs are short on purpose: the
 * ground navigator refuses outright any target whose chunk is not loaded
 * (GroundPathNavigation.createPath), and a point a horizon away almost never
 * is, so aiming at the horizon itself produced a wanderer who stood at the
 * village edge forever. Terrain is met by turning, not by pathing around it: a
 * leg over loaded ground that gains nothing swings the heading. Ground that is
 * not loaded is not terrain, it is the end of the world for now, and the
 * wanderer waits at it rather than turning back. A walk that has not reached
 * the horizon after several travel timeouts is taken as blocked, and the
 * wanderer moves on beyond the horizon from wherever they stand.
 *
 * <p>Sits below fleeing and fighting, and {@link StrollAroundVillage} yields to
 * it, so a roaming wanderer neither loiters nor walks through monsters. There
 * is no sleeping on the road: nobody sleeps rough, by decision, and the walk
 * simply goes on through the night.
 */
public class RoamGoal extends Goal {

    private static final double SPEED = 0.6D;

    /** Blocks each leg reaches ahead: a follow-range step, so the whole leg is ground the navigator can see. */
    private static final int LEG = 16;

    /** Goal ticks between checks; the selector ticks a running goal every other game tick. */
    private static final int CHECK_EVERY = 10;

    /** Game ticks between stuck samples, and the ground a free walk covers in that time. */
    private static final int STUCK_SAMPLE_TICKS = 100;
    private static final double STUCK_DISTANCE_SQR = 9.0D;

    /** Travel timeouts a walk gets to reach the horizon before it is taken as blocked. */
    private static final int PATIENCE_TIMEOUTS = 4;

    private final RealPerson person;

    private int ticks;
    private long lastSample;
    private Vec3 samplePos = Vec3.ZERO;

    /** Whether the last leg was over loaded ground; only such a leg can count as blocked. */
    private boolean legOnLoadedGround;

    public RoamGoal(RealPerson person) {
        this.person = person;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return person.isRoaming() && person.getVillage() == null && person.getTravelTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        ticks = 0;
        lastSample = person.level().getGameTime();
        samplePos = person.position();
        aim();
    }

    @Override
    public void tick() {
        if (++ticks % CHECK_EVERY != 0) {
            return;
        }
        long now = person.level().getGameTime();
        if (pastHorizon(now)) {
            person.crossHorizon();
            return;
        }
        if (now - lastSample >= STUCK_SAMPLE_TICKS) {
            if (legOnLoadedGround && person.position().distanceToSqr(samplePos) < STUCK_DISTANCE_SQR) {
                // Something in the way: swing anywhere from a quarter to a half
                // turn, either side, and try that way instead.
                double swing = (Math.PI / 2) + person.getRandom().nextDouble() * (Math.PI / 2);
                person.turnRoamHeading(person.getRandom().nextBoolean() ? swing : -swing);
            }
            lastSample = now;
            samplePos = person.position();
            aim();
        } else if (person.getNavigation().isDone()) {
            aim();
        }
    }

    @Override
    public void stop() {
        person.getNavigation().stop();
    }

    /**
     * Sends the navigator one leg along the heading from where the wanderer
     * stands. A leg into ground that is not loaded is not attempted: the
     * navigator would refuse it, and the wanderer waits at the edge of the
     * world for it to load rather than reading the wait as a wall.
     */
    private void aim() {
        double x = person.getX() + Math.cos(person.getRoamHeading()) * LEG;
        double z = person.getZ() + Math.sin(person.getRoamHeading()) * LEG;
        legOnLoadedGround = person.level().hasChunkAt(BlockPos.containing(x, person.getY(), z));
        if (legOnLoadedGround) {
            person.getNavigation().moveTo(x, person.getY(), z, SPEED);
        }
    }

    private boolean pastHorizon(long now) {
        BlockPos origin = person.getRoamOrigin();
        if (origin == null) {
            return false;
        }
        double dx = person.getX() - origin.getX();
        double dz = person.getZ() - origin.getZ();
        double horizon = VillagelifeConfig.WandererHorizonDistance;
        if (dx * dx + dz * dz >= horizon * horizon) {
            return true;
        }
        long patience = VillagelifeConfig.TravelTimeoutSeconds * 20L * PATIENCE_TIMEOUTS;
        return now - person.getRoamSince() >= patience;
    }
}
