package com.etka.lune.bot.path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwimPolicyTest {

    private static final int FULL = 300;

    /** A tick in water, with the defaults every crossing starts from; set what differs. */
    private static final class Tick {
        boolean swimming;
        boolean eyesUnder;
        int air = FULL;
        boolean canStroke = true;
        boolean deep = true;
        boolean airAbove = true;
        double depth;
        double verticalSpeed;
        boolean targetIsWater = true;
        boolean climbing;
        boolean descending;
        boolean onClimbable;

        SwimPolicy.Water water() {
            return new SwimPolicy.Water(swimming, eyesUnder, air, FULL, canStroke, deep, airAbove,
                    depth, verticalSpeed, targetIsWater, climbing, descending, onClimbable);
        }

        SwimPolicy.Stroke stroke() {
            return SwimPolicy.decide(water());
        }
    }

    /**
     * Upright at the top of a lake, the route going on across it, full air and the run key allowed:
     * the moment every crossing starts from.
     */
    private static Tick atTheSurface() {
        Tick tick = new Tick();
        tick.depth = 0.3;
        return tick;
    }

    /** The stroke on and the feet where the hold keeps them. */
    private static Tick holding() {
        Tick tick = new Tick();
        tick.swimming = true;
        tick.depth = SwimPolicy.SURFACE_DEPTH;
        return tick;
    }

    /** The stroke on, a block under the top, where the old swimmer used to go on purpose. */
    private static Tick aBlockUnder() {
        Tick tick = holding();
        tick.eyesUnder = true;
        tick.depth = 1.0;
        return tick;
    }

    /**
     * The bug this class exists for. Pitch does nothing to an upright paddler and vanilla will not
     * start the stroke with the head out, so Shift is the only way under - and Space, which the
     * route used to press whenever the bot sank below a surface waypoint, is the opposite of it.
     */
    @Test
    void aCrossingStartsWithShiftNotSpace() {
        SwimPolicy.Stroke stroke = atTheSurface().stroke();

        assertEquals(SwimPolicy.Kind.DIVE, stroke.kind());
        assertTrue(stroke.sneak(), "Shift is what gets the eyes under");
        assertFalse(stroke.jump());
        assertEquals(SwimPolicy.Aim.LEVEL, stroke.aim(), "and the head stays level for the rise");
    }

    /** Eyes under but the stroke not latched yet - turning, so W is up. Shift would only sink it. */
    @Test
    void itStopsDivingOnceTheEyesAreUnder() {
        Tick under = atTheSurface();
        under.eyesUnder = true;
        under.depth = 1.4;

        SwimPolicy.Stroke stroke = under.stroke();

        assertEquals(SwimPolicy.Kind.DIVE, stroke.kind());
        assertFalse(stroke.sneak());
        assertFalse(stroke.jump());
    }

    /**
     * The second bug. The swimmer went a block under the surface to keep its eyes under, believing
     * the stroke needed them there, and spent a fifth of every crossing coming up to breathe.
     * Vanilla keeps the stroke while the body is in the water, and the air comes back while the
     * eyes are out, so a swimmer holds its feet just under the top and has both at once.
     */
    @Test
    void aSwimmerHoldsItsFeetJustUnderTheTopAndPressesNothingThere() {
        SwimPolicy.Stroke stroke = holding().stroke();

        assertEquals(SwimPolicy.Kind.SWIM, stroke.kind());
        assertFalse(stroke.sneak());
        assertFalse(stroke.jump());
        assertEquals(SwimPolicy.Aim.LEVEL, stroke.aim(), "a level look steers the depth nowhere");
    }

    @Test
    void theHoldIsInsideTheWindowWhereTheEyesAreOutAndTheBodyIsIn() {
        double eyesOutAbove = 8.0 / 9.0 - 0.4;
        assertTrue(SwimPolicy.SURFACE_DEPTH + SwimPolicy.DEPTH_BAND < eyesOutAbove,
                "the deep edge of the band still has the eyes out");
        assertTrue(SwimPolicy.SURFACE_DEPTH - SwimPolicy.DEPTH_BAND > 0.0,
                "the shallow edge of the band still has the feet in the water");
    }

    @Test
    void aSwimmerTooDeepComesUpWithSpace() {
        SwimPolicy.Stroke stroke = aBlockUnder().stroke();

        assertEquals(SwimPolicy.Kind.SWIM, stroke.kind());
        assertTrue(stroke.jump());
        assertFalse(stroke.sneak());
    }

    /** Rising fast with the top close: let go now and it still coasts out of the water. */
    @Test
    void aSwimmerAboutToLeaveTheWaterBrakesWithShift() {
        Tick popping = holding();
        popping.depth = 0.25;
        popping.verticalSpeed = 0.1;

        SwimPolicy.Stroke stroke = popping.stroke();

        assertTrue(stroke.sneak(), "Shift is the brake");
        assertFalse(stroke.jump());
    }

    /**
     * The hold reads where the body will settle, not where it is. Half a block under and rising
     * at the speed Space gives, it is already going to coast to the hold - pressing Space until it
     * was there carried it half a block past, out of the water, which ends the stroke.
     */
    @Test
    void theHoldReadsWhereItWillSettleNotWhereItIs() {
        Tick rising = holding();
        rising.eyesUnder = true;
        rising.depth = SwimPolicy.SURFACE_DEPTH + 0.12 * SwimPolicy.COAST;
        rising.verticalSpeed = 0.12;

        SwimPolicy.Stroke stroke = rising.stroke();

        assertFalse(stroke.jump(), "still under, but already on its way to the hold");
        assertFalse(stroke.sneak());
    }

    /** A swimmer never comes up for a breath: the hold is the breath, whatever the bar says. */
    @Test
    void aSwimmerNeverComesUpForABreath() {
        Tick low = aBlockUnder();
        low.air = FULL / 2;

        SwimPolicy.Stroke stroke = low.stroke();

        assertEquals(SwimPolicy.Kind.SWIM, stroke.kind());
        assertTrue(stroke.jump(), "up to the hold, which is where the air is");
        assertEquals(SwimPolicy.Aim.LEVEL, stroke.aim());
    }

    /** The shallows on the way to a bank are still swum at the top, not along the bottom. */
    @Test
    void aSwimmerInTheShallowsStillHoldsTheTop() {
        Tick ford = aBlockUnder();
        ford.deep = false;
        ford.depth = 0.8;

        SwimPolicy.Stroke stroke = ford.stroke();

        assertEquals(SwimPolicy.Kind.SWIM, stroke.kind());
        assertTrue(stroke.jump());
    }

    /** Under ice there is no top to breathe at, so the route's own depth is the one to keep. */
    @Test
    void aSwimmerUnderARoofKeepsTheStrokeAndTheRoutesDepth() {
        Tick roofed = aBlockUnder();
        roofed.airAbove = false;

        SwimPolicy.Stroke stroke = roofed.stroke();

        assertEquals(SwimPolicy.Kind.SWIM, stroke.kind());
        assertFalse(stroke.jump());
        assertFalse(stroke.sneak());
        assertEquals(SwimPolicy.Aim.LANE, stroke.aim());

        roofed.air = FULL / 2;
        SwimPolicy.Stroke breath = roofed.stroke();
        assertEquals(SwimPolicy.Kind.BREATHE, breath.kind(), "and at half a bar it is a breath");
        assertTrue(breath.jump());
        assertEquals(SwimPolicy.Aim.SURFACE, breath.aim());
    }

    /**
     * An upright paddler dives only with the air a dive costs in hand over the breathing line, so
     * the dive can never bring the bar back under it and turn into a breath on the way down.
     */
    @Test
    void anUprightPaddlerWaitsForTheAirADiveCosts() {
        Tick short_ = atTheSurface();
        short_.air = FULL / 2 + SwimPolicy.DIVE_AIR - 1;
        Tick enough = atTheSurface();
        enough.air = FULL / 2 + SwimPolicy.DIVE_AIR;

        assertEquals(SwimPolicy.Kind.BREATHE, short_.stroke().kind());
        assertFalse(short_.stroke().sneak(), "no diving with the bar short");
        assertFalse(short_.stroke().jump(), "and nothing to come up from");
        assertEquals(SwimPolicy.Kind.DIVE, enough.stroke().kind());
    }

    @Test
    void anUprightPaddlerWithItsEyesUnderComesUpAtHalfABar() {
        Tick sunk = atTheSurface();
        sunk.eyesUnder = true;
        sunk.depth = 1.5;
        sunk.air = FULL / 2;

        SwimPolicy.Stroke stroke = sunk.stroke();

        assertEquals(SwimPolicy.Kind.BREATHE, stroke.kind());
        assertTrue(stroke.jump(), "Space while the eyes are still under");
        assertEquals(SwimPolicy.Aim.SURFACE, stroke.aim());
    }

    @Test
    void theBreathIsReadOffTheBarSoARebuiltRouteKeepsIt() {
        int line = FULL / 2;
        assertTrue(SwimPolicy.breathing(true, line, FULL));
        assertFalse(SwimPolicy.breathing(true, line + 1, FULL));
        assertTrue(SwimPolicy.breathing(false, line + SwimPolicy.DIVE_AIR - 1, FULL));
        assertFalse(SwimPolicy.breathing(false, line + SwimPolicy.DIVE_AIR, FULL));
        assertFalse(SwimPolicy.breathing(false, FULL, FULL));
        assertFalse(SwimPolicy.breathing(false, 10, 10), "a bar too small for a dive still fills");
    }

    @Test
    void aBreathComesBeforeTheEmergencyDoes() {
        // WaterEscape takes the keys at two fifths of a bar. An ordinary crossing should never get
        // that far, so anyone with their eyes under has to be heading up first.
        assertTrue(SwimPolicy.COME_UP_AT > 0.4);
    }

    @Test
    void leavingTheWaterClimbsOntoAHigherBank() {
        Tick bank = holding();
        bank.targetIsWater = false;
        bank.climbing = true;

        SwimPolicy.Stroke stroke = bank.stroke();

        assertEquals(SwimPolicy.Kind.CLIMB_OUT, stroke.kind());
        assertTrue(stroke.jump());
        assertFalse(stroke.sneak());
        assertEquals(SwimPolicy.Aim.NODE, stroke.aim());
    }

    @Test
    void shallowWaterIsWadedNeverDived() {
        Tick ford = atTheSurface();
        ford.deep = false;

        SwimPolicy.Stroke stroke = ford.stroke();

        assertEquals(SwimPolicy.Kind.PADDLE, stroke.kind());
        assertFalse(stroke.sneak(), "one block of water never covers the eyes");
    }

    /**
     * No food for the run key, or a Walk card that was told not to sprint: no stroke will start, so
     * diving would only spend the air. It paddles the way it always did.
     */
    @Test
    void withoutTheRunKeyItPaddlesAtTheSurface() {
        Tick hungry = atTheSurface();
        hungry.canStroke = false;
        Tick sunk = atTheSurface();
        sunk.canStroke = false;
        sunk.climbing = true;

        assertEquals(SwimPolicy.Kind.PADDLE, hungry.stroke().kind());
        assertFalse(hungry.stroke().sneak());
        assertTrue(sunk.stroke().jump(), "Space back up to the waypoint it sank below");
    }

    /** Going down to a lower waypoint without the stroke: Shift, which is how a player sinks. */
    @Test
    void aPaddlerSinksToALowerWaypointWithShift() {
        Tick deeper = atTheSurface();
        deeper.canStroke = false;
        deeper.eyesUnder = true;
        deeper.descending = true;

        SwimPolicy.Stroke stroke = deeper.stroke();

        assertTrue(stroke.sneak());
        assertFalse(stroke.jump());
    }

    /** On a vine Shift is "hold on", and a bot holding on to sink never sinks. */
    @Test
    void shiftIsNeverPressedOnAClimbable() {
        Tick vineAtTheSurface = atTheSurface();
        vineAtTheSurface.onClimbable = true;
        Tick vineGoingDown = atTheSurface();
        vineGoingDown.onClimbable = true;
        vineGoingDown.canStroke = false;
        vineGoingDown.descending = true;
        Tick vineAtTheTop = holding();
        vineAtTheTop.onClimbable = true;
        vineAtTheTop.depth = 0.25;
        vineAtTheTop.verticalSpeed = 0.1;

        assertFalse(vineAtTheSurface.stroke().sneak());
        assertFalse(vineGoingDown.stroke().sneak());
        assertFalse(vineAtTheTop.stroke().sneak(), "not even as a brake");
    }

    /**
     * Vanilla drops a swimmer's sprint on the tick forward is let go, unless Shift is down; the
     * pose goes with it and the only way back is the dive. Steering lets go of forward through a
     * sharp turn, so the swimmer holds Shift there, and Space with it so the depth stays.
     */
    @Test
    void aTurnKeepsTheStrokeWithShiftAndSpaceTogether() {
        Tick tick = holding();
        SwimPolicy.Stroke stroke = tick.stroke();

        SwimPolicy.Stroke turning = SwimPolicy.throughTurn(stroke, tick.water(), false);
        SwimPolicy.Stroke straight = SwimPolicy.throughTurn(stroke, tick.water(), true);

        assertTrue(turning.sneak() && turning.jump(), "the two cancel, and Shift keeps the sprint");
        assertEquals(SwimPolicy.Kind.SWIM, turning.kind());
        assertEquals(stroke, straight, "with forward held there is nothing to keep");
    }

    @Test
    void aTurnWhileBrakingStillBrakes() {
        Tick popping = holding();
        popping.depth = 0.25;
        popping.verticalSpeed = 0.1;

        SwimPolicy.Stroke turning = SwimPolicy.throughTurn(popping.stroke(), popping.water(), false);

        assertTrue(turning.sneak());
        assertFalse(turning.jump(), "Space would undo the brake, and Shift alone keeps the sprint");
    }

    @Test
    void onlyASwimmerHasAStrokeToKeepThroughATurn() {
        Tick diving = atTheSurface();
        Tick vine = holding();
        vine.onClimbable = true;

        assertEquals(diving.stroke(), SwimPolicy.throughTurn(diving.stroke(), diving.water(), false));
        assertEquals(vine.stroke(), SwimPolicy.throughTurn(vine.stroke(), vine.water(), false),
                "on a vine Shift is hold on");
    }

    // ---- The hold, swum through vanilla's own vertical physics ----

    /**
     * One column of still water, the top of its surface block at {@link #TOP}, swum by the rules
     * read out of {@code LocalPlayer.aiStep}, {@code Player.travel}, {@code LivingEntity.travelInWater}
     * and {@code Entity.updateSwimming}: Shift takes 0.04 off the vertical speed and Space adds it,
     * a swimmer's look pulls that speed toward its own pitch, the body then moves, and 0.8 of the
     * speed is kept for the next tick, with gravity only for anybody not sprinting. The eyes are
     * in the water while they sit under the top; the body is while the feet do. The stroke needs
     * the eyes under to begin and only the body under to go on, and the sprint it rides on will
     * not start in water with the eyes out.
     */
    private static final class Pool {
        static final double TOP = 8.0 / 9.0;
        static final double STANDING_EYES = 1.62;
        static final double CROUCHING_EYES = 1.27;
        static final double SWIMMING_EYES = 0.4;

        double y;
        double verticalSpeed;
        double lookY;
        boolean sprinting;
        boolean swimming;
        boolean crouching;
        boolean inWater;
        boolean eyesUnder;
        int air = FULL;
        int minAir = FULL;
        int eyesUnderTicks;
        int outOfWaterTicks;
        int strokesLost;

        Pool(double y) {
            this.y = y;
            sense();
        }

        /** {@code Entity.baseTick}: the water, the eyes, and the stroke latching or lapsing. */
        void sense() {
            inWater = y + 0.001 <= TOP;
            double eyes = swimming ? SWIMMING_EYES : crouching ? CROUCHING_EYES : STANDING_EYES;
            eyesUnder = inWater && y + eyes <= TOP;
            boolean was = swimming;
            swimming = swimming ? sprinting && inWater : sprinting && eyesUnder;
            if (was && !swimming) {
                strokesLost++;
            }
        }

        SwimPolicy.Water water() {
            return new SwimPolicy.Water(swimming, eyesUnder, air, FULL, true, true, true,
                    TOP - y, verticalSpeed, true, y < 0.0, false, false);
        }

        void tick(SwimPolicy.Stroke keys, boolean forward) {
            // LocalPlayer.aiStep: the crouch, the sprint starting and stopping, then Shift.
            crouching = keys.sneak() && !swimming;
            if (!sprinting && forward && (!inWater || eyesUnder) && (!crouching || eyesUnder)) {
                sprinting = true;
            }
            if (sprinting) {
                if (swimming) {
                    if (!inWater || (!forward && !keys.sneak())) {
                        sprinting = false;
                    }
                } else if (inWater && !eyesUnder) {
                    sprinting = false;
                }
            }
            double speed = verticalSpeed;
            if (inWater && keys.sneak()) {
                speed -= 0.04;
            }
            // LivingEntity.aiStep: Space in water.
            if (inWater && keys.jump()) {
                speed += 0.04;
            }
            // Player.travel: a swimmer's look, when it looks down, is pressing Space, or is under.
            if (swimming && (lookY <= 0.0 || keys.jump() || y + 0.9 <= TOP)) {
                speed += (lookY - speed) * (lookY < -0.2 ? 0.085 : 0.06);
            }
            // LivingEntity.travelInWater: the move, then the drag, then gravity for a paddler.
            y += speed;
            if (inWater) {
                speed *= 0.8;
                if (!sprinting) {
                    speed -= 0.08 / 16.0;
                }
            } else {
                speed = (speed - 0.08) * 0.98;
            }
            verticalSpeed = speed;
            sense();
            air = eyesUnder ? air - 1 : Math.min(FULL, air + 4);
            minAir = Math.min(minAir, air);
            if (eyesUnder) {
                eyesUnderTicks++;
            }
            if (!inWater) {
                outOfWaterTicks++;
            }
        }

        void swim(int ticks) {
            for (int i = 0; i < ticks; i++) {
                tick(SwimPolicy.decide(water()), true);
            }
        }

        boolean breathingAndStroking() {
            return swimming && !eyesUnder && inWater;
        }
    }

    /** The model's own sanity: what vanilla does to somebody pressing nothing. */
    @Test
    void theModelSinksAnUprightPaddlerAndCoastsASwimmerAsVanillaDoes() {
        Pool paddler = new Pool(0.3);
        SwimPolicy.Stroke nothing = new SwimPolicy.Stroke(SwimPolicy.Kind.PADDLE, false, false,
                SwimPolicy.Aim.NODE);
        for (int i = 0; i < 40; i++) {
            paddler.tick(nothing, false);
        }
        assertEquals(-0.025, paddler.verticalSpeed, 1.0E-3,
                "a paddler pressing nothing sinks at vanilla's terminal speed");

        // A swimmer at the speed Space gives, let go of with a level look, coasts about 3.8 times
        // that speed; with the look a shade up, where the pull is off, five. COAST sits between.
        for (double look : new double[] {0.0, 0.001}) {
            Pool swimmer = new Pool(-2.0);
            swimmer.sprinting = true;
            swimmer.swimming = true;
            swimmer.lookY = look;
            swimmer.sense();
            swimmer.verticalSpeed = 0.121;
            double from = swimmer.y;
            for (int i = 0; i < 60; i++) {
                swimmer.tick(nothing, true);
            }
            double coasted = (swimmer.y - from) / 0.121;
            assertTrue(coasted >= 3.7 && coasted <= 5.1, "coasted " + coasted + " at look " + look);
            assertTrue(Math.abs(coasted - SwimPolicy.COAST) <= 1.1, "COAST is " + SwimPolicy.COAST);
        }
    }

    /**
     * The whole crossing, from upright at the top of the lake: the dive, the stroke latching, the
     * rise, and then a long hold with the eyes out, the body in, the stroke never lost and the air
     * never short. The user's own demonstration (2026-10-10), swum by the policy.
     */
    @Test
    void fromTheSurfaceADiveEndsSwimmingWithTheEyesOutAndStaysThere() {
        Pool pool = new Pool(0.3);

        int settled = -1;
        for (int tick = 0; tick < 60 && settled < 0; tick++) {
            pool.swim(1);
            if (pool.breathingAndStroking()) {
                settled = tick;
            }
        }
        assertTrue(settled >= 0, "swimming with the eyes out within three seconds");
        assertTrue(pool.minAir >= FULL - SwimPolicy.DIVE_AIR,
                "the dive spent " + (FULL - pool.minAir) + " air; DIVE_AIR is "
                        + SwimPolicy.DIVE_AIR);
        assertEquals(0, pool.outOfWaterTicks, "the rise never left the water");

        pool.eyesUnderTicks = 0;
        int held = 600;
        pool.swim(held);

        assertEquals(0, pool.strokesLost, "the stroke was never lost");
        assertEquals(0, pool.outOfWaterTicks, "the body never left the water");
        assertTrue(pool.eyesUnderTicks <= held / 50,
                "eyes under for " + pool.eyesUnderTicks + " of " + held + " ticks");
        assertEquals(FULL, pool.air, "a full bar at the end");
        assertTrue(pool.breathingAndStroking());
        assertEquals(SwimPolicy.SURFACE_DEPTH, Pool.TOP - pool.y, SwimPolicy.DEPTH_BAND + 0.05,
                "settled at the hold");
    }

    /**
     * A replan two blocks down - a dive that went deep, a fall into a lake - comes back to the hold
     * on the keys alone, without leaving the water on the way.
     */
    @Test
    void aSwimmerTwoBlocksDownComesBackToTheTopWithoutLeavingTheWater() {
        Pool pool = new Pool(-1.6);
        pool.sprinting = true;
        pool.swimming = true;
        pool.air = 250;
        pool.sense();

        int up = -1;
        for (int tick = 0; tick < 60 && up < 0; tick++) {
            pool.swim(1);
            if (pool.breathingAndStroking()) {
                up = tick;
            }
        }
        assertTrue(up >= 0 && up <= 40, "eyes out within two seconds, took " + up);
        pool.swim(200);

        assertEquals(0, pool.outOfWaterTicks);
        assertEquals(0, pool.strokesLost);
        assertTrue(pool.breathingAndStroking());
    }

    /**
     * The look is eased by LookController and is never exactly level. A shade up turns vanilla's
     * damping off and the swimmer coasts further; a shade down pulls it under. The hold copes
     * with both.
     */
    @Test
    void theHoldSurvivesALookThatIsNotQuiteLevel() {
        for (double look : new double[] {0.05, -0.05}) {
            Pool pool = new Pool(0.3);
            pool.lookY = look;
            pool.swim(80);
            pool.eyesUnderTicks = 0;
            pool.strokesLost = 0;
            int held = 600;
            pool.swim(held);

            assertEquals(0, pool.outOfWaterTicks, "left the water looking " + look);
            assertEquals(0, pool.strokesLost, "lost the stroke looking " + look);
            assertTrue(pool.eyesUnderTicks <= held / 20,
                    "eyes under for " + pool.eyesUnderTicks + " ticks looking " + look);
            assertTrue(pool.breathingAndStroking(), "not holding at the end, looking " + look);
        }
    }

    /** A corner: forward let go for a dozen ticks. The stroke survives it, and so does the hold. */
    @Test
    void aSharpTurnKeepsTheStrokeAndTheDepth() {
        Pool pool = new Pool(0.3);
        pool.swim(80);
        assertTrue(pool.breathingAndStroking());

        for (int tick = 0; tick < 12; tick++) {
            SwimPolicy.Water water = pool.water();
            pool.tick(SwimPolicy.throughTurn(SwimPolicy.decide(water), water, false), false);
        }
        assertEquals(0, pool.strokesLost, "the stroke went with forward");
        assertTrue(pool.swimming);
        pool.swim(40);
        assertTrue(pool.breathingAndStroking());
        assertEquals(0, pool.outOfWaterTicks);
    }
}
