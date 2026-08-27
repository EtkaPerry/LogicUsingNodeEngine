package com.etka.lune.bot.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillOutcomeTest {

    @Test
    void equalWorkRateScoresSmallAndGiantTreesEqually() {
        SkillOutcome small = SkillOutcome.evaluate(true, 5, 5, 100, 0.0);
        SkillOutcome giant = SkillOutcome.evaluate(true, 30, 30, 600, 0.0);

        assertEquals(small.reward(), giant.reward());
        assertEquals(small.unitsPerSecond(), giant.unitsPerSecond());
    }

    @Test
    void improvementBeatsBaselineAndQuickAbandonmentDoesNot() {
        SkillOutcome faster = SkillOutcome.evaluate(true, 10, 10, 100, 20.0, 15.0);
        SkillOutcome slower = SkillOutcome.evaluate(true, 10, 10, 300, 20.0, 15.0);
        SkillOutcome abandoned = SkillOutcome.evaluate(false, 2, 10, 20, 20.0);
        SkillOutcome almostDoneButFailed = SkillOutcome.evaluate(false, 9, 10, 20, 20.0);

        assertTrue(faster.reward() > slower.reward());
        assertTrue(slower.reward() > abandoned.reward());
        assertTrue(abandoned.reward() < 0.0);
        assertTrue(almostDoneButFailed.reward() < 0.0);
        assertTrue(faster.bestTime());
        assertFalse(slower.bestTime());
    }

    @Test
    void nothingToDoIsNeutralButGivingUpIsNot() {
        SkillOutcome nothingThere = SkillOutcome.evaluate(true, 0, 64, 80, 0.0);
        SkillOutcome gaveUpEmptyHanded = SkillOutcome.evaluate(false, 0, 64, 80, 0.0);

        assertEquals(0.0, nothingThere.reward());
        assertEquals(-10.0, gaveUpEmptyHanded.reward());
        // A job with nothing to show cannot claim a best time, however fast it noticed.
        assertFalse(nothingThere.bestTime());
    }

    @Test
    void anEmptySweepDoesNotOutscoreRealWork() {
        SkillOutcome nothingThere = SkillOutcome.evaluate(true, 0, 64, 80, 0.0);
        SkillOutcome slowButProductive = SkillOutcome.evaluate(true, 4, 64, 4000, 0.0);

        assertTrue(slowButProductive.reward() > nothingThere.reward());
    }
}
