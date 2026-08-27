package com.etka.lune.routine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutineSafetyTest {

    @Test
    void installsOneGlobalMonitorWithoutChangingTheWorkFlow() {
        Routine routine = new Routine("Wood");
        RoutineNode chop = new RoutineNode("chop");
        chop.editorX = 24;
        chop.editorY = 26;
        routine.nodes.add(chop);

        assertTrue(RoutineSafety.installDefaultMonitor(routine));
        assertTrue(RoutineSafety.hasMonitor(routine));
        assertEquals(3, routine.nodes.size());
        assertTrue(routine.nodes.get(0).isAlwaysNode());
        assertEquals(RoutineSafety.COMMAND_ID, routine.nodes.get(1).commandId);
        assertEquals(0, routine.nodes.get(1).repeat);
        assertEquals(chop, routine.nodes.get(2));
        assertTrue(routine.nodes.get(0).editorY > chop.editorY);
        assertTrue(routine.nodes.get(0).alwaysTargets.contains(routine.nodes.get(1).id));

        assertFalse(RoutineSafety.installDefaultMonitor(routine));
        assertEquals(3, routine.nodes.size());
    }

    @Test
    void recognisesPerStepAndLegacyWhileConnections() {
        Routine perStep = new Routine("Per step");
        RoutineNode work = new RoutineNode("mine");
        RoutineNode guard = new RoutineNode(RoutineSafety.COMMAND_ID);
        work.onWhile = guard.id;
        perStep.nodes.add(work);
        perStep.nodes.add(guard);
        assertTrue(RoutineSafety.hasMonitor(perStep));

        Routine legacy = new Routine("Legacy");
        RoutineNode legacyGuard = new RoutineNode(RoutineSafety.COMMAND_ID);
        legacy.nodes.add(legacyGuard);
        legacy.onWhile = legacyGuard.id;
        assertTrue(RoutineSafety.hasMonitor(legacy));
    }

    @Test
    void doesNotMistakeAnUnwiredCommandForProtection() {
        Routine routine = new Routine("Unwired");
        routine.nodes.add(new RoutineNode(RoutineSafety.COMMAND_ID));

        assertFalse(RoutineSafety.hasMonitor(routine));
        assertTrue(RoutineSafety.installDefaultMonitor(routine));
        assertEquals(3, routine.nodes.stream()
                .filter(node -> node.isAlwaysNode() || RoutineSafety.COMMAND_ID.equals(node.commandId))
                .count());
    }

    @Test
    void connectedProtectionIsDisplayedAsInfiniteEvenWhenLoadedAsOnce() {
        Routine routine = new Routine("Old safe routine");
        RoutineNode always = new RoutineNode(RoutineNode.ALWAYS_COMMAND);
        RoutineNode guard = new RoutineNode(RoutineSafety.COMMAND_ID);
        guard.repeat = 1;
        always.alwaysTargets.add(guard.id);
        routine.nodes.add(always);
        routine.nodes.add(guard);

        assertTrue(RoutineSafety.isMonitorNode(routine, guard));
        guard.repeat = 0;
        assertEquals("x∞", guard.describeRepeat());
    }

}
