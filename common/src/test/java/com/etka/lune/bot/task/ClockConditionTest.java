package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rule behind "work until 20:00": two readings in minutes, and one comparison. */
class ClockConditionTest {

    private static final long EIGHT_PM = 20 * 60L;

    @Test
    void beforePassesUntilTheMinuteArrivesAndNotOnIt() {
        assertTrue(ConditionTask.clockMatches(ConditionTask.BEFORE, 14 * 60L, EIGHT_PM));
        assertTrue(ConditionTask.clockMatches(ConditionTask.BEFORE, EIGHT_PM - 1, EIGHT_PM));
        assertFalse(ConditionTask.clockMatches(ConditionTask.BEFORE, EIGHT_PM, EIGHT_PM),
                "20:00 is not before 20:00; this is the minute the job stops");
        assertFalse(ConditionTask.clockMatches(ConditionTask.BEFORE, 22 * 60L, EIGHT_PM));
    }

    @Test
    void atOrAfterIsTheExactOppositeSoTheTwoEdgesCoverEveryMinute() {
        for (long minute = 0; minute < 24 * 60L; minute++) {
            assertTrue(ConditionTask.clockMatches(ConditionTask.BEFORE, minute, EIGHT_PM)
                            != ConditionTask.clockMatches(ConditionTask.AT_OR_AFTER, minute, EIGHT_PM),
                    "minute " + minute + " must satisfy exactly one of the two rules");
        }
    }

    /**
     * Deliberate, and the reason the tooltip says so: a clock rule is about the reading, not about
     * how long is left. "Before 20:00" is true again at one in the morning, because that is what
     * anybody reading the card will predict. Guessing that a job started at 22:00 meant "before
     * 20:00 tomorrow" would be a rule nobody wrote and nobody could see.
     */
    @Test
    void theRuleDoesNotTryToGuessWhichSideOfMidnightWasMeant() {
        assertTrue(ConditionTask.clockMatches(ConditionTask.BEFORE, 1 * 60L, EIGHT_PM));
        assertFalse(ConditionTask.clockMatches(ConditionTask.AT_OR_AFTER, 1 * 60L, EIGHT_PM));
    }

    /** Midnight as a target: everything is at or after it, nothing is before it. */
    @Test
    void midnightAsATargetIsReachedByEveryReading() {
        assertTrue(ConditionTask.clockMatches(ConditionTask.AT_OR_AFTER, 0L, 0L));
        assertFalse(ConditionTask.clockMatches(ConditionTask.BEFORE, 0L, 0L));
        assertTrue(ConditionTask.clockMatches(ConditionTask.AT_OR_AFTER, 23 * 60L + 59L, 0L));
    }

    /** A saved rule nobody recognises reads as Before, which stops rather than runs forever. */
    @Test
    void anUnknownRuleReadsAsBefore() {
        assertTrue(ConditionTask.clockMatches("Roughly", 14 * 60L, EIGHT_PM));
        assertFalse(ConditionTask.clockMatches("Roughly", 22 * 60L, EIGHT_PM));
        assertFalse(ConditionTask.clockMatches(null, 22 * 60L, EIGHT_PM));
    }
}
