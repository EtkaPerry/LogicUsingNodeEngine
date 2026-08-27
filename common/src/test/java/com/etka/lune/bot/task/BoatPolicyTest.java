package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoatPolicyTest {

    @Test
    void iceIsWorthBoardingAlmostImmediately() {
        assertTrue(BoatPolicy.worthLaunching(3, true, true, 0));
        assertFalse(BoatPolicy.worthLaunching(2, true, true, 0));
    }

    @Test
    void openWaterHasToBeWideEnoughToBeatSwimmingAround() {
        assertFalse(BoatPolicy.worthLaunching(7, false, true, 0));
        assertTrue(BoatPolicy.worthLaunching(8, false, true, 0));
    }

    @Test
    void willNotPlanACrossingItCannotBuildTheBoatFor() {
        assertFalse(BoatPolicy.worthLaunching(40, true, false, 4));
        assertTrue(BoatPolicy.worthLaunching(40, true, false, 5));
        assertTrue(BoatPolicy.worthLaunching(40, true, true, 0));
    }

    @Test
    void countsOnlyTheMissingPlanks() {
        assertEquals(5, BoatPolicy.planksNeeded(0));
        assertEquals(1, BoatPolicy.planksNeeded(4));
        assertEquals(0, BoatPolicy.planksNeeded(9));
        assertEquals(5, BoatPolicy.planksNeeded(-3));
    }

    @Test
    void steersTowardTheHeadingAndHoldsCourseInsideTheDeadzone() {
        assertEquals(1, BoatPolicy.steer(30.0));
        assertEquals(-1, BoatPolicy.steer(-30.0));
        assertEquals(0, BoatPolicy.steer(5.0));
        assertEquals(0, BoatPolicy.steer(-5.0));
    }

    @Test
    void turnsBeforeItAcceleratesWhenPointingTheWrongWay() {
        assertTrue(BoatPolicy.shouldAccelerate(10.0));
        assertFalse(BoatPolicy.shouldAccelerate(120.0));
        assertFalse(BoatPolicy.shouldAccelerate(-120.0));
    }

    @Test
    void arrivesWithinThreeBlocksRatherThanTryingToDockExactly() {
        assertTrue(BoatPolicy.arrived(2.9));
        assertFalse(BoatPolicy.arrived(3.1));
    }

    @Test
    void stopsSteeringWhileTheTurnIsStillComingRound() {
        // A journal crossing ran 55 degrees of error down to zero at about six degrees a tick, then
        // sailed through to -26 and corrected back, over and over. Twelve degrees off and closing
        // gently will arrive on its own, so the rudder comes off early and momentum finishes it.
        assertEquals(0, BoatPolicy.steer(12.0, -2.0));
    }

    @Test
    void headsOffAnOvershootThatIsAlreadyGuaranteed() {
        // Twelve degrees off but swinging at six a tick: this one is going to sail straight past,
        // so opposite rudder now is the correction, not a wobble.
        assertEquals(-1, BoatPolicy.steer(12.0, -6.0));
    }

    @Test
    void stillCorrectsWhenTheTurnIsTooSlowToArrive() {
        // Same error, barely turning: this one genuinely needs the input.
        assertEquals(1, BoatPolicy.steer(12.0, -0.5));
    }

    @Test
    void countersATurnThatHasAlreadyOvershot() {
        // Pointing the right way but still swinging past it - steer against the rotation rather
        // than waiting for the error to grow.
        assertEquals(-1, BoatPolicy.steer(0.0, -4.0));
    }

    @Test
    void aSteadyHeadingWithNoRotationIsLeftAlone() {
        assertEquals(0, BoatPolicy.steer(0.0, 0.0));
        assertEquals(0, BoatPolicy.steer(3.0, 0.0));
    }

    @Test
    void theOldSingleArgumentFormStillMeansNoKnownTurn() {
        assertEquals(BoatPolicy.steer(20.0, 0.0), BoatPolicy.steer(20.0));
    }
}
