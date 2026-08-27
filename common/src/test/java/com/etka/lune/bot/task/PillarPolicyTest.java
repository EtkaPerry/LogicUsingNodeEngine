package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PillarPolicyTest {

    @Test
    void placementWindowsRemainInsideVanillaJumpHeight() {
        double early = PillarPolicy.clearance(PillarPolicy.EARLY);
        double balanced = PillarPolicy.clearance(PillarPolicy.BALANCED);
        double apex = PillarPolicy.clearance(PillarPolicy.APEX);

        assertTrue(early > 0.0);
        assertTrue(early < balanced);
        assertTrue(balanced < apex);
        assertTrue(apex < 1.25);
    }

    @Test
    void heightContextsStayBounded() {
        assertEquals("one", PillarPolicy.heightBucket(1));
        assertEquals("short", PillarPolicy.heightBucket(3));
        assertEquals("tall", PillarPolicy.heightBucket(8));
    }
}
