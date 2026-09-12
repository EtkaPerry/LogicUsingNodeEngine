package com.etka.lune.bot.path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SprintPolicyTest {

    /** Running forward on flat ground, head straight: the plain case. */
    private static SprintPolicy.Movement running() {
        return new SprintPolicy.Movement(true, true, true, false, false, false, false, false, 0.0);
    }

    @Test
    void aBotRunningForwardOnTheGroundSprints() {
        assertTrue(SprintPolicy.shouldSprint(running()));
    }

    /**
     * The rolling-terrain regression. Almost every node on natural ground changes height, so a rule
     * that dropped sprint for elevation left the bot walking whole journeys.
     */
    @Test
    void elevationChangeDoesNotStopTheSprint() {
        SprintPolicy.Movement climbing = new SprintPolicy.Movement(
                true, true, true, false, false, false, true, false, 0.0);
        SprintPolicy.Movement descending = new SprintPolicy.Movement(
                true, true, true, false, false, false, false, true, 0.0);

        assertTrue(SprintPolicy.shouldSprint(climbing),
                "a sprint-jump is the quickest way up a one-block step");
        assertTrue(SprintPolicy.shouldSprint(descending));
    }

    /**
     * The corner regression. The look controller eases at about fifteen degrees a tick, so a
     * ninety-degree turn left the head "wrong" for most of a second - and steering presses the
     * correct keys the whole time regardless.
     */
    @Test
    void headingErrorDoesNotStopTheSprint() {
        for (double error : new double[] {0.0, 29.0, 31.0, 90.0, 179.0}) {
            SprintPolicy.Movement turning = new SprintPolicy.Movement(
                    true, true, true, false, false, false, false, false, error);
            assertTrue(SprintPolicy.shouldSprint(turning),
                    "head " + error + " degrees off travel must not decide ground speed");
        }
    }

    @Test
    void everyCombinationOfSlopeAndHeadingGivesTheSameAnswer() {
        boolean expected = SprintPolicy.shouldSprint(running());
        for (boolean climbing : new boolean[] {false, true}) {
            for (boolean descending : new boolean[] {false, true}) {
                for (double error : new double[] {0.0, 45.0, 135.0}) {
                    SprintPolicy.Movement move = new SprintPolicy.Movement(
                            true, true, true, false, false, false, climbing, descending, error);
                    assertEquals(expected, SprintPolicy.shouldSprint(move),
                            "sprint must be independent of slope and heading");
                }
            }
        }
    }

    @Test
    void sprintingSidewaysOrBackwardsIsNotAThing() {
        SprintPolicy.Movement strafing = new SprintPolicy.Movement(
                true, false, true, false, false, false, false, false, 0.0);
        assertFalse(SprintPolicy.shouldSprint(strafing),
                "vanilla will not sprint without forward held");
    }

    @Test
    void aDeliberateWaterCrossingKeepsSprintThroughTheTurn() {
        SprintPolicy.Movement swimming = new SprintPolicy.Movement(
                true, false, false, true, true, false, false, false, 120.0);
        assertTrue(SprintPolicy.shouldSprint(swimming),
                "sprint is the crawl stroke; dropping it at the bank halves the crossing");
    }

    @Test
    void sprintIsNotHeldWhileHangingOnALadder() {
        SprintPolicy.Movement onLadder = new SprintPolicy.Movement(
                true, true, false, false, false, true, true, false, 0.0);
        assertFalse(SprintPolicy.shouldSprint(onLadder));
    }

    @Test
    void midAirPressesNothingBecauseSprintCarriesFromTheTakeOff() {
        SprintPolicy.Movement airborne = new SprintPolicy.Movement(
                true, true, false, false, false, false, false, false, 0.0);
        assertFalse(SprintPolicy.shouldSprint(airborne));
    }

    @Test
    void theUserSwitchAlwaysWins() {
        SprintPolicy.Movement forbidden = new SprintPolicy.Movement(
                false, true, true, false, true, false, false, false, 0.0);
        assertFalse(SprintPolicy.shouldSprint(forbidden));
    }

    @Test
    void stepsOffALedgeRatherThanLaunchingOffIt() {
        // The pathfinder only plans drops it can survive, but that promise is about the landing it
        // chose. Sprinting carries the bot past that landing onto whatever is further down: one
        // measured travel run took five falls of 4-6 blocks this way and ratcheted from y=61 to
        // bedrock, because each overshoot became the start of the next search.
        assertFalse(SprintPolicy.shouldSprint(new SprintPolicy.Movement(
                true, true, true, false, false, false, false, true,
                SprintPolicy.LEDGE_DROP, 0.0)));
    }

    @Test
    void aOneBlockSlopeIsStillSprinted() {
        // The rule this replaces switched sprinting off on any height change, and on rolling ground
        // that is nearly every node - which is what made the bot walk its journeys.
        assertTrue(SprintPolicy.shouldSprint(new SprintPolicy.Movement(
                true, true, true, false, false, false, false, true, 1, 0.0)));
    }
}
