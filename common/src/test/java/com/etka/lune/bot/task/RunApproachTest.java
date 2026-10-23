package com.etka.lune.bot.task;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunApproachTest {

    private static final BlockPos SPOT = new BlockPos(2, 91, -51);

    /**
     * The bastion of 2026-10-18: a fresh route to the same spot every second, each thrown off once at
     * a step down beside a hole. The second throw-off writes the spot off, across the rebuild.
     */
    @Test
    void aSpotWhoseWayKeepsThrowingTheBodyOffIsFailedAcrossRebuiltRoutes() {
        RunApproach approach = new RunApproach(2);
        approach.begin(SPOT);
        assertFalse(approach.failed(0));
        assertFalse(approach.failed(1), "thrown off once");
        approach.begin(SPOT);
        assertFalse(approach.failed(0), "a fresh route to the same spot counts from nothing");
        assertTrue(approach.failed(1), "and its first throw-off is the second");
    }

    @Test
    void aRouteReadEveryTickCountsEachThrowOffOnce() {
        RunApproach approach = new RunApproach(2);
        approach.begin(SPOT);
        assertFalse(approach.failed(1));
        assertFalse(approach.failed(1));
        assertFalse(approach.failed(1), "the same one throw-off, read three times");
        assertTrue(approach.failed(2));
    }

    /** The spot chosen a second later is often a block or two from the last: it is the same approach. */
    @Test
    void aSpotWithinTheGoalsReachIsTheSameApproach() {
        RunApproach approach = new RunApproach(2);
        approach.begin(SPOT);
        approach.failed(1);
        approach.begin(SPOT.offset(1, 0, -2));
        assertTrue(approach.failed(1));
    }

    @Test
    void anotherSpotStartsTheTallyAgain() {
        RunApproach approach = new RunApproach(2);
        approach.begin(SPOT);
        approach.failed(1);
        approach.begin(SPOT.offset(6, 0, 0));
        assertFalse(approach.failed(1), "a different way");
        approach.clear();
        assertFalse(approach.failed(5), "nothing to fail once cleared");
    }
}
