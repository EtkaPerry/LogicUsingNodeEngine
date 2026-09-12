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
     * Blocks of empty gap the search will plan to jump across.
     *
     * <p>Two. A running jump clears more, but the search cannot know whether there will be room to
     * build up speed, and the cost of being wrong is a fall rather than a slower route.
     */
    public static final int MAX_GAP = 2;

    /** How far below the take-off a landing may be. Level, or one down. */
    public static final int MAX_DROP = 1;

    /**
     * Horizontal speed, in blocks per tick, a jump waits for before leaving the ground.
     * <p>
     * A standing jump does not clear two blocks; a sprinting one does. Pressing sprint on the
     * take-off tick is not the same as arriving with speed - it is the run-up that carries you - so
     * the take-off waits a tick or two for the run rather than committing to a hop that was always
     * going to come up short.
     * <p>
     * Below a walk (0.215) so arriving on foot is always enough, and above a standing start so a
     * route that has just replanned spends those ticks building speed instead of stepping into the
     * hole. It also settles a separate problem for free: a bot that has stalled or drifted off its
     * route is "far from the next node" too, and used to hop on the spot forever. A bot hopping on
     * the spot has no speed, so it no longer qualifies.
     */
    public static final double MIN_TAKEOFF_SPEED = 0.12;

    private GapJumpPolicy() {}

    /**
     * Whether the offset to the next node describes a hole that only a jump can cross.
     *
     * <p>Deliberately strict. Cardinal only, because diagonal jumps catch on corners; never upward,
     * because a jump that also has to gain height is a step-up and has its own move; and there has
     * to be a real hole - if the ground between is solid this is ordinary walking, however far the
     * node happens to be.
     *
     * @param dx                   horizontal offset to the next node
     * @param dy                   vertical offset; zero or negative for a jump
     * @param dz                   horizontal offset to the next node
     * @param solidFloorUnderStep  given a step number from 1 up to the distance, whether there is a
     *                             floor under that intermediate block
     */
    public static boolean isGap(int dx, int dy, int dz, IntPredicate solidFloorUnderStep) {
        int distance = Math.abs(dx) + Math.abs(dz);
        if (distance <= 1 || distance > MAX_GAP + 1) {
            return false;
        }
        if (dy > 0 || dy < -MAX_DROP) {
            return false;
        }
        if (dx != 0 && dz != 0) {
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
     * Whether to leave the ground this tick.
     *
     * @param gap             the answer from {@link #isGap}
     * @param horizontalSpeed ground speed now; the vertical component says nothing about a gap
     */
    public static boolean shouldTakeOff(boolean allowJump, boolean onGround, boolean headroom,
                                        boolean gap, double horizontalSpeed) {
        return allowJump && onGround && headroom && gap && horizontalSpeed >= MIN_TAKEOFF_SPEED;
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
            for (int distance = 2; distance <= MAX_GAP + 1; distance++) {
                for (int drop = 0; drop <= MAX_DROP; drop++) {
                    jumps.add(new int[] {direction[0] * distance, -drop, direction[1] * distance});
                }
            }
        }
        return jumps;
    }
}
