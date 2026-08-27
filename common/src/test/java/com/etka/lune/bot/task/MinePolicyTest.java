package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinePolicyTest {

    @Test
    void abandonsALongWalkForSomethingDramaticallyCloser() {
        // The reported case: three hundred blocks committed, and a forest underfoot on arrival.
        assertTrue(MinePolicy.shouldSwitchTarget(300.0, 8.0));
    }

    @Test
    void keepsGoingWhenTheNewSightingIsOnlyMarginallyBetter() {
        assertFalse(MinePolicy.shouldSwitchTarget(100.0, 60.0));
        assertFalse(MinePolicy.shouldSwitchTarget(100.0, 51.0));
    }

    @Test
    void switchesExactlyAtHalfTheRemainingDistance() {
        assertTrue(MinePolicy.shouldSwitchTarget(100.0, 50.0));
    }

    @Test
    void neverSecondGuessesAShortWalk() {
        // Retargeting near the destination is how a bot oscillates between two equal blocks.
        assertFalse(MinePolicy.shouldSwitchTarget(20.0, 1.0));
        assertFalse(MinePolicy.shouldSwitchTarget(23.9, 0.0));
    }

    @Test
    void ignoresNonsenseDistances() {
        assertFalse(MinePolicy.shouldSwitchTarget(100.0, -1.0));
    }
}
