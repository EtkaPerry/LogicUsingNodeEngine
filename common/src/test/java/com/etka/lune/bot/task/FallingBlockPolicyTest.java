package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallingBlockPolicyTest {

    @Test
    void anIsolatedBlockIsMinedWhereItLies() {
        // A single gravel block on a beach collapses onto nothing, so there is no reason to pay for
        // a column scan or to climb.
        assertFalse(FallingBlockPolicy.wouldCollapse(0));
        assertEquals(0, FallingBlockPolicy.digOffset(0));
    }

    @Test
    void aColumnIsDugFromTheTop() {
        // Breaking the bottom of a stack drops the rest into the hole - onto the bot, and onto the
        // flint it just earned.
        assertTrue(FallingBlockPolicy.wouldCollapse(3));
        assertEquals(3, FallingBlockPolicy.digOffset(3));
    }

    @Test
    void theColumnScanIsBounded() {
        // A cliff of gravel must not turn one dig into an unbounded climb.
        assertEquals(FallingBlockPolicy.MAX_COLUMN_SCAN,
                FallingBlockPolicy.digOffset(FallingBlockPolicy.MAX_COLUMN_SCAN + 20));
    }

    @Test
    void nonsenseMeasurementsDoNotProduceNonsenseOffsets() {
        assertEquals(0, FallingBlockPolicy.digOffset(-1));
        assertEquals(0, FallingBlockPolicy.expectedDropFall(-4));
    }

    @Test
    void theDropSettlesWithTheColumnUnderIt() {
        // The item does not stay where the block was: the rest of the column collapses and the drop
        // rides down with it, which is why looking only at the broken position finds empty air.
        assertEquals(4, FallingBlockPolicy.expectedDropFall(4));
        assertEquals(0, FallingBlockPolicy.expectedDropFall(0));
    }
}
