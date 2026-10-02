package com.etka.lune.bot.path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwimPolicyTest {

    private static final int FULL = 300;

    /**
     * Upright at the top of a lake, the route going on across it, full air and the run key allowed:
     * the moment every crossing starts from.
     */
    private static SwimPolicy.Water atTheSurface() {
        return new SwimPolicy.Water(false, false, FULL, FULL, true, true, true, false, false, false);
    }

    private static SwimPolicy.Water swimming(int air) {
        return new SwimPolicy.Water(true, true, air, FULL, true, true, true, false, false, false);
    }

    /**
     * The bug this class exists for. Pitch does nothing to an upright paddler and vanilla will not
     * start the stroke with the head out, so Shift is the only way under - and Space, which the
     * route used to press whenever the bot sank below a surface waypoint, is the opposite of it.
     */
    @Test
    void aCrossingStartsWithShiftNotSpace() {
        SwimPolicy.Stroke stroke = SwimPolicy.decide(atTheSurface());

        assertEquals(SwimPolicy.Kind.DIVE, stroke.kind());
        assertTrue(stroke.sneak(), "Shift is what gets the eyes under");
        assertFalse(stroke.jump());
        assertEquals(SwimPolicy.Aim.LANE, stroke.aim());
    }

    /** Sunk a little below the surface waypoint, it used to read that as a step up and jump. */
    @Test
    void sinkingBelowASurfaceWaypointIsNotAReasonToJumpWhenSwimming() {
        SwimPolicy.Water belowTheLayer = new SwimPolicy.Water(
                true, true, FULL, FULL, true, true, true, true, false, false);

        SwimPolicy.Stroke stroke = SwimPolicy.decide(belowTheLayer);

        assertEquals(SwimPolicy.Kind.SWIM, stroke.kind());
        assertFalse(stroke.jump(), "the swimmer is meant to be under that layer");
    }

    @Test
    void onceTheStrokeIsOnTheLookDoesTheSteering() {
        SwimPolicy.Stroke stroke = SwimPolicy.decide(swimming(FULL));

        assertEquals(SwimPolicy.Kind.SWIM, stroke.kind());
        assertFalse(stroke.sneak());
        assertFalse(stroke.jump());
        assertEquals(SwimPolicy.Aim.LANE, stroke.aim());
    }

    /** Eyes under but the stroke not latched yet - turning, so W is up. Shift would only sink it. */
    @Test
    void itStopsDivingOnceTheEyesAreUnder() {
        SwimPolicy.Water under = new SwimPolicy.Water(
                false, true, FULL, FULL, true, true, true, false, false, false);

        assertFalse(SwimPolicy.decide(under).sneak());
    }

    @Test
    void itComesUpForAirAtHalfABar() {
        SwimPolicy.Stroke fine = SwimPolicy.decide(swimming(FULL / 2 + 1));
        SwimPolicy.Stroke low = SwimPolicy.decide(swimming(FULL / 2));

        assertEquals(SwimPolicy.Kind.SWIM, fine.kind());
        assertEquals(SwimPolicy.Kind.BREATHE, low.kind());
        assertTrue(low.jump(), "Space while the eyes are still under");
        assertEquals(SwimPolicy.Aim.SURFACE, low.aim());
    }

    /**
     * The other half of a breath. Diving again on the first bubble back would leave the bot bobbing
     * at the threshold for the rest of the crossing.
     */
    @Test
    void itStaysUpUntilTheBarIsFull() {
        SwimPolicy.Water topped = new SwimPolicy.Water(
                false, false, FULL - 4, FULL, true, true, true, false, false, false);

        SwimPolicy.Stroke stroke = SwimPolicy.decide(topped);

        assertEquals(SwimPolicy.Kind.BREATHE, stroke.kind());
        assertFalse(stroke.sneak(), "no diving with the bar short");
        assertEquals(SwimPolicy.Kind.DIVE, SwimPolicy.decide(atTheSurface()).kind(),
                "and with it full, back under");
    }

    @Test
    void aSwimmerWithItsEyesOutHoldsTheSurfaceWithoutSpace() {
        SwimPolicy.Water breathing = new SwimPolicy.Water(
                true, false, FULL / 2, FULL, true, true, true, true, false, false);

        assertFalse(SwimPolicy.decide(breathing).jump(),
                "Space would carry it right out of the water and end the stroke");
    }

    @Test
    void theBreathIsReadOffTheBarSoARebuiltRouteKeepsIt() {
        assertTrue(SwimPolicy.breathing(false, FULL - 1, FULL));
        assertFalse(SwimPolicy.breathing(false, FULL, FULL));
        assertFalse(SwimPolicy.breathing(true, FULL - 1, FULL));
        assertTrue(SwimPolicy.breathing(true, (int) (FULL * SwimPolicy.COME_UP_AT), FULL));
    }

    @Test
    void aBreathComesBeforeTheEmergencyDoes() {
        // WaterEscape takes the keys at two fifths of a bar. An ordinary crossing should never get
        // that far, so the swimmer has to be heading up first.
        assertTrue(SwimPolicy.COME_UP_AT > 0.4);
    }

    @Test
    void leavingTheWaterClimbsOntoAHigherBank() {
        SwimPolicy.Water bank = new SwimPolicy.Water(
                true, true, FULL, FULL, true, true, false, true, false, false);

        SwimPolicy.Stroke stroke = SwimPolicy.decide(bank);

        assertEquals(SwimPolicy.Kind.CLIMB_OUT, stroke.kind());
        assertTrue(stroke.jump());
        assertFalse(stroke.sneak());
        assertEquals(SwimPolicy.Aim.NODE, stroke.aim());
    }

    @Test
    void shallowWaterIsWadedNeverDived() {
        SwimPolicy.Water ford = new SwimPolicy.Water(
                false, false, FULL, FULL, true, false, true, false, false, false);

        SwimPolicy.Stroke stroke = SwimPolicy.decide(ford);

        assertEquals(SwimPolicy.Kind.PADDLE, stroke.kind());
        assertFalse(stroke.sneak(), "one block of water never covers the eyes");
    }

    /**
     * No food for the run key, or a Walk card that was told not to sprint: no stroke will start, so
     * diving would only spend the air. It paddles the way it always did.
     */
    @Test
    void withoutTheRunKeyItPaddlesAtTheSurface() {
        SwimPolicy.Water hungry = new SwimPolicy.Water(
                false, false, FULL, FULL, false, true, true, false, false, false);
        SwimPolicy.Water sunk = new SwimPolicy.Water(
                false, false, FULL, FULL, false, true, true, true, false, false);

        assertEquals(SwimPolicy.Kind.PADDLE, SwimPolicy.decide(hungry).kind());
        assertFalse(SwimPolicy.decide(hungry).sneak());
        assertTrue(SwimPolicy.decide(sunk).jump(), "Space back up to the waypoint it sank below");
    }

    /** Going down to a lower waypoint without the stroke: Shift, which is how a player sinks. */
    @Test
    void aPaddlerSinksToALowerWaypointWithShift() {
        SwimPolicy.Water deeper = new SwimPolicy.Water(
                false, true, FULL, FULL, false, true, true, false, true, false);

        SwimPolicy.Stroke stroke = SwimPolicy.decide(deeper);

        assertTrue(stroke.sneak());
        assertFalse(stroke.jump());
    }

    /** On a vine Shift is "hold on", and a bot holding on to sink never sinks. */
    @Test
    void shiftIsNeverPressedOnAClimbable() {
        SwimPolicy.Water vineAtTheSurface = new SwimPolicy.Water(
                false, false, FULL, FULL, true, true, true, false, false, true);
        SwimPolicy.Water vineGoingDown = new SwimPolicy.Water(
                false, false, FULL, FULL, false, true, true, false, true, true);

        assertFalse(SwimPolicy.decide(vineAtTheSurface).sneak());
        assertFalse(SwimPolicy.decide(vineGoingDown).sneak());
    }
}
