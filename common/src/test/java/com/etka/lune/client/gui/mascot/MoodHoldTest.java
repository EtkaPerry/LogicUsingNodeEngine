package com.etka.lune.client.gui.mascot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A job steps between kinds of work every few ticks, and her face must not follow every step.
 * Trouble, though, is never kept waiting.
 */
class MoodHoldTest {

    private static final MascotAdvisor.Mood DIGGING = MascotAdvisor.Mood.DIGGING;
    private static final MascotAdvisor.Mood WORKING = MascotAdvisor.Mood.WORKING;
    private static final MascotAdvisor.Mood TRAVEL = MascotAdvisor.Mood.TRAVEL;
    private static final MascotAdvisor.Mood DANGER = MascotAdvisor.Mood.DANGER;

    @Test
    void anotherJobFaceWaitsItsTurn() {
        MoodHold hold = new MoodHold();
        MascotAdvisor.Mood shown = DIGGING;
        for (int tick = 1; tick < MoodHold.HOLD_TICKS; tick++) {
            shown = hold.next(shown, TRAVEL);
            assertEquals(DIGGING, shown, "switched to travel after " + tick + " ticks");
        }
        assertEquals(TRAVEL, hold.next(shown, TRAVEL));
    }

    @Test
    void aStepShorterThanTheHoldNeverShows() {
        // Mine between two blocks: a couple of ticks of lines with no face, then digging again.
        MoodHold hold = new MoodHold();
        MascotAdvisor.Mood shown = DIGGING;
        shown = hold.next(shown, WORKING);
        shown = hold.next(shown, WORKING);
        assertEquals(DIGGING, shown);
        shown = hold.next(shown, DIGGING);
        assertEquals(DIGGING, shown);
        for (int tick = 1; tick < MoodHold.HOLD_TICKS; tick++) {
            shown = hold.next(shown, WORKING);
        }
        assertEquals(DIGGING, shown, "the count carried over from the earlier flicker");
    }

    @Test
    void troubleIsShownAtOnceAndSoIsTheFaceAfterIt() {
        MoodHold hold = new MoodHold();
        assertEquals(DANGER, hold.next(DIGGING, DANGER));
        assertEquals(DIGGING, hold.next(DANGER, DIGGING));
    }

    @Test
    void theFirstFaceIsShownAtOnce() {
        assertEquals(DIGGING, new MoodHold().next(null, DIGGING));
    }
}
