package com.etka.lune.bot.task;

/**
 * Pure rules for crossing water and ice in a boat; kept testable without Minecraft.
 *
 * <p>A boat is five planks and about three seconds of setup, so it is not free - but on ice it is
 * not a convenience either, it is the fastest travel in the game by a wide margin, and a frozen
 * river is the one obstacle where walking around is almost always the wrong answer. That asymmetry
 * is why the two surfaces get different thresholds: open water has to be genuinely wide before a
 * boat beats swimming around it, while ice is worth boarding almost immediately.</p>
 */
final class BoatPolicy {

    /** Vanilla recipe: five planks in a 3×2, so it needs a crafting table rather than the 2×2. */
    static final int PLANKS_PER_BOAT = 5;
    /** Open water narrower than this is quicker to swim or walk around than to launch into. */
    static final int MIN_WATER_CROSSING = 8;
    /** On ice a boat is roughly an order of magnitude faster than walking, so barely any is enough. */
    static final int MIN_ICE_CROSSING = 3;
    /** Degrees of heading error tolerated before steering; a boat that corrects constantly wobbles. */
    static final double TURN_DEADZONE = 6.0;
    /**
     * How far ahead to look when deciding whether to keep steering, in ticks.
     *
     * <p>Roughly the number of ticks a boat takes to stop rotating after the key is released.
     * Too small and it still overshoots; too large and it stops turning early and crabs toward the
     * target on a permanent slight error.
     */
    static final double TURN_LEAD_TICKS = 4.0;
    /** Distance at which the crossing is over and the bot should get out and walk. */
    static final double ARRIVAL_DISTANCE = 3.0;

    private BoatPolicy() {}

    static int planksNeeded(int carriedPlanks) {
        return Math.max(0, PLANKS_PER_BOAT - Math.max(0, carriedPlanks));
    }

    /** Whether the crossing ahead justifies building and launching a boat. */
    static boolean worthLaunching(int crossingBlocks, boolean onIce, boolean carryingBoat,
                                  int carriedPlanks) {
        if (crossingBlocks < (onIce ? MIN_ICE_CROSSING : MIN_WATER_CROSSING)) {
            return false;
        }
        return carryingBoat || planksNeeded(carriedPlanks) == 0;
    }

    /**
     * Which way to turn, given how far the boat's heading is from where it should be going.
     *
     * @param yawError wrapped degrees, positive when the target is clockwise of the current heading
     * @return {@code 1} to press right, {@code -1} to press left, {@code 0} to hold course
     */
    static int steer(double yawError) {
        return steer(yawError, 0.0);
    }

    /**
     * As above, but allowing for the turn already under way.
     *
     * <p>A boat keeps rotating after the key is released. Steering on the error alone means the
     * rudder is still hard over when the error reaches the deadzone, and the boat sails straight
     * through it: a measured crossing ran 55° of error down to zero at about six degrees a tick,
     * overshot to −26°, corrected, overshot again, and circled. Since the deadzone is the same size
     * as one tick of turn, the error alone can never see it coming.
     *
     * <p>So steer on where the heading will be shortly rather than where it is. Turning quickly
     * toward the target cancels the input early and lets the momentum finish the turn, which is
     * what a person does with the tiller.
     *
     * @param turnRate degrees of yaw change per tick, positive in the same sense as {@code yawError}
     */
    static int steer(double yawError, double turnRate) {
        double predicted = yawError + turnRate * TURN_LEAD_TICKS;
        if (predicted > TURN_DEADZONE) {
            return 1;
        }
        if (predicted < -TURN_DEADZONE) {
            return -1;
        }
        return 0;
    }

    /**
     * Whether to drive forward this tick.
     * <p>
     * A boat that is pointing the wrong way should turn before it accelerates, or it carves a long
     * arc across the water and arrives somewhere else. Vanilla also turns faster when not moving.
     */
    static boolean shouldAccelerate(double yawError) {
        return Math.abs(yawError) < 45.0;
    }

    static boolean arrived(double horizontalDistance) {
        return horizontalDistance <= ARRIVAL_DISTANCE;
    }
}
