package com.etka.lune.bot.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweepCursorTest {

    /** Walks the whole list in one slice, doing nothing with any candidate. */
    private static void sweepAll(SweepCursor cursor) {
        while (cursor.hasNext()) {
            cursor.next();
        }
    }

    @Test
    void anEmptyListIsAlreadyFinished() {
        SweepCursor cursor = new SweepCursor();
        cursor.reset(0);

        assertFalse(cursor.hasNext());
        assertEquals(SweepCursor.Outcome.NOTHING, cursor.finish());
        assertTrue(cursor.isComplete());
    }

    @Test
    void aFinishedSweepWithNoAcceptanceReportsNothingRatherThanStillLooking() {
        SweepCursor cursor = new SweepCursor();
        cursor.reset(5);

        sweepAll(cursor);

        assertEquals(SweepCursor.Outcome.NOTHING, cursor.finish());
        assertTrue(cursor.isComplete());
    }

    @Test
    void runningOutOfBudgetPartWayDownIsNotAnAnswer() {
        SweepCursor cursor = new SweepCursor();
        cursor.reset(100);

        cursor.next();
        cursor.next();

        assertEquals(SweepCursor.Outcome.KEEP_SWEEPING, cursor.finish());
        assertFalse(cursor.isComplete(), "two of a hundred is not a finished search");
    }

    @Test
    void aSliceResumesWhereTheLastOneStopped() {
        SweepCursor cursor = new SweepCursor();
        cursor.reset(4);

        assertEquals(0, cursor.next());
        assertEquals(1, cursor.next());
        cursor.finish();

        assertEquals(2, cursor.next());
        assertEquals(3, cursor.next());
        assertEquals(SweepCursor.Outcome.NOTHING, cursor.finish());
    }

    @Test
    void nothingBeyondAnAcceptanceIsExamined() {
        SweepCursor cursor = new SweepCursor();
        cursor.reset(50);

        cursor.next();
        cursor.next();
        cursor.accept(1);

        assertFalse(cursor.hasNext(), "candidates are in distance order; the rest are further");
        assertEquals(SweepCursor.Outcome.COMMIT, cursor.finish());
        assertEquals(1, cursor.accepted());
    }

    /** The whole point: a block ruled out is done with, a block behind the head is not. */
    @Test
    void aPostponedCandidateIsLookedAtAgain() {
        SweepCursor cursor = new SweepCursor();
        cursor.reset(10);

        // First pass: the near block is only postponed, a far one is acceptable.
        cursor.next();
        cursor.postpone(0);
        while (cursor.hasNext()) {
            int at = cursor.next();
            if (at == 7) {
                cursor.accept(7);
            }
        }

        assertEquals(SweepCursor.Outcome.KEEP_SWEEPING, cursor.finish(),
                "the far block must not win while a nearer one is still unanswered");
        assertEquals(0, cursor.next(), "the second pass starts at the postponed candidate");

        // Second pass: the head has come round and the near block is acceptable after all.
        cursor.accept(0);
        assertEquals(SweepCursor.Outcome.COMMIT, cursor.finish());
        assertEquals(0, cursor.accepted(), "the nearer block wins once it can be seen");
    }

    @Test
    void aPostponementFurtherAwayThanTheAcceptanceDoesNotDelayIt() {
        SweepCursor cursor = new SweepCursor();
        cursor.reset(10);

        cursor.next();
        cursor.accept(0);
        cursor.postpone(6);

        assertEquals(SweepCursor.Outcome.COMMIT, cursor.finish());
        assertEquals(0, cursor.accepted());
    }

    @Test
    void onlyTheNearestPostponementIsRemembered() {
        SweepCursor cursor = new SweepCursor();
        cursor.reset(10);

        cursor.next();
        cursor.postpone(2);
        assertTrue(cursor.hasPostponement());
        cursor.postpone(5);

        sweepAll(cursor);
        assertEquals(SweepCursor.Outcome.KEEP_SWEEPING, cursor.finish());
        assertEquals(2, cursor.next(), "the sweep goes back to the nearest one outstanding");
    }

    /** A head that never comes round must not hold the search open for the rest of the task. */
    @Test
    void aPostponementThatNeverResolvesGivesUpAfterABoundedNumberOfPasses() {
        SweepCursor cursor = new SweepCursor();
        cursor.reset(6);

        for (int pass = 0; pass < SweepCursor.MAX_POSTPONE_PASSES; pass++) {
            cursor.postpone(cursor.next());
            sweepAll(cursor);
            assertEquals(SweepCursor.Outcome.KEEP_SWEEPING, cursor.finish());
        }

        cursor.postpone(cursor.next());
        sweepAll(cursor);
        assertEquals(SweepCursor.Outcome.NOTHING, cursor.finish());
        assertTrue(cursor.isComplete(), "a spent budget must not leave the sweep looking forever");
    }

    @Test
    void aSpentBudgetStillCommitsWhateverWasAccepted() {
        SweepCursor cursor = new SweepCursor();
        cursor.reset(6);

        for (int pass = 0; pass < SweepCursor.MAX_POSTPONE_PASSES; pass++) {
            cursor.postpone(cursor.next());
            while (cursor.hasNext()) {
                int at = cursor.next();
                if (at == 4) {
                    cursor.accept(4);
                }
            }
            assertEquals(SweepCursor.Outcome.KEEP_SWEEPING, cursor.finish());
        }

        cursor.postpone(cursor.next());
        sweepAll(cursor);
        assertEquals(SweepCursor.Outcome.COMMIT, cursor.finish());
        assertEquals(4, cursor.accepted());
    }

    @Test
    void resetDropsEverythingTheLastSweepDecided() {
        SweepCursor cursor = new SweepCursor();
        cursor.reset(10);
        cursor.next();
        cursor.accept(0);
        cursor.postpone(3);

        cursor.reset(10);

        assertTrue(cursor.hasNext());
        assertEquals(0, cursor.next(), "a fresh sweep starts at the nearest candidate");
        assertEquals(-1, cursor.accepted());
        assertFalse(cursor.hasPostponement());
    }
}
