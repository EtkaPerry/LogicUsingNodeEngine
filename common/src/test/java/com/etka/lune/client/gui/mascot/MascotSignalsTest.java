package com.etka.lune.client.gui.mascot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MascotSignalsTest {

    @Test
    void recognisesSharedMovementObstructions() {
        assertEquals(MascotSignals.Signal.BLOCKED,
                MascotSignals.classify("walking - no route found"));
        assertEquals(MascotSignals.Signal.BLOCKED,
                MascotSignals.classify("crop unreachable, trying the next one"));
        assertEquals(MascotSignals.Signal.BLOCKED,
                MascotSignals.classify("walled in - building a way out"));
        assertEquals(MascotSignals.Signal.BLOCKED,
                MascotSignals.classify("climb recovery made no progress"));
    }

    @Test
    void keepsEveryMascotStateDistinct() {
        assertEquals(MascotSignals.Signal.WAITING,
                MascotSignals.classify("waiting for a bite"));
        assertEquals(MascotSignals.Signal.DANGER,
                MascotSignals.classify("escaping to air while evading a Creeper"));
        assertEquals(MascotSignals.Signal.DANGER,
                MascotSignals.classify("no food - staying safe until health recovers"));
        assertEquals(MascotSignals.Signal.INVENTORY_FULL,
                MascotSignals.classify("inventory full, collected 24"));
        assertEquals(MascotSignals.Signal.MISSING_MATERIALS,
                MascotSignals.classify("no materials for requested weapon"));
        assertEquals(MascotSignals.Signal.SUCCESS,
                MascotSignals.classify("landed safely"));
        assertEquals(MascotSignals.Signal.SUCCESS,
                MascotSignals.classify("escaped lava"));
        assertEquals(MascotSignals.Signal.NONE,
                MascotSignals.classify("nothing ahead, taking a proper look around"));
    }

    @Test
    void classifiesCompletedAndFailedMilestones() {
        assertEquals(MascotSignals.Signal.SUCCESS,
                MascotSignals.classifyMilestone("Chop Wood finished"));
        assertEquals(MascotSignals.Signal.INVENTORY_FULL,
                MascotSignals.classifyMilestone("Stopped: inventory is full"));
        assertEquals(MascotSignals.Signal.MISSING_MATERIALS,
                MascotSignals.classifyMilestone("Craft failed: no materials"));
        assertEquals(MascotSignals.Signal.BLOCKED,
                MascotSignals.classifyMilestone("Goto failed: unknown obstruction"));
    }
}
