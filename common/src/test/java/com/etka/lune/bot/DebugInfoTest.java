package com.etka.lune.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugInfoTest {

    @Test
    void keepsACompactDecisionTraceAndCountsRepeats() {
        DebugInfo debug = new DebugInfo();

        debug.decide("look ahead");
        debug.decide("look ahead");
        assertEquals(" x2", debug.decisionRepeatSuffix());
        debug.decide("walk east");

        assertEquals("walk east", debug.nextDecision);
        assertEquals("", debug.decisionRepeatSuffix());
        assertEquals(2, debug.decisionSnapshot().size());
        assertEquals("look ahead", debug.decisionSnapshot().get(0));
    }

    @Test
    void clearsTargetAndTaskContext() {
        DebugInfo debug = new DebugInfo();

        debug.targetLabel = "ship wood";
        debug.targetVerdict = "outside view (turn toward it)";
        debug.decide("turn toward candidate");

        assertTrue(debug.targetVerdict.contains("turn"));

        debug.clearDecisionTrace();
        assertEquals("", debug.intent);
        assertEquals("", debug.targetLabel);
        assertTrue(debug.decisions.isEmpty());
    }
}
