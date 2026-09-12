package com.etka.lune.bot.path;

/**
 * When the bot holds the run key.
 * <p>
 * Sprinting is the default, not a reward for ideal conditions. It used to be switched off on any
 * route node that changed height and whenever the head was more than thirty degrees off the
 * direction of travel. On rolling ground almost every node changes height, and the eased look takes
 * most of a second to come round after a corner, so between them the bot walked most of its
 * journeys - which is the largest part of "it does not run like a human".
 * <p>
 * Neither restriction was about the game. Vanilla sprints up and down slopes quite happily, and a
 * sprint-jump is the quickest way up a one-block step rather than something to avoid. Head angle
 * never decided whether sprinting worked either: {@code PathExecutor.steer} presses the correct
 * movement keys whichever way the head happens to point, so the angle described the camera, not the
 * travel.
 * <p>
 * What does decide it is the forward key - vanilla will not sprint sideways or backwards - and
 * having something to push against. Both of those are in {@link Movement}, and the fields that no
 * longer matter are kept there deliberately: they describe the situation rather than the rule, and
 * keeping them lets a test assert that the answer really is independent of them.
 */
public final class SprintPolicy {

    /**
     * Everything about the current tick that could plausibly bear on sprinting.
     *
     * @param allowSprint      the route and the user config both permit it
     * @param pressingForward  steering has asked for forward this tick
     * @param onGround         standing on something
     * @param inWater          body in water, swimming or wading
     * @param waterRoute       this leg of the route is a deliberate crossing
     * @param hangingOnLadder  on a climbable and not on the ground
     * @param climbing         the next node is higher - deliberately not consulted
     * @param descending       the next node is lower - deliberately not consulted
     * @param dropAhead        how many blocks the next node falls; 0 when level or climbing
     * @param headingErrorDegrees how far the head is from the direction of travel - not consulted
     */
    public record Movement(boolean allowSprint, boolean pressingForward, boolean onGround,
                           boolean inWater, boolean waterRoute, boolean hangingOnLadder,
                           boolean climbing, boolean descending, int dropAhead,
                           double headingErrorDegrees) {

        /** Keeps the older nine-argument form working for callers that never see a ledge. */
        public Movement(boolean allowSprint, boolean pressingForward, boolean onGround,
                        boolean inWater, boolean waterRoute, boolean hangingOnLadder,
                        boolean climbing, boolean descending, double headingErrorDegrees) {
            this(allowSprint, pressingForward, onGround, inWater, waterRoute, hangingOnLadder,
                    climbing, descending, 0, headingErrorDegrees);
        }
    }

    /**
     * A drop of this many blocks or more is a ledge rather than a slope, and is walked off.
     *
     * <p>Two, because one is the ordinary step of rolling ground and the thing the old
     * height-change rule got wrong. The pathfinder never plans a drop it cannot survive - it stops
     * at {@code maxFall}, three by default, which costs no health - but that guarantee is about
     * where the bot <em>lands</em>, and sprinting off the edge carries it past that landing onto
     * whatever is further down. A measured travel run fell five times that way, 4 to 6 blocks each,
     * every one with the run key held; and because each overshoot put the bot lower than the route
     * expected, the next search started from there and the whole journey ratcheted down a cave
     * system from y=61 to bedrock.</p>
     */
    public static final int LEDGE_DROP = 2;

    private SprintPolicy() {}

    public static boolean shouldSprint(Movement move) {
        if (!move.allowSprint() || move.hangingOnLadder()) {
            return false;
        }
        if (!move.onGround() && !move.inWater()) {
            // Mid-air. Sprint carries over from the take-off; pressing it now changes nothing.
            return false;
        }
        // Step off a ledge, do not launch off it. This is not the old "any height change" rule that
        // made the bot walk everywhere - a slope is one block and still sprinted. See LEDGE_DROP.
        if (move.onGround() && move.dropAhead() >= LEDGE_DROP) {
            return false;
        }
        // A swimmer keeps the crawl stroke through a turn. Applying the forward-key rule in water
        // drops sprint at the edge of a river exactly while the route is still settling its heading,
        // leaving the bot paddling diagonally against the bank and looking stalled.
        return move.pressingForward() || move.waterRoute();
    }
}
