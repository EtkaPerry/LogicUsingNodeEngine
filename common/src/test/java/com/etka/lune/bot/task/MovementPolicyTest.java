package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementPolicyTest {

    @Test
    void tacticsTradeSearchThoroughnessForGreediness() {
        double thorough = MovementPolicy.heuristicWeight(MovementPolicy.THOROUGH, 1.6);
        double balanced = MovementPolicy.heuristicWeight(MovementPolicy.BALANCED, 1.6);
        double quick = MovementPolicy.heuristicWeight(MovementPolicy.QUICK, 1.6);

        assertTrue(thorough < balanced);
        assertTrue(balanced < quick);
        assertTrue(thorough >= 1.0);
    }

    @Test
    void distanceContextDoesNotMemoriseCoordinates() {
        assertEquals("near", MovementPolicy.distanceBucket(4.0));
        assertEquals("medium", MovementPolicy.distanceBucket(20.0));
        assertEquals("far", MovementPolicy.distanceBucket(100.0));
    }
}
