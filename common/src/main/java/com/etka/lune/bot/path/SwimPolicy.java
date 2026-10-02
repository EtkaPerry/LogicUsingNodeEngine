package com.etka.lune.bot.path;

/**
 * What a player does with Shift, Space and their eyes in water.
 *
 * <p>Vanilla gives someone in water three controls, and the bot was only using one of them. Space
 * rises. Shift sinks: {@code LocalPlayer.aiStep} calls {@code goDownInWater} on every tick it is
 * held. The look steers depth too, but only for a swimmer - {@code Player.travel} pulls the
 * vertical speed toward the look while the swimming pose is on and ignores the pitch otherwise.
 * That pose is the run key, and it only starts while the eyes are under: at the surface vanilla
 * will not begin a sprint at all, and cancels a running one for anybody not already swimming.</p>
 *
 * <p>So a crossing begins with Shift, and nothing else can begin it. The route across a lake runs
 * along the top layer of the water, where every waypoint is somewhere to breathe, and the bot used
 * to tip its head down at that layer and hold the run key. An upright paddler ignores both. It sank
 * a little, the next waypoint was then above its feet, it pressed Space to climb back to it, and it
 * bobbed the whole way across upright. Measured on an ocean crossing (2026-09-30): 0.1 blocks a
 * tick, the run key held throughout, the head out of the water for 98% of it and Shift never
 * pressed once. A swimmer goes twice that fast for the same hunger - vanilla charges swimming the
 * same exhaustion per metre as paddling.</p>
 *
 * <p>The rules, in the order {@link #decide} asks them:</p>
 * <ol>
 *   <li>Leaving the water is climbing out: Space when the bank is higher, and nothing else.</li>
 *   <li>A breath is taken at the surface, and taken fully. Head up once half the air is gone, and
 *   stay up until the bar is full - see {@link #breathing}.</li>
 *   <li>Deep water is swum when the stroke is available: Shift until the eyes are under, then
 *   neither key, with the look a block under the surface layer the route runs along.</li>
 *   <li>Anything else is paddled, the way a player without the run key crosses: Shift to go down to
 *   a lower waypoint and Space to come up to a higher one.</li>
 * </ol>
 *
 * <p>Pure, like {@link SprintPolicy}: the executor reads the situation off the player and the
 * route, and everything the bot then does in water is decided here, where a test can ask.</p>
 */
public final class SwimPolicy {

    /**
     * Share of a full air bar left when a swimmer heads up for a breath.
     *
     * <p>Half, so the way up never meets {@link WaterEscape}, which takes over at two fifths. That
     * is the emergency, and an ordinary crossing should not need one.</p>
     */
    public static final double COME_UP_AT = 0.5;

    /**
     * Everything about this tick in water that the decision reads.
     *
     * @param swimming     vanilla's swimming pose is on - the crawl stroke
     * @param eyesUnder    the eyes are in the water, so the air is going down
     * @param air          air left, in vanilla's ticks
     * @param maxAir       a full bar
     * @param canStroke    the run key is allowed and vanilla would honour it - enough food, not
     *                     blind, not riding - which is what the stroke needs
     * @param deep         both the water the bot is in and the waypoint ahead are deep enough to
     *                     swim in ({@link MovementHelper#isDeepWater}), not a wade
     * @param targetIsWater the waypoint ahead is still in the water
     * @param climbing     the waypoint ahead is higher than the feet
     * @param descending   the waypoint ahead is lower than the feet
     * @param onClimbable  holding a vine or ladder, where Shift means "hold on", not "go down"
     */
    public record Water(boolean swimming, boolean eyesUnder, int air, int maxAir,
                        boolean canStroke, boolean deep, boolean targetIsWater,
                        boolean climbing, boolean descending, boolean onClimbable) {}

    /** Where the eyes go, which is where a swimmer goes. */
    public enum Aim {
        /** At the waypoint, as on land. */
        NODE,
        /**
         * A block under the waypoint when it is on the surface layer with water below it, and at
         * the waypoint otherwise. Under the surface the eyes stay under, so the stroke goes on;
         * at it, the head breaks out and the run key stops counting.
         */
        LANE,
        /** Up at the air above the water, toward the waypoint. */
        SURFACE
    }

    /** Which rule decided; for the debug overlay, the journal and the tests. */
    public enum Kind { CLIMB_OUT, BREATHE, DIVE, SWIM, PADDLE }

    /** The keys and the look for one tick. */
    public record Stroke(Kind kind, boolean sneak, boolean jump, Aim aim) {}

    private SwimPolicy() {}

    public static Stroke decide(Water water) {
        // The route leaves the water here. Space onto a higher bank; a level beach is just walked
        // up, and pressing anything else would only fight the look that is already on it.
        if (!water.targetIsWater()) {
            return new Stroke(Kind.CLIMB_OUT, false, water.climbing(), Aim.NODE);
        }
        if (breathing(water.eyesUnder(), water.air(), water.maxAir())) {
            // Space while the eyes are still under: a swimmer looking up rises, but stops rising
            // once the top of its head clears the water, which can leave the eyes a hand's width
            // under it with the air still going. Once they are out, a swimmer holds its height
            // with the look alone; an upright paddler sinks, and climbs back as it always did.
            boolean jump = water.eyesUnder() || (!water.swimming() && water.climbing());
            return new Stroke(Kind.BREATHE, false, jump, Aim.SURFACE);
        }
        if (water.canStroke() && water.deep()) {
            // Shift until the eyes are under. The run key is already held, so vanilla starts the
            // stroke on the tick they go, and from then on the look does the steering - Space
            // here would undo it, which is the bobbing this replaced.
            boolean sneak = !water.swimming() && !water.eyesUnder() && !water.onClimbable();
            return new Stroke(sneak ? Kind.DIVE : Kind.SWIM, sneak, false, Aim.LANE);
        }
        // No stroke to be had: a wade, or no run key. Shift down to a lower waypoint and Space up
        // to a higher one, which is all an upright paddler can do with the depth.
        boolean sneak = water.descending() && !water.onClimbable();
        return new Stroke(Kind.PADDLE, sneak, !sneak && water.climbing(), Aim.NODE);
    }

    /**
     * Whether this tick belongs to a breath rather than to the route.
     *
     * <p>Under water it starts at {@link #COME_UP_AT}; out of it, it lasts until the bar is full.
     * The half that keeps a swimmer at the surface is what makes it a breath: without it the bot
     * dives again the tick it has one bubble back, and spends the crossing bobbing at the
     * threshold. It is read off the air bar and the eyes rather than remembered, because the route
     * that would remember it is rebuilt at every replan, and the bar is the one thing that is still
     * there afterwards.</p>
     */
    public static boolean breathing(boolean eyesUnder, int air, int maxAir) {
        if (maxAir <= 0) {
            return false;
        }
        return eyesUnder ? air <= maxAir * COME_UP_AT : air < maxAir;
    }
}
