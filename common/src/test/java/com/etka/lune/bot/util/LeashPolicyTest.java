package com.etka.lune.bot.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeashPolicyTest {

    @Test
    void theBoundaryItselfIsStillInside() {
        assertFalse(LeashPolicy.outside(60.0, 60));
        assertTrue(LeashPolicy.outside(60.1, 60));
    }

    @Test
    void comesBackInsideTheLineBeforeHandingWorkBack() {
        // Releasing on the boundary hands over to a job that was already walking outwards, which
        // crosses again next step: the leash would stutter instead of holding.
        assertTrue(LeashPolicy.returnRadius(60) < 60);
    }

    @Test
    void aBigAreaDoesNotDemandALongWalkBackForAStepOverTheLine() {
        // A fifth of 500 would be a hundred blocks of trudging to resume; the cap keeps it short.
        assertEquals(484, LeashPolicy.returnRadius(500));
    }

    @Test
    void asmallAreaStillLeavesRoomToStandIn() {
        assertTrue(LeashPolicy.returnRadius(8) >= 1);
        assertTrue(LeashPolicy.returnRadius(8) < 8);
        assertEquals(1, LeashPolicy.returnRadius(1));
    }
}
