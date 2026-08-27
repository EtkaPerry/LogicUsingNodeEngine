package com.etka.lune.bot.task;

import com.etka.lune.bot.task.ClutchPolicy.Method;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClutchPolicyTest {

    /** Standing reach below the feet: the arm's 4.5 blocks less the height of the eye. */
    private static final double REACH = 2.88;

    @Test
    void waterWinsWheneverItIsAvailable() {
        assertEquals(Method.WATER, ClutchPolicy.choose(Method.NONE, true, true, true, true, false));
        assertEquals(Method.WATER, ClutchPolicy.choose(Method.NONE, true, false, false, false, false));
    }

    @Test
    void theBoatIsWhatIsLeftWithNoBucket() {
        assertEquals(Method.BOAT, ClutchPolicy.choose(Method.NONE, false, false, true, true, false));
    }

    @Test
    void aFallWithNothingToClutchWithGetsNoMethod() {
        assertEquals(Method.NONE, ClutchPolicy.choose(Method.NONE, false, false, false, false, false));
    }

    @Test
    void spendsTheBlockRatherThanABoatItCannotReach() {
        // The 58-block drop that started all this: a hull can be put down but never climbed into,
        // and a cushion lands in one packet, so the block is the only thing that is actually a
        // clutch at that speed.
        assertEquals(Method.CUSHION, ClutchPolicy.choose(Method.NONE, false, true, true, false, false));
    }

    @Test
    void keepsTheCushionWhileTheBoatCanStillDoTheJob() {
        assertEquals(Method.BOAT, ClutchPolicy.choose(Method.NONE, false, true, true, true, false));
    }

    @Test
    void triesTheBoatAnywayWhenThereIsNothingElseToSpend() {
        assertEquals(Method.BOAT, ClutchPolicy.choose(Method.NONE, false, false, true, false, false));
    }

    @Test
    void staysWithTheMethodTheFallStartedOn() {
        // A bucket picked up mid-fall is no reason to abandon a hull already on its way down, and
        // the swap would cost the ticks that decide it.
        assertEquals(Method.BOAT, ClutchPolicy.choose(Method.BOAT, true, true, true, true, false));
        assertEquals(Method.WATER, ClutchPolicy.choose(Method.WATER, true, true, true, true, false));
        assertEquals(Method.CUSHION, ClutchPolicy.choose(Method.CUSHION, true, true, true, true, false));
    }

    @Test
    void fallsBackWhenTheChosenMethodGoesAway() {
        assertEquals(Method.CUSHION, ClutchPolicy.choose(Method.WATER, false, true, false, false, false));
        assertEquals(Method.WATER, ClutchPolicy.choose(Method.CUSHION, true, false, false, false, false));
    }

    @Test
    void learnedPreferenceNeverOverridesPhysicalViability() {
        assertEquals(Method.CUSHION, ClutchPolicy.choosePreferred(Method.NONE, Method.BOAT,
                false, true, true, false, false));
        assertEquals(Method.BOAT, ClutchPolicy.choosePreferred(Method.NONE, Method.BOAT,
                true, true, true, true, false));
        assertEquals(Method.WATER, ClutchPolicy.choosePreferred(Method.NONE, Method.WATER,
                true, true, true, true, false));
    }

    @Test
    void keepsTheBoatAfterTheItemHasBeenSpent() {
        // Placing takes the boat out of the inventory a tick before the hull arrives. Reading that
        // gap as "no boat any more" would send the clutch looking for something it never had.
        assertEquals(Method.BOAT, ClutchPolicy.choose(Method.BOAT, false, false, false, false, true));
    }

    @Test
    void givesUpWhenTheOnlyMethodIsGoneAndNothingWasSpent() {
        assertEquals(Method.NONE, ClutchPolicy.choose(Method.BOAT, false, false, false, false, false));
        assertEquals(Method.NONE, ClutchPolicy.choose(Method.WATER, false, false, false, false, false));
    }

    @Test
    void aShortDropIsStillSlowEnoughForABoat() {
        // Five blocks arrives at about 0.84 a tick, which is three and a half ticks of arm's
        // reach - room for the hull to come back and be climbed into.
        assertTrue(ClutchPolicy.boatHasTime(REACH, 5.0, 0.0));
    }

    @Test
    void anythingFasterIsGivenToTheBlockInstead() {
        // Eight blocks already arrives at 1.03 a tick: 2.8 ticks of reach, under the margin. The
        // margin errs this way on purpose - spending a hay bale costs a hay bale, and guessing the
        // boat had time costs the run.
        assertFalse(ClutchPolicy.boatHasTime(REACH, 8.0, 0.0));
        assertEquals(Method.CUSHION,
                ClutchPolicy.choose(Method.NONE, false, true, true,
                        ClutchPolicy.boatHasTime(REACH, 8.0, 0.0), false));
    }

    @Test
    void aDeepDropIsPastBoatingBeforeItStarts() {
        // The measured run: 58 blocks, arriving at 2.28 blocks a tick, which is 1.2 ticks of reach.
        assertFalse(ClutchPolicy.boatHasTime(REACH, 58.0, 0.0));
        assertEquals(2.28, ClutchPolicy.impactSpeed(58.0, 0.0), 0.05);
    }

    @Test
    void impactSpeedGrowsWithTheDropAndStopsAtTerminal() {
        assertTrue(ClutchPolicy.impactSpeed(30.0, 0.0) > ClutchPolicy.impactSpeed(10.0, 0.0));
        assertTrue(ClutchPolicy.impactSpeed(2000.0, 0.0) <= 3.93);
    }

    @Test
    void speedAlreadyCarriedCountsTowardTheImpact() {
        assertTrue(ClutchPolicy.impactSpeed(5.0, 2.0) > ClutchPolicy.impactSpeed(5.0, 0.0));
    }

    @Test
    void countsFallDamageTheWayTheGameDoes() {
        assertEquals(0, ClutchPolicy.fallDamage(3.0, 1.0));
        assertEquals(52, ClutchPolicy.fallDamage(55.0, 1.0));
        assertEquals(10, ClutchPolicy.fallDamage(55.0, 0.2));
        assertEquals(0, ClutchPolicy.fallDamage(55.0, 0.0));
    }

    @Test
    void knowsWhichCushionWouldActuallySaveTheFall() {
        // Ten hearts against a 55-block drop: hay leaves ten damage and is survivable, bare ground
        // is not, and a slime block or a web leaves nothing at all.
        assertTrue(ClutchPolicy.survives(55.0, 0.2, 20.0));
        assertFalse(ClutchPolicy.survives(55.0, 1.0, 20.0));
        assertTrue(ClutchPolicy.survives(55.0, 0.0, 1.0));
        // The same hay on the same drop with two hearts left is not a save.
        assertFalse(ClutchPolicy.survives(55.0, 0.2, 4.0));
    }
}
