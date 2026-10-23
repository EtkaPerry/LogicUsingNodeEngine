package com.etka.lune.bot.path;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * The single definition of what a gap jump is, shared by the search that plans one and the executor
 * that presses the keys for it.
 * <p>
 * It lives on its own because the two disagreed, silently, for as long as gap jumps existed.
 * {@link AStarPathfinder} planned jumps that land a block lower as well as level ones;
 * {@link PathExecutor} refused to press jump on any tick it considered a descent. So half of every
 * planned jump was a route the bot could not walk: it stepped off the take-off block and fell in the
 * hole it had just planned to clear, and the whole capability looked like something the bot could
 * not do. Nothing failed loudly. The route was valid, the keys were wrong, and the two pieces of
 * code that had to match were forty lines apart in different files with no way to compare them.
 * <p>
 * Now there is one. {@link #plannedJumps()} enumerates exactly what the search may emit and
 * {@link #isGap} decides what the executor will act on, so a test can assert the two are the same
 * set - which is the assertion that would have caught the original bug on the day it was written.
 */
public final class GapJumpPolicy {

    /**
     * Blocks of empty gap the search will plan to jump across on a route that may not sprint, and
     * the most any jump may also drop or climb across.
     *
     * <p>Two: a jump from the lip at a walk clears it, so a route planned with it is a route the
     * walker can take whatever its pace.
     */
    public static final int MAX_GAP = 2;

    /**
     * Blocks of empty gap a route that may sprint plans to jump across: three, the four blocks wide
     * a player clears on a run (the user, 2026-10-18: "people jump 4 blocks wide sometimes"). Level
     * landings only, and only taken at a sprint from the lip, which {@link #reaches} waits for: a
     * running jump from there comes down about three and a half blocks out.
     */
    public static final int MAX_SPRINT_GAP = 3;

    /** How far below the take-off a landing may be. Level, or one down. */
    public static final int MAX_DROP = 1;

    /**
     * How far above the take-off a landing may be: one block, across a one-block gap, the way a
     * player hops up onto a ledge from a standstill looking at the spot (the user, 2026-10-18). A
     * jump rises a block and a quarter, and is above the landing's top from its third tick to its
     * eighth, so the body has to reach the landing in that window - a walking jump does cleanly, and
     * a sprinting one meets the landing's face below its top first ({@link #walkJump}).
     */
    public static final int MAX_RISE = 1;

    /**
     * Ground speed, in blocks per tick, at which a body arriving on the take-off block is running
     * already, and goes on to its jump without first walking back for room ({@link #RUN_UP_ROOM}).
     * <p>
     * The speed is {@code getDeltaMovement()}, which the ground's friction has already taken nearly
     * half of by the time a tick reads it: a sprint reads 0.153 and is running, a walk reads 0.118
     * and is not. When to leave the ground is {@link #reaches}'s, from where the jump would come
     * down; this used to be that too, and jumped every run the moment it was fast enough, wherever
     * that was - which came up short of a long gap from the back of the block and overshot a short
     * one.
     */
    public static final double MIN_TAKEOFF_SPEED = 0.12;

    private GapJumpPolicy() {}

    /**
     * Whether the offset to the next node describes a hole that only a jump can cross.
     *
     * <p>Deliberately strict, and exactly the shapes the search plans ({@link #plannedJumps}).
     * Cardinal only, because diagonal jumps catch on corners. Up to {@link #MAX_GAP} blocks of gap
     * landing level or {@link #MAX_DROP} lower; {@link #MAX_SPRINT_GAP} landing level; and
     * {@link #MAX_RISE} up across a single block. And there has to be a real hole - if the ground
     * between is solid this is ordinary walking, however far the node happens to be.
     *
     * @param dx                   horizontal offset to the next node
     * @param dy                   vertical offset
     * @param dz                   horizontal offset to the next node
     * @param solidFloorUnderStep  given a step number from 1 up to the distance, whether there is a
     *                             floor under that intermediate block
     */
    public static boolean isGap(int dx, int dy, int dz, IntPredicate solidFloorUnderStep) {
        int distance = Math.abs(dx) + Math.abs(dz);
        if (distance <= 1 || distance > MAX_SPRINT_GAP + 1) {
            return false;
        }
        if (dx != 0 && dz != 0) {
            return false;
        }
        boolean shape = dy == 0
                || dy < 0 && dy >= -MAX_DROP && distance <= MAX_GAP + 1
                || dy > 0 && dy <= MAX_RISE && distance == 2;
        if (!shape) {
            return false;
        }
        for (int step = 1; step < distance; step++) {
            if (solidFloorUnderStep.test(step)) {
                return false;
            }
        }
        return true;
    }

    /**
     * How far the head may point off the line to the landing at take-off, in degrees.
     *
     * <p>Vanilla adds a sprint jump's boost along the way the head faces, not the way the body is
     * going. Taken with the head off the line, the boost carries the body off it. On the lava maze's
     * bastion (2026-10-17) a route turned at the edge of a two-block leap and jumped on the tick it
     * arrived, the head still seventy degrees round from the walk up to it: the bot came down a block
     * to the side of the landing, and short of it, in lava. Ten degrees leaves the boost a sideways
     * share of a sixth, which over a whole leap is a quarter of a block.</p>
     */
    public static final double MAX_TAKEOFF_TURN = 10.0;

    /**
     * Half a player's width: how far the middle of the body may hang past an edge and still stand
     * on the block behind it. A jump has to be pressed before that, because vanilla jumps only from
     * the ground.
     */
    public static final double HALF_WIDTH = 0.3;

    /**
     * The most one tick on the ground adds to a run: a sprinter's push, the attribute's 0.1 times
     * sprint's 1.3 times the 0.98 a key press is worth, with room to spare.
     */
    public static final double RUN_PUSH = 0.15;

    /**
     * How far back from the edge a run-up has to start, in blocks: a little short of the middle of
     * the take-off block, where the hold puts the body.
     *
     * <p>From there a standing start reaches a jump with sprint on and the feet still on the block,
     * even counting the tick after a Shift hold, when vanilla still has the body crouched - a third
     * of the speed and no sprint (LocalPlayer reads crouching off the last tick's keys). From
     * nearer the edge it may not: on the bastion (2026-10-18) the hold let the walk up to a turn
     * carry the body to the lip, the run from there reached jumping speed on the tick its feet went
     * over, and the bot stepped into the hole.</p>
     */
    public static final double RUN_UP_ROOM = 0.45;

    /**
     * How far the middle of the body is from the far edge of the take-off block, along the jump;
     * negative once it hangs over. The take-off block is {@code (blockX, blockZ)} and the jump goes
     * one block a step along {@code (stepX, stepZ)}, which is cardinal (see {@link #isGap}).
     */
    public static double toEdge(int blockX, int blockZ, int stepX, int stepZ, double x, double z) {
        if (stepX != 0) {
            return (blockX + 0.5 + 0.5 * stepX - x) * stepX;
        }
        return (blockZ + 0.5 + 0.5 * stepZ - z) * stepZ;
    }

    /**
     * How far the middle of the body may be off the jump's centre line, across it, for a run to
     * begin: a fifth of a block, so that with half a width either side the body stays inside the
     * column the jump flies along.
     *
     * <p>The search plans a jump down the middle of a column and knows nothing of what stands beside
     * it. A route that came onto the take-off block on a diagonal left the body four tenths of a
     * block to one side, and on the bastion (2026-10-18) its shoulder caught the wall standing
     * beside the gap on the tick it jumped: the speed went from half a block a tick to a sixteenth,
     * and the body came down in the hole.</p>
     */
    public static final double LANE = 0.2;

    /**
     * How far the middle of the body is off the jump's centre line, across the jump, either side:
     * the line through the middle of the take-off block {@code (blockX, blockZ)} along
     * {@code (stepX, stepZ)}.
     */
    public static double offLane(int blockX, int blockZ, int stepX, double x, double z) {
        return stepX != 0 ? Math.abs(z - (blockZ + 0.5)) : Math.abs(x - (blockX + 0.5));
    }

    /**
     * How far off the lane the head's line brings the body down: the line from the middle of the body
     * the way the head faces, read across the jump at the far edge of the landing block, from the
     * landing's centre line - which is the lane's. Infinity when the head does not face along the
     * jump at all.
     *
     * <p>A sprint jump goes the way the head faces, so this is where the body comes down, give or take
     * a little steering in the air. It is read at the far edge because a run lands past the middle;
     * and the body starts in the lane, so a line inside it at both ends is inside it the whole way.
     * The lane at the take-off was not enough on its own: on the bastion (2026-10-18) a sprint jump
     * left from the lane with the head six and a half degrees off straight - inside
     * {@link #MAX_TAKEOFF_TURN} - drifted three tenths of a block over the three-block flight, and
     * the wall standing beside the landing took its shoulder just short of it.</p>
     *
     * @param landingX the middle of the landing block
     * @param landingZ the middle of the landing block
     */
    public static double landingOffLane(float yaw, int stepX, int stepZ, double x, double z,
                                        double landingX, double landingZ) {
        double radians = Math.toRadians(yaw);
        double faceX = -Math.sin(radians);
        double faceZ = Math.cos(radians);
        double forward = faceX * stepX + faceZ * stepZ;
        if (forward < 1.0E-6) {
            return Double.POSITIVE_INFINITY;
        }
        double scale = ((landingX - x) * stepX + (landingZ - z) * stepZ + 0.5) / forward;
        return stepX != 0
                ? Math.abs(z + faceZ * scale - landingZ)
                : Math.abs(x + faceX * scale - landingX);
    }

    /** Whether the head's line brings the body down inside the lane; see {@link #landingOffLane}. */
    public static boolean linesUp(float yaw, int stepX, int stepZ, double x, double z,
                                  double landingX, double landingZ) {
        return landingOffLane(yaw, stepX, stepZ, x, z, landingX, landingZ) <= LANE;
    }

    /**
     * Whether a hold on the take-off block presses Shift: only when the way the body still has on it
     * toward the edge would carry it to within {@link #SHIFT_MARGIN} of going over, which is what
     * vanilla's edge guard is for. Ordinary ground keeps a little over half a body's speed each tick,
     * so one let go of slides on about twice its speed ({@link #slide}); one standing still stays
     * where it is with no key down, and Shift held over it does nothing but make the next tick a
     * crouch, a third of the speed, for the run that follows. The user, watching the lava maze
     * (2026-10-18), saw a bot that crouched before every jump.
     */
    public static boolean holdsWithShift(double toEdge, double speedAlong) {
        return toEdge - slide(speedAlong) < SHIFT_MARGIN - HALF_WIDTH;
    }

    /** How far a body going at {@code speed} slides on ordinary ground once nothing is pressed. */
    static double slide(double speed) {
        return Math.max(0.0, speed) / (1.0 - GROUND_KEPT);
    }

    /** How near going over the edge a slide may end before a hold presses Shift, in blocks. */
    static final double SHIFT_MARGIN = 0.1;
    /** A body slower than this, in blocks a tick, is standing still: it slides nowhere worth a Shift. */
    public static final double STILL = 0.02;

    /**
     * Whether this is the last tick the feet can still be on the take-off block, given how far the
     * body is from its edge and how fast it is going at it. A jump not pressed now is a step into
     * the hole, so it is pressed whatever the speed: a jump from the lip lands further than no jump.
     */
    public static boolean atTheLip(double toEdge, double speedAlong) {
        return toEdge - Math.max(0.0, speedAlong) - RUN_PUSH <= -HALF_WIDTH;
    }

    // Vanilla's numbers for a jump, read out of LivingEntity.jumpFromGround and travel and
    // Player.getFlyingSpeed: the push of a held key on the ground and in the air, sprinting or not
    // (the attribute times sprint's 1.3, times the 0.98 a key press is worth), what is kept of the
    // speed after a tick on the ground and in the air, the jump, sprint's kick along the head, and
    // gravity.
    private static final double SPRINT_GROUND_PUSH = 0.13 * 0.98;
    private static final double WALK_GROUND_PUSH = 0.1 * 0.98;
    private static final double SPRINT_AIR_PUSH = 0.026 * 0.98;
    private static final double WALK_AIR_PUSH = 0.02 * 0.98;
    private static final double GROUND_KEPT = 0.6 * 0.91;
    private static final double AIR_KEPT = 0.91;
    private static final double JUMP_POWER = 0.42;
    private static final double SPRINT_KICK = 0.2;
    private static final double GRAVITY = 0.08;
    private static final double AIR_DRAG = 0.98;

    /**
     * How far along the jump the middle of the body goes from the take-off tick until the feet come
     * back down to {@code rise} blocks above the take-off - one up, level, or one down - leaving with
     * {@code speed} along the jump ({@code getDeltaMovement()}, as a tick reads it), forward held and
     * the head along the line.
     *
     * <p>A running jump comes down three and a half blocks out, a walking one two, one from a
     * standstill a block and a half; a jump to a block lower goes half a block further and one to a
     * block higher two fifths less. {@code GapJumpPolicyTest} holds these numbers to a step-by-step
     * model of vanilla written apart from this one.</p>
     */
    public static double flight(double speed, boolean sprinting, int rise) {
        double along = 0.0;
        double height = 0.0;
        double forward = Math.max(0.0, speed) + (sprinting ? SPRINT_KICK + SPRINT_GROUND_PUSH : WALK_GROUND_PUSH);
        double up = JUMP_POWER;
        for (int tick = 0; tick < 60; tick++) {
            along += forward;
            height += up;
            if (up < 0.0 && height <= rise) {
                return along;
            }
            forward = forward * (tick == 0 ? GROUND_KEPT : AIR_KEPT) + (sprinting ? SPRINT_AIR_PUSH : WALK_AIR_PUSH);
            up = (up - GRAVITY) * AIR_DRAG;
        }
        return along;
    }

    /**
     * Whether a jump now brings the middle of the body down where it should land: the middle of the
     * landing block, {@code gap} blocks of hole past the edge - or, onto a block higher, a quarter in,
     * because a jump up comes down short and the landing is reached the moment the body is over it.
     *
     * <p>This is when to leave the ground. A run nearer the edge and faster goes further, so the
     * first tick this holds is the take-off that lands nearest the middle: earlier comes up short,
     * later overshoots - onto a hole, if the landing is one block of bridge. A run that never gets
     * there jumps from the lip anyway ({@link #atTheLip}).</p>
     *
     * @param toEdge     see {@link #toEdge}
     * @param speedAlong ground speed along the jump
     * @param sprinting  whether the body is sprinting now, which is what decides the kick
     */
    public static boolean reaches(double toEdge, double speedAlong, boolean sprinting, int rise, int gap) {
        double landing = -toEdge + flight(speedAlong, sprinting, rise);
        return landing >= gap + (rise > 0 ? 0.25 : 0.5);
    }

    /**
     * Whether a jump is taken at a walk rather than a run: every jump up a block, because a sprinting
     * one meets the landing's face below its top and gets onto it only by scrambling, still rising
     * as the feet clear it, where a walking one never touches it; and a jump a run would carry past
     * the floor beyond the landing, when a walk from the lip still clears the gap. A run goes from
     * the middle of the take-off block at the latest ({@link #RUN_UP_ROOM}, where the hold puts it),
     * and from there a running jump over one block comes down two blocks past it, and one over two
     * blocks and down one comes down near four past - into whatever is there: on the bastion
     * (2026-10-18) a run came down on the far edge of a one-block landing that way and slid into
     * the hole behind it. Every other jump is run at.
     *
     * @param floorBeyond how many blocks of floor follow the landing along the jump, at its level
     */
    public static boolean walkJump(int rise, int gap, int floorBeyond) {
        if (rise > 0) {
            return true;
        }
        double lastFoothold = gap + 1 + floorBeyond - HALF_WIDTH;
        boolean runOvershoots = flight(RUN_SPEED, true, rise) - RUN_UP_ROOM > lastFoothold;
        boolean walkClears = flight(WALK_SPEED, false, rise) >= gap + HALF_WIDTH;
        return runOvershoots && walkClears;
    }

    /** What a tick reads of a body's speed at a run and at a walk on ordinary ground, after friction. */
    private static final double RUN_SPEED = 0.153;
    private static final double WALK_SPEED = 0.118;

    /**
     * Whether the body may run at the jump this tick, rather than hold on the take-off block.
     *
     * <p>The head has to be on the line first. Then a run that has begun carries on to its jump.
     * A run begins only from the lane ({@link #LANE}) with the head's line coming down in it too
     * ({@link #linesUp}), however fast the body arrives: off the lane, the body goes back to the
     * middle of the block first, and a head a few degrees out waits the tick or two it takes to come
     * round. So does a body standing nearer the edge than {@link #RUN_UP_ROOM} - stopped by the
     * hold, or by a route that let go - unless it arrives fast enough to go at once. A run begun on
     * the lane stays on it, because it is steered at the middle of the landing.</p>
     *
     * <p>A jump to be walked ({@link #walkJump}) is not run at by a body that is sprinting: the hold
     * lets go of forward, which is the only thing that ends a sprint in vanilla, and the run-up
     * starts again at a walk.</p>
     *
     * @param linesUp   see {@link #linesUp}
     * @param paced     false for a body sprinting at a jump that is to be walked
     * @param runningUp whether the run-up for this jump has already begun
     * @param offLane   see {@link #offLane}
     */
    public static boolean readyToRun(boolean facing, boolean linesUp, boolean paced, boolean runningUp,
                                     double toEdge, double offLane, double speedAlong) {
        return facing && (runningUp || linesUp && paced && offLane <= LANE
                && (toEdge >= RUN_UP_ROOM || speedAlong >= MIN_TAKEOFF_SPEED));
    }

    /**
     * Whether to leave the ground this tick: a run whose jump now would come down on the landing
     * ({@link #reaches}) with the head's line in the lane, or a run at the lip however it stands,
     * because the next tick is a step into the hole and a jump lands further than no jump.
     *
     * @param gap                the answer from {@link #isGap}
     * @param ready              the answer from {@link #readyToRun}, which asks the head first; see
     *                           {@link #facesTheJump}
     * @param linesUp            see {@link #linesUp}; a run whose line has drifted runs on while the
     *                           head comes back
     * @param reaches            see {@link #reaches}
     * @param speedTowardLanding ground speed along the line to the landing; see {@link #speedToward}.
     *                           Speed across it is no run-up, however fast: it is the walk up to a turn
     * @param toEdge             see {@link #toEdge}; at the lip a run jumps whatever its speed
     */
    public static boolean shouldTakeOff(boolean allowJump, boolean onGround, boolean headroom,
                                        boolean gap, boolean ready, boolean linesUp, boolean reaches,
                                        double speedTowardLanding, double toEdge) {
        return allowJump && onGround && headroom && gap && ready
                && (linesUp && reaches || atTheLip(toEdge, speedTowardLanding));
    }

    /**
     * Whether a head at {@code yaw} faces along {@code (towardX, towardZ)} - from the body to the
     * landing - to within {@link #MAX_TAKEOFF_TURN}. Yaw is Minecraft's: 0 faces +Z, and the face
     * is {@code (-sin yaw, cos yaw)}.
     */
    public static boolean facesTheJump(float yaw, double towardX, double towardZ) {
        double length = Math.hypot(towardX, towardZ);
        if (length < 1.0E-6) {
            return false;
        }
        double radians = Math.toRadians(yaw);
        double along = (-Math.sin(radians) * towardX + Math.cos(radians) * towardZ) / length;
        return along >= Math.cos(Math.toRadians(MAX_TAKEOFF_TURN));
    }

    /** Ground speed along {@code (towardX, towardZ)}; backwards is negative. */
    public static double speedToward(double velocityX, double velocityZ, double towardX, double towardZ) {
        double length = Math.hypot(towardX, towardZ);
        return length < 1.0E-6 ? 0.0 : (velocityX * towardX + velocityZ * towardZ) / length;
    }

    /**
     * Every jump shape {@link AStarPathfinder} is able to plan, as {dx, dy, dz} offsets.
     * <p>
     * This mirrors the search's own enumeration rather than describing it loosely, so that a test
     * comparing it against {@link #isGap} is comparing two real implementations and not one
     * implementation against a restatement of itself.
     */
    public static List<int[]> plannedJumps() {
        List<int[]> jumps = new ArrayList<>();
        for (int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int distance = 2; distance <= MAX_SPRINT_GAP + 1; distance++) {
                int lowest = distance <= MAX_GAP + 1 ? -MAX_DROP : 0;
                int highest = distance == 2 ? MAX_RISE : 0;
                for (int dy = highest; dy >= lowest; dy--) {
                    jumps.add(new int[] {direction[0] * distance, dy, direction[1] * distance});
                }
            }
        }
        return jumps;
    }
}
