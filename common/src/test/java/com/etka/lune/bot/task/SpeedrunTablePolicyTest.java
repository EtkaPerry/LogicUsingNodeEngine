package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedrunTablePolicyTest {

    @Test
    void longRouteSearchesFartherThanAnOrdinaryCraft() {
        assertTrue(SpeedrunTablePolicy.searchRadius(true)
                > SpeedrunTablePolicy.searchRadius(false));
        assertEquals(256, SpeedrunTablePolicy.searchRadius(true));
    }

    @Test
    void ordinaryCraftKeepsItsShortLocalSearch() {
        assertEquals(4, SpeedrunTablePolicy.searchRadius(false));
    }
}
