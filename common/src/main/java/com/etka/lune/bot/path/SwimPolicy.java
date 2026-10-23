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
 * <p>So a crossing begins with Shift, and nothing else can begin it. What happens after that was
 * wrong twice. The first bot tipped its head at the water and held the run key, which an upright
 * paddler ignores, and bobbed across an ocean at 0.1 blocks a tick with its head out the whole way.
 * The second dived, swam a block under the surface and came up for a breath at half a bar, which
 * is twice as fast and spent a fifth of every crossing surfacing and diving again - because it read
 * the stroke as something that needed the eyes under. It does not. Vanilla keeps the stroke for as
 * long as the <em>body</em> touches water and forward is held ({@code shouldStopSwimSprinting},
 * {@code Entity.updateSwimming}), and the air comes back on every tick the <em>eyes</em> are out
 * ({@code LivingEntity.baseTick}). A swimmer's eyes are 0.4 above its feet and the top of a water
 * block is 0.889 above its floor, so a swimmer whose feet sit between 0.0 and 0.4 under the top of
 * the water strokes at full speed and breathes at once, which is what a player does: they duck
 * under to start the stroke, come up until their eyes clear the water, and stay there. The user
 * showed exactly that from inside the game (2026-10-10).</p>
 *
 * <p>The rules, in the order {@link #decide} asks them:</p>
 * <ol>
 *   <li>Leaving the water is climbing out: Space when the bank is higher, and nothing else.</li>
 *   <li>A swimmer with air at the top of its water holds its feet {@link #SURFACE_DEPTH} under it,
 *   with Space and Shift, and looks level so the look steers nothing. That is the breath: a swimmer
 *   never comes up for one, and never needs {@link WaterEscape}.</li>
 *   <li>Anyone else with their eyes under heads up at {@link #COME_UP_AT} and stays up until a dive
 *   is affordable again - see {@link #breathing}.</li>
 *   <li>Deep water is dived into when the stroke is available: Shift until the eyes are under, then
 *   nothing, and the hold takes over on the tick the stroke latches. A swimmer under a roof, with
 *   no air at the top of its water, keeps the stroke and follows the route's own depth.</li>
 *   <li>Anything else is paddled, the way a player without the run key crosses: Shift to go down to
 *   a lower waypoint and Space to come up to a higher one.</li>
 * </ol>
 *
 * <p>Pure, like {@link SprintPolicy}: the executor reads the situation off the player and the
 * route, and everything the bot then does in water is decided here, where a test can ask.
 * {@code SwimPolicyTest} swims the hold through a model of vanilla's own vertical physics.</p>
 */
public final class SwimPolicy {

    /**
     * Share of a full air bar left when someone with their eyes under heads up for a breath.
     *
     * <p>Half, so the way up never meets {@link WaterEscape}, which takes over at two fifths. That
     * is the emergency, and an ordinary crossing should not need one.</p>
     */
    public static final double COME_UP_AT = 0.5;

    /**
     * Air a dive spends, in ticks: Shift down until the eyes are under, the stroke latching, the
     * rise back to the eye line. About twenty-five in the model; two seconds leaves room. An
     * upright paddler with less than this over {@link #COME_UP_AT} waits at the surface rather than
     * diving, so a dive can never end in a breath it has to come up for.
     */
    public static final int DIVE_AIR = 40;

    /**
     * How far under the top of the water a swimmer keeps its feet, in blocks.
     *
     * <p>The body is in the water while the feet are at least a hair under the top of it, and the
     * eyes are out while the feet are less than 0.489 under it - the 0.889 of a water block less
     * the swimmer's 0.4. A fifth of a block sits in the middle of that window, with
     * {@link #DEPTH_BAND} either side of it still inside.</p>
     */
    public static final double SURFACE_DEPTH = 0.2;

    /** How far either side of {@link #SURFACE_DEPTH} the feet may drift before a key is pressed. */
    public static final double DEPTH_BAND = 0.1;

    /**
     * Blocks a swimmer still travels, for each block a tick of vertical speed it has, once the
     * keys are let go.
     *
     * <p>Vanilla keeps 0.8 of a swimmer's vertical speed after every tick in water, and a level
     * look takes six per cent off it first, so a coasting swimmer covers 0.94 &times; (1 + 0.752 +
     * 0.752&sup2; + ...) &asymp; 3.8 times its speed before it stops; looking a little up, where
     * that pull is off, five times. Four sits between, and {@link #DEPTH_BAND} absorbs the
     * difference. Without this the hold let go of Space at the depth it wanted and coasted half a
     * block past it, out of the water, which ends the stroke.</p>
     */
    public static final double COAST = 4.0;

    /**
     * Everything about this tick in water that the decision reads.
     *
     * @param swimming      vanilla's swimming pose is on - the crawl stroke
     * @param eyesUnder     the eyes are in the water, so the air is going down
     * @param air           air left, in vanilla's ticks
     * @param maxAir        a full bar
     * @param canStroke     the run key is allowed and vanilla would honour it - enough food, not
     *                      blind, not riding - which is what the stroke needs
     * @param deep          both the water the bot is in and the waypoint ahead are deep enough to
     *                      swim in ({@link MovementHelper#isDeepWater}), not a wade
     * @param airAbove      the top of the water the feet are in is open to the air, so a breath
     *                      can be had there; false under ice or a roof
     * @param depth         how far under the top of that water the feet are, in blocks
     * @param verticalSpeed the body's vertical speed this tick, up positive, in blocks a tick
     * @param targetIsWater the waypoint ahead is still in the water
     * @param climbing      the waypoint ahead is higher than the feet
     * @param descending    the waypoint ahead is lower than the feet
     * @param onClimbable   holding a vine or ladder, where Shift means "hold on", not "go down"
     */
    public record Water(boolean swimming, boolean eyesUnder, int air, int maxAir,
                        boolean canStroke, boolean deep, boolean airAbove,
                        double depth, double verticalSpeed, boolean targetIsWater,
                        boolean climbing, boolean descending, boolean onClimbable) {}

    /** Where the eyes go, which for a swimmer is also where the body goes. */
    public enum Aim {
        /** At the waypoint, as on land. */
        NODE,
        /**
         * Along the route at the eyes' own height. A level look steers a swimmer's depth toward
         * nothing but still damps its vertical speed, so the keys decide the depth and the look
         * holds it.
         */
        LEVEL,
        /**
         * Along the route at the waypoint's height, for a swimmer with no air at the top of its
         * water: under a roof the route's own depth is the only one worth having.
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
        if (water.swimming() && water.canStroke() && water.airAbove()) {
            // The stroke is on and there is air at the top of this water. Hold the feet just under
            // it: the eyes are out, the air comes back, the body is still in the water and so the
            // stroke goes on. Space when the body would settle too deep, Shift when it would
            // settle too high - out of the water, which ends the stroke - and nothing in between,
            // because a swimmer looking level coasts to a stop on its own. Where it would settle
            // is read from the speed it already has (COAST), not from where it is: pressing until
            // it was there carried it half a block past.
            double settle = water.depth() - water.verticalSpeed() * COAST;
            boolean jump = settle > SURFACE_DEPTH + DEPTH_BAND;
            boolean sneak = !jump && settle < SURFACE_DEPTH - DEPTH_BAND && !water.onClimbable();
            return new Stroke(Kind.SWIM, sneak, jump, Aim.LEVEL);
        }
        if (breathing(water.eyesUnder(), water.air(), water.maxAir())) {
            // Space while the eyes are still under. Once they are out, a swimmer with no top to
            // hold at is still a swimmer; an upright paddler sinks, and climbs back as it always
            // did.
            boolean jump = water.eyesUnder() || (!water.swimming() && water.climbing());
            return new Stroke(Kind.BREATHE, false, jump, Aim.SURFACE);
        }
        if (water.canStroke() && water.deep()) {
            if (water.swimming()) {
                // The stroke is on under a roof. There is no breath at the top of this water, so
                // the route's depth is the one to keep, and the look is what keeps it.
                return new Stroke(Kind.SWIM, false, false, Aim.LANE);
            }
            // Shift until the eyes are under. The run key is already held, so vanilla starts the
            // stroke on the tick they go, and the hold above takes over from there. Nothing is
            // pressed meanwhile: Space would undo the dive, and Shift would only sink it further
            // from the surface it is about to come back to.
            boolean sneak = !water.eyesUnder() && !water.onClimbable();
            return new Stroke(Kind.DIVE, sneak, false, Aim.LEVEL);
        }
        // No stroke to be had: a wade, or no run key. Shift down to a lower waypoint and Space up
        // to a higher one, which is all an upright paddler can do with the depth.
        boolean sneak = water.descending() && !water.onClimbable();
        return new Stroke(Kind.PADDLE, sneak, !sneak && water.climbing(), Aim.NODE);
    }

    /**
     * The same stroke with the keys that keep it through a turn.
     *
     * <p>Vanilla stops a swimmer's sprint on the tick forward is let go - at a sharp corner the
     * steering strafes for a few ticks while the head comes round - unless Shift is down
     * ({@code LocalPlayer.shouldStopSwimSprinting}). Losing the sprint is losing the pose, which
     * stands the body up with its eyes a block and a half out of the water, and the only way back
     * is the dive, twenty-five ticks of it. So a swimmer that is not pressing forward holds Shift,
     * and Space beside it so the two cancel and the depth stays - except while it was already
     * braking with Shift alone, which still brakes. Only a swimmer's: an upright paddler has no
     * stroke to keep, and on a vine Shift is "hold on".</p>
     */
    public static Stroke throughTurn(Stroke stroke, Water water, boolean pressingForward) {
        if (pressingForward || !water.swimming() || water.onClimbable()
                || stroke.kind() != Kind.SWIM) {
            return stroke;
        }
        boolean braking = stroke.sneak() && !stroke.jump();
        return new Stroke(stroke.kind(), true, !braking, stroke.aim());
    }

    /**
     * Whether this tick belongs to a breath rather than to the route, for someone who is not
     * holding the surface with the stroke.
     *
     * <p>Under water it starts at {@link #COME_UP_AT}; out of it, it lasts until the bar holds
     * {@link #DIVE_AIR} more than that, so the dive it allows cannot bring the bar back under the
     * line and turn into another breath. It is read off the air bar and the eyes rather than
     * remembered, because the route that would remember it is rebuilt at every replan, and the bar
     * is the one thing that is still there afterwards.</p>
     */
    public static boolean breathing(boolean eyesUnder, int air, int maxAir) {
        if (maxAir <= 0) {
            return false;
        }
        double line = maxAir * COME_UP_AT;
        return eyesUnder ? air <= line : air < Math.min(maxAir, line + DIVE_AIR);
    }
}
