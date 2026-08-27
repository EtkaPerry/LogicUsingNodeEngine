package com.etka.lune.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunTraceTest {

    @Test
    void reportsEveryActionThatCanBlockAUserVisibleRun() {
        String blocked = BlockedReason.describe("placement space occupied", "no solid support face",
                "visible obstruction before requested block", "outside view (turn toward it)",
                11, 42, "possible loop: no inventory/phase change for 120 ticks");

        assertTrue(blocked.contains("obstruction=placement space occupied"));
        assertTrue(blocked.contains("placement=no solid support face"));
        assertTrue(blocked.contains("break=visible obstruction before requested block"));
        assertTrue(blocked.contains("target=outside view"));
        assertTrue(blocked.contains("waypoint_stall_ticks=11"));
        assertTrue(blocked.contains("goal_stall_ticks=42"));
        assertTrue(blocked.contains("possible loop"));
    }

    @Test
    void reportsNoBlockerForAnOrdinaryAction() {
        assertEquals("none", BlockedReason.describe("", "", "breaking",
                "visible and actionable", 0, 0, ""));
    }

}
