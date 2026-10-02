package com.etka.lune.bot.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The part of a geyser's reach that is arithmetic: which heights over the sulfur it pushes. */
class GeysersTest {

    @Test
    void itPushesFromTheWaterOnTheSulfurToSixBlocksPerBlockOfWater() {
        // Sulfur at y 60 under two blocks of water: the game pushes y 61 up to y 73.
        assertTrue(Geysers.inLift(60, 2, 61.0, 62.8), "standing in the water on the sulfur");
        assertTrue(Geysers.inLift(60, 2, 72.5, 74.3), "the top of the lift");
        assertFalse(Geysers.inLift(60, 2, 73.0, 74.8), "above the lift");
        assertFalse(Geysers.inLift(60, 2, 58.0, 59.8), "under the sulfur");
    }

    @Test
    void deeperWaterReachesHigher() {
        assertFalse(Geysers.inLift(60, 1, 70.0, 71.8));
        assertTrue(Geysers.inLift(60, 4, 70.0, 71.8));
    }

    @Test
    void noWaterOrTooMuchIsNoGeyser() {
        assertFalse(Geysers.inLift(60, 0, 61.0, 62.8));
        assertFalse(Geysers.inLift(60, Geysers.MAX_WATER + 1, 61.0, 62.8));
    }
}
