package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * The road. A roaming wanderer, someone with no village who left one or was
 * orphaned from it, walks a heading every day: a fresh one chosen at each
 * dawn, aimless by design (Aaron, 2026-09-02: "every day give them a
 * direction, and they'll just wander in that given direction that day"). The
 * foraging steps outrank this walk, so game and timber met on the way are a
 * stop, and the walk resumes from wherever the stop ended
 * (docs/population-and-labor.md, "The road").
 *
 * <p>The walk is one leg at a time, each aimed a follow-range step along the
 * heading from where the wanderer stands. Legs are short on purpose: the
 * ground navigator refuses outright any target whose chunk is not loaded
 * (GroundPathNavigation.createPath), so aiming far along the heading produced
 * a wanderer who stood at the village edge forever. Terrain is met by turning,
 * not by pathing around it: a leg over loaded ground that gains no ground
 * ALONG THE HEADING swings the heading. Along the heading, not in any
 * direction: the first live walk to meet a shoreline milled about an
 * eight-block patch for six minutes, moving all the while and never forward.
 * Ground that is not loaded is not terrain, it is the end of the world for
 * now, and the wanderer waits at it rather than turning back.
 *
 * <p>Nobody vanishes for walking. A wanderer who walks out of the ticking
 * world freezes where they stand, like every other entity, and resumes when it
 * comes back. Two earlier designs crossed them into the WandererPool, at a
 * fixed distance and then at the edge of the loaded world, and both put the
 * crossing where a player could watch a wanderer blink out, or never saw the
 * walk at all as chunks came and went (Aaron). The pool is fed only at the
 * village edge now, by leavers past the wanderer cap (Village.tickTravelers).
 *
 * <p>Sits below fleeing and fighting, and {@link StrollAroundVillage} yields to
 * it, so a roaming wanderer neither loiters nor walks through monsters. Nobody
 * sleeps rough, by decision; a wanderer carrying three logs camps for the
 * night at a fire of their own ({@code CampStep}, which outranks this walk)
 * and the road resumes at dawn, while one with no logs walks the night through.
 */
public class RoamGoal extends Goal {

    private static final double SPEED = 0.6D;

    /** Blocks each leg reaches ahead: a follow-range step, so the whole leg is ground the navigator can see. */
    private static final int LEG = 16;

    /** Goal ticks between checks; the selector ticks a running goal every other game tick. */
    private static final int CHECK_EVERY = 10;

    /** Game ticks between stuck samples, and the headway a free walk makes along the heading in that time. */
    private static final int STUCK_SAMPLE_TICKS = 100;
    private static final double STUCK_HEADWAY = 4.0D;

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
        // A following household member shadows the lead instead (FollowFamilyGoal); only the
        // lead, a single wanderer, or one whose family has left the loaded world
        // walks a heading of their own.
        return person.isRoamingWanderer() && person.getTravelTarget() == null
                && !person.followsFamilyOnTheRoad();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        ticks = 0;
        resample();
        person.turnWithTheDay();
        // A leaver who walked out with logs in the pack, or a wanderer back on
        // the road after a stop, checks their empty hand before setting off.
        person.toolUpFromPack();
        aim();
    }

    @Override
    public void tick() {
        if (++ticks % CHECK_EVERY != 0) {
            return;
        }
        if (person.turnWithTheDay()) {
            // A new day, a new heading: yesterday's stuck sample says nothing about it.
            resample();
            aim();
            return;
        }
        long now = person.level().getGameTime();
        if (now - lastSample >= STUCK_SAMPLE_TICKS) {
            if (legOnLoadedGround && headwaySince(samplePos) < STUCK_HEADWAY) {
                // Something in the way: swing anywhere from a quarter to a half
                // turn, either side, and try that way instead.
                double swing = (Math.PI / 2) + person.getRandom().nextDouble() * (Math.PI / 2);
                person.turnRoamHeading(person.getRandom().nextBoolean() ? swing : -swing);
            }
            resample();
            aim();
        } else if (person.getNavigation().isDone()) {
            aim();
        }
    }

    @Override
    public void stop() {
        person.getNavigation().stop();
    }

    private void resample() {
        lastSample = person.level().getGameTime();
        samplePos = person.position();
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

    /** Ground gained along the heading since {@code from}: the walk's projection, so pacing a shore counts as none. */
    private double headwaySince(Vec3 from) {
        double dx = person.getX() - from.x;
        double dz = person.getZ() - from.z;
        return dx * Math.cos(person.getRoamHeading()) + dz * Math.sin(person.getRoamHeading());
    }
}
