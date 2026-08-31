package com.etka.lune.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopWatchTest {

    private static final int ENOUGH_TICKS = LoopWatch.MIN_TICKS;

    @Test
    void aStepHoldingMostOfTheTickIsReported() {
        assertTrue(LoopWatch.isWorthReporting(ENOUGH_TICKS, 52_000, 0.0, false));
    }

    @Test
    void aCheapStepRestartingConstantlyIsReported() {
        assertTrue(LoopWatch.isWorthReporting(ENOUGH_TICKS, 40, 9.0, false));
    }

    /**
     * The rule that keeps this from being a nag. A mining run that is genuinely producing blocks
     * is allowed to be expensive - the player asked for it, and interrupting them to say their
     * task is working is noise.
     */
    @Test
    void aProductiveStepIsNeverReportedHoweverExpensive() {
        assertFalse(LoopWatch.isWorthReporting(ENOUGH_TICKS, 200_000, 20.0, true));
    }

    @Test
    void aWindowWithTooFewSamplesSaysNothing() {
        assertFalse(LoopWatch.isWorthReporting(ENOUGH_TICKS - 1, 52_000, 0.0, false));
    }

    @Test
    void ordinaryCheapWorkIsNotReported() {
        assertFalse(LoopWatch.isWorthReporting(ENOUGH_TICKS, 120, 0.2, false));
    }

    /** Nothing may be reported before a window has closed, however bad the samples look. */
    @Test
    void noVerdictIsOfferedBeforeTheFirstWindowCloses() {
        LoopWatch watch = LoopWatch.get();
        watch.clear();
        for (int tick = 0; tick < ENOUGH_TICKS * 2; tick++) {
            watch.sample("node-1", "Mine", "Test Task", true, 52_000_000L, false);
        }
        assertNull(watch.worst());
    }

    @Test
    void anUnknownNodeIsIgnoredRatherThanCounted() {
        LoopWatch watch = LoopWatch.get();
        watch.clear();
        watch.sample(null, "Mine", "Test Task", true, 52_000_000L, false);
        watch.recordRestart(null);
        assertNull(watch.worst());
    }

    /**
     * The whole verdict, assembled the way the runner assembles it, with the window forced shut so
     * the test does not have to wait five real seconds for it.
     */
    @Test
    void aClosedWindowNamesTheOffendingStep() throws Exception {
        LoopWatch watch = LoopWatch.get();
        watch.clear();
        for (int tick = 0; tick < ENOUGH_TICKS; tick++) {
            watch.sample("node-1", "Mine", "Tas Topla", true, 52_000_000L, false);
        }
        forceWindowOpenedLongAgo(watch);

        LoopWatch.Spin spin = watch.worst();
        assertNotNull(spin);
        assertTrue(spin.heavyPerTick());
        assertTrue(spin.permanent());
        assertTrue(spin.label().equals("Mine"));
        assertTrue(spin.taskName().equals("Tas Topla"));
        watch.clear();
    }

    /** Backdates the window start, since the class deliberately measures in real time. */
    private static void forceWindowOpenedLongAgo(LoopWatch watch) throws Exception {
        var field = LoopWatch.class.getDeclaredField("windowBegan");
        field.setAccessible(true);
        field.setLong(watch, System.nanoTime() - LoopWatch.WINDOW_NANOS * 2);
    }
}
