package com.etka.lune.bot.knowledge;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rings an eye's reading is checked against, which have to be the game's own. */
class StrongholdKnowledgeTest {

    @Test
    void theRingsHoldWhatTheGameDealsThem() {
        assertEquals(List.of(3, 6, 10, 15, 21, 28, 36, 9),
                Arrays.stream(StrongholdKnowledge.ringSizes()).boxed().toList());
    }

    @Test
    void cornersAreOnARingOrBetweenThem() {
        assertTrue(StrongholdKnowledge.onARing(1500, 900));
        assertFalse(StrongholdKnowledge.onARing(0, 0));
        assertFalse(StrongholdKnowledge.onARing(3500, 0));
        assertTrue(StrongholdKnowledge.onARing(5000, 0));
        assertTrue(StrongholdKnowledge.onARing(0, -8000));
    }

    @Test
    void fromSpawnTheNearestStrongholdIsOnTheFirstRing() {
        double bound = StrongholdKnowledge.nearestAtMost(0, 0);
        assertTrue(bound > 2880 && bound < 3000, "bound from spawn: " + bound);
        // Out between the first two rings, one of either is still within a few thousand blocks.
        double between = StrongholdKnowledge.nearestAtMost(3200, 0);
        assertTrue(between > 2000 && between < 4500, "bound between the rings: " + between);
    }
}
