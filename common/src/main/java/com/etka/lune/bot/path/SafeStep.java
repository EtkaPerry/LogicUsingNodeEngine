package com.etka.lune.bot.path;

import com.etka.lune.bot.BotContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A step on foot with no route behind it: back from a mob, out of a fireball's line, away from a
 * Creeper. These are the moves made in a hurry, a block at a time, on ground nothing has planned -
 * so whatever decides them has to look at the ground itself, and the feet have to go where it
 * looked.
 *
 * <h2>Why it is one class</h2>
 *
 * <p>Self Preservation used to pick a direction, check the one block it pointed at, then turn the
 * head that way and hold forward and sprint. The turn is eased, so for most of a second the feet
 * went where the head had been rather than where the check had looked; in the backpedal the head
 * was also aimed at the mob on the same tick, so the two turns cancelled, the head crept off the
 * mob a few degrees a tick, and forward-and-sprint ran the bot at the mob and round it. Where the
 * check did find a step up it pressed jump with sprint held, which carries a body three blocks past
 * the one that was looked at. At a bastion that was the gaps in the floor and the lava under the
 * bridges (2026-10-02). Kill's retreat, back-off and strafe pressed their keys with no check at
 * all. One set of rules now serves all of them.</p>
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li><b>The keys go where the check looked.</b> A step is steered at the middle of the block
 *   that was checked ({@link com.etka.lune.bot.input.BotInput#steerToward}), so it is walked the
 *   same whichever way the head points - at the mob, mid-turn, or held by a player who kept the
 *   mouse. The head is the caller's business and never the feet's.</li>
 *   <li><b>One block, onto ground.</b> Level, a step up or a step down, into a block the body can
 *   stand in out of the water, and never beside lava.</li>
 *   <li><b>A diagonal only between two pieces of ground.</b> Its corners are crossed on the way, so
 *   both have to be ground the body could stand on too - never the lip of a hole.</li>
 *   <li><b>Up and down only straight, and never at a run.</b> A step up is jumped from against the
 *   face of the block: a jump taken from further back clears the face with all its speed and lands
 *   past the block that was checked. A step down lands a block further on than it looks, so the
 *   block past it has to be safe as well, or a wall.</li>
 *   <li><b>Off the lip when there is a choice.</b> A hit knocks the body the way it was already
 *   going, so ground beside a drop that hurts is taken only when nothing else is left - which, on
 *   a one-wide bridge, it is.</li>
 *   <li><b>In the air, toward the landing.</b> Air control is small, but steering at the step being
 *   taken is all there is. Letting go of the keys is a drift.</li>
 *   <li><b>Stuck is not resting.</b> See {@link #STALL_TICKS}.</li>
 * </ul>
 */
public final class SafeStep {

    /**
     * Ticks spent on one block before the order of preference is turned.
     *
     * <p>Every candidate is judged by the ground under it, and a direction can pass that and still
     * be a wall the bot walks into: a tree trunk, a ledge it cannot climb, the corner it is already
     * wedged in. The test is deterministic, so the same wrong answer comes back every tick for as
     * long as the danger lasts - a run measured against a Creeper spent 67 ticks holding forward
     * and sprint at one block, "retreating", until the Creeper caught up and took 16 of its 20
     * health, and did it again nine hundred ticks later. So feet that have not changed block for
     * this long turn the preference, and a different direction wins. Anything that moves resets it,
     * so an escape that is working is never second-guessed.</p>
     *
     * <p>Under half a second, because a Creeper's fuse is thirty ticks and the bot has to have given
     * up on a blocked direction and be moving down another well inside that.</p>
     */
    public static final int STALL_TICKS = 8;

    /**
     * How close the front of the body has to be to a step's face before the jump, in blocks.
     *
     * <p>Close enough that the body meets the face on the way up: the face stops it there, and it
     * climbs on at whatever speed the air allows, which lands it on the step and not past it.</p>
     */
    static final double FACE_REACH = 0.35;

    /**
     * Ticks a step stays the one under way once the feet are off the ground. A jump onto a step is
     * about a dozen, and a hit can keep a body up a little longer; past that the step belongs to
     * some earlier moment, and steering at it would be steering at a memory.
     */
    private static final int AIR_TICKS = 30;

    /** The eight ways out of a block, each a neighbour's offset. */
    private static final int[][] WAYS = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    /** How a step changes the height of the feet. */
    public enum Rise { LEVEL, UP, DOWN }

    /**
     * One step: from the block the feet are in to the one they will be in.
     *
     * @param from    the feet block the step was chosen from
     * @param dx      the way it goes along x, -1, 0 or 1
     * @param dz      the way it goes along z, -1, 0 or 1
     * @param landing the feet block at the end of it
     */
    public record Step(BlockPos from, int dx, int dz, Rise rise, BlockPos landing) {

        /** Where the keys steer: the middle of the landing. */
        public Vec3 aim() {
            return Vec3.atBottomCenterOf(landing);
        }

        /** The way the step goes, flat and of unit length, for a caller that turns the head along it. */
        public Vec3 direction() {
            return new Vec3(dx, 0.0, dz).normalize();
        }
    }

    private BlockPos stallFeet;
    private int stallTicks;
    private int turn;
    /** The step under way, which is still the one being taken while the feet are off the ground. */
    private Step taking;
    private int takenAt;

    /**
     * The step to take this tick, as near to {@code desired} as the ground allows, or null when
     * there is none: nowhere safe to put a foot, or the body in water or lava, which are not walked.
     * While the feet are off the ground it is the step already under way.
     *
     * @param walking whether the caller will walk the answer. Holding position on purpose is not
     *                being stuck - batting a fireball means standing where the shot was aimed - and
     *                counting those ticks as a stall turned the sidestep away from the one chosen.
     */
    public Step toward(BotContext ctx, Vec3 desired, boolean walking) {
        LocalPlayer player = ctx.player;
        if (player.isInWater() || player.isInLava()) {
            taking = null;
            return null;
        }
        if (!player.onGround()) {
            return taking != null && player.tickCount - takenAt <= AIR_TICKS ? taking : null;
        }
        BlockPos feet = MovementHelper.feetPosition(player);
        if (!walking || !feet.equals(stallFeet)) {
            stallFeet = feet.immutable();
            stallTicks = 0;
            turn = 0;
        } else if (++stallTicks >= STALL_TICKS) {
            stallTicks = 0;
            turn++;
        }
        taking = choose(ctx.level, feet, desired, turn);
        takenAt = player.tickCount;
        return taking;
    }

    /**
     * Presses the keys that take this step, whichever way the head points.
     *
     * @param run whether to sprint. Only ever on level ground: a step up or down is walked.
     */
    public void steer(BotContext ctx, Step step, boolean run) {
        LocalPlayer player = ctx.player;
        ctx.input.steerToward(player, step.aim());
        boolean grounded = player.onGround();
        ctx.input.sprint = run && grounded && step.rise() == Rise.LEVEL;
        ctx.input.jump = grounded && step.rise() == Rise.UP
                && (player.horizontalCollision
                        || atTheFace(player.getX(), player.getZ(), player.getBbWidth() / 2.0, step));
    }

    /** Forgets the step under way and any stall, for a new danger or the end of one. */
    public void forget() {
        stallFeet = null;
        stallTicks = 0;
        turn = 0;
        taking = null;
    }

    /**
     * The safest step there is from {@code feet}, nearest {@code desired} first.
     *
     * <p>Two passes over the same order: the first takes only ground away from a lip, the second
     * whatever ground there is. {@code turn} starts the order further round, which is how a stall
     * makes a different direction win.</p>
     */
    public static Step choose(BlockGetter level, BlockPos feet, Vec3 desired, int turn) {
        List<int[]> order = preference(desired);
        Step onALip = null;
        for (int i = 0; i < order.size(); i++) {
            int[] way = order.get(Math.floorMod(i + turn, order.size()));
            Step step = along(level, feet, way[0], way[1]);
            if (step == null) {
                continue;
            }
            if (!onTheLip(level, step.landing())) {
                return step;
            }
            if (onALip == null) {
                onALip = step;
            }
        }
        return onALip;
    }

    /** The step one block along {@code (dx, dz)} from {@code feet}, or null when there is no safe one. */
    public static Step along(BlockGetter level, BlockPos feet, int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return null;
        }
        BlockPos ahead = feet.offset(dx, 0, dz);
        if (dx != 0 && dz != 0) {
            boolean corners = ground(level, feet.offset(dx, 0, 0))
                    && ground(level, feet.offset(0, 0, dz));
            return corners && ground(level, ahead)
                    ? new Step(feet.immutable(), dx, dz, Rise.LEVEL, ahead)
                    : null;
        }
        if (ground(level, ahead)) {
            return new Step(feet.immutable(), dx, dz, Rise.LEVEL, ahead);
        }
        BlockPos up = ahead.above();
        if (MovementHelper.isPassable(level, feet.above(2)) && ground(level, up)) {
            return new Step(feet.immutable(), dx, dz, Rise.UP, up);
        }
        BlockPos down = ahead.below();
        if (MovementHelper.isPassable(level, ahead.above()) && ground(level, down)
                && landsSafelyPast(level, down.offset(dx, 0, dz))) {
            return new Step(feet.immutable(), dx, dz, Rise.DOWN, down);
        }
        return null;
    }

    /**
     * Whether ground stands beside a drop that hurts, which a hit could push the body over. Lava
     * beside it is not a lip but a refusal, and {@link #along} has already made it.
     */
    public static boolean onTheLip(BlockGetter level, BlockPos feet) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos beside = feet.relative(side);
            if (MovementHelper.hasBodyClearance(level, beside)
                    && MovementHelper.fallHurts(level, beside)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether drifting from {@code feet} along {@code direction} takes the body somewhere that
     * hurts: lava beside or under the next block, something that burns, or a fall that costs health.
     *
     * <p>Not the whole step test. This is for the moves that drift rather than step - a strafe laid
     * over a route, the last nudge toward a drop the router could not reach - which may well go
     * where nobody could stand, into a hollow or the roots of a tree, but never there.</p>
     */
    public static boolean hazardousToward(BlockGetter level, BlockPos feet, Vec3 direction) {
        List<int[]> order = preference(direction);
        if (order.isEmpty()) {
            return false;
        }
        int[] way = order.get(0);
        if (hazardous(level, feet.offset(way[0], 0, way[1]))) {
            return true;
        }
        return way[0] != 0 && way[1] != 0
                && (hazardous(level, feet.offset(way[0], 0, 0))
                        || hazardous(level, feet.offset(0, 0, way[1])));
    }

    /**
     * Whether the front of the body is against a step's face, or close enough to meet it on the
     * way up. Steps up are straight, so one of {@code dx} and {@code dz} is zero.
     */
    static boolean atTheFace(double x, double z, double halfWidth, Step step) {
        double gap;
        if (step.dx() > 0) {
            gap = step.from().getX() + 1 - (x + halfWidth);
        } else if (step.dx() < 0) {
            gap = (x - halfWidth) - step.from().getX();
        } else if (step.dz() > 0) {
            gap = step.from().getZ() + 1 - (z + halfWidth);
        } else {
            gap = (z - halfWidth) - step.from().getZ();
        }
        return gap <= FACE_REACH;
    }

    /** The eight ways out of a block, nearest the wanted direction first; empty when it has none. */
    static List<int[]> preference(Vec3 desired) {
        if (desired.x * desired.x + desired.z * desired.z < 1.0E-8) {
            return List.of();
        }
        double want = Math.atan2(desired.z, desired.x);
        List<int[]> ways = new ArrayList<>(List.of(WAYS));
        ways.sort(Comparator.comparingDouble(way -> turnFrom(want, way)));
        return ways;
    }

    /** How far a way turns from the one wanted. A turn one way sorts just before the same turn the other. */
    private static double turnFrom(double want, int[] way) {
        double turn = Math.atan2(way[1], way[0]) - want;
        turn = Math.atan2(Math.sin(turn), Math.cos(turn));
        return Math.abs(turn) + (turn < 0.0 ? 1.0E-9 : 0.0);
    }

    /** Ground the body can stand in, out of the water and away from lava. */
    private static boolean ground(BlockGetter level, BlockPos feet) {
        return MovementHelper.canStandAt(level, feet, false) && !MovementHelper.nearLava(level, feet);
    }

    /**
     * Whether a body walking off a step down is safe where it actually comes down, which is past
     * the landing: a block of drop is five ticks in the air, and a walk covers one block in that.
     * A wall there stops it over the landing.
     */
    private static boolean landsSafelyPast(BlockGetter level, BlockPos beyond) {
        if (MovementHelper.nearLava(level, beyond) || hurts(level, beyond) || hurts(level, beyond.above())) {
            return false;
        }
        return !MovementHelper.hasBodyClearance(level, beyond) || !MovementHelper.fallHurts(level, beyond);
    }

    private static boolean hazardous(BlockGetter level, BlockPos column) {
        if (MovementHelper.nearLava(level, column) || hurts(level, column) || hurts(level, column.above())) {
            return true;
        }
        return MovementHelper.hasBodyClearance(level, column) && MovementHelper.fallHurts(level, column);
    }

    /** Fire, a cactus, a berry bush: blocks {@link MovementHelper#isPassable} calls walls, which hurt to touch. */
    private static boolean hurts(BlockGetter level, BlockPos pos) {
        return MovementHelper.isHarmful(level.getBlockState(pos));
    }
}
