package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApproachWatchTest {

    /** Arrives without moving until the watch gives up, and reports which arrival did it. */
    private static int arrivalsUntilWriteOff(ApproachWatch watch) {
        for (int arrival = 1; arrival <= 1000; arrival++) {
            if (watch.arrived(arrival > 1, false)) {
                return arrival;
            }
        }
        return -1;
    }

    @Test
    void writesOffATargetItKeepsArrivingAtWithoutMoving() {
        // The reported loop: the route succeeds on its first tick because the bot is already on the
        // goal, the gate refuses the block, and the same route is built again next tick.
        assertEquals(ApproachWatch.MAX_FRUITLESS_ARRIVALS,
                arrivalsUntilWriteOff(new ApproachWatch()));
    }

    @Test
    void aWalkThatActuallyWentSomewhereIsNeverWrittenOff() {
        ApproachWatch watch = new ApproachWatch();
        for (int i = 0; i < 1000; i++) {
            assertFalse(watch.arrived(true, true),
                    "an approach that moved the bot has changed the gate's answer");
        }
        assertEquals(0, watch.fruitlessArrivals());
    }

    @Test
    void oneRealWalkClearsTheRun() {
        ApproachWatch watch = new ApproachWatch();
        for (int i = 0; i < ApproachWatch.MAX_FRUITLESS_ARRIVALS - 1; i++) {
            assertFalse(watch.arrived(true, false));
        }
        assertFalse(watch.arrived(true, true));
        // Back to a full budget: being somewhere new is a reason to try the gate again.
        assertEquals(ApproachWatch.MAX_FRUITLESS_ARRIVALS, arrivalsUntilWriteOff(watch));
    }

    @Test
    void changingTargetStartsTheCountAgain() {
        ApproachWatch watch = new ApproachWatch();
        for (int i = 0; i < ApproachWatch.MAX_FRUITLESS_ARRIVALS - 1; i++) {
            assertFalse(watch.arrived(true, false));
        }
        assertFalse(watch.arrived(false, false), "a different block has not been tried yet");
        assertEquals(1, watch.fruitlessArrivals());
    }

    @Test
    void aSwingSettlesEverythingBeforeIt() {
        ApproachWatch watch = new ApproachWatch();
        for (int i = 0; i < ApproachWatch.MAX_FRUITLESS_ARRIVALS - 1; i++) {
            assertFalse(watch.arrived(true, false));
        }
        watch.paidOff();

        assertEquals(0, watch.fruitlessArrivals());
        assertFalse(watch.siteIsTheProblem());
        assertEquals(ApproachWatch.MAX_FRUITLESS_ARRIVALS, arrivalsUntilWriteOff(watch));
    }

    /**
     * A canopy where no log can be cut from where the bot is standing would otherwise be worked
     * through one log at a time, repeating a geometry that has already failed.
     */
    @Test
    void enoughWrittenOffTargetsCondemnTheWholeSite() {
        ApproachWatch watch = new ApproachWatch();
        for (int target = 1; target < ApproachWatch.MAX_FRUITLESS_TARGETS; target++) {
            assertTrue(arrivalsUntilWriteOff(watch) > 0);
            assertFalse(watch.siteIsTheProblem(), "one bad log is not a bad tree");
        }
        assertTrue(arrivalsUntilWriteOff(watch) > 0);
        assertTrue(watch.siteIsTheProblem());
    }

    @Test
    void aProductiveSiteNeverAccumulatesTowardBeingCondemned() {
        ApproachWatch watch = new ApproachWatch();
        for (int log = 0; log < 20; log++) {
            for (int i = 0; i < ApproachWatch.MAX_FRUITLESS_ARRIVALS - 1; i++) {
                assertFalse(watch.arrived(true, false));
            }
            watch.paidOff();
            assertFalse(watch.siteIsTheProblem());
        }
    }
}
