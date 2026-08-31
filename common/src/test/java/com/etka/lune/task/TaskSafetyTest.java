package com.etka.lune.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSafetyTest {

    @Test
    void installsOneGlobalMonitorWithoutChangingTheWorkFlow() {
        TaskGraph task = new TaskGraph("Wood");
        TaskNode chop = new TaskNode("chop");
        chop.editorX = 24;
        chop.editorY = 26;
        task.nodes.add(chop);

        assertTrue(TaskSafety.installDefaultMonitor(task));
        assertTrue(TaskSafety.hasMonitor(task));
        assertEquals(3, task.nodes.size());
        assertTrue(task.nodes.get(0).isClockNode());
        assertEquals(TaskSafety.COMMAND_ID, task.nodes.get(1).commandId);
        assertEquals(0, task.nodes.get(1).repeat);
        assertEquals(chop, task.nodes.get(2));
        assertTrue(task.nodes.get(0).editorY > chop.editorY);
        assertTrue(task.nodes.get(0).alwaysTargets.contains(task.nodes.get(1).id));

        assertFalse(TaskSafety.installDefaultMonitor(task));
        assertEquals(3, task.nodes.size());
    }

    @Test
    void recognisesPerStepAndLegacyWhileConnections() {
        TaskGraph perStep = new TaskGraph("Per step");
        TaskNode work = new TaskNode("mine");
        TaskNode guard = new TaskNode(TaskSafety.COMMAND_ID);
        work.onWhile = guard.id;
        perStep.nodes.add(work);
        perStep.nodes.add(guard);
        assertTrue(TaskSafety.hasMonitor(perStep));

        TaskGraph legacy = new TaskGraph("Legacy");
        TaskNode legacyGuard = new TaskNode(TaskSafety.COMMAND_ID);
        legacy.nodes.add(legacyGuard);
        legacy.onWhile = legacyGuard.id;
        assertTrue(TaskSafety.hasMonitor(legacy));
    }

    @Test
    void doesNotMistakeAnUnwiredCommandForProtection() {
        TaskGraph task = new TaskGraph("Unwired");
        task.nodes.add(new TaskNode(TaskSafety.COMMAND_ID));

        assertFalse(TaskSafety.hasMonitor(task));
        assertTrue(TaskSafety.installDefaultMonitor(task));
        assertEquals(3, task.nodes.stream()
                .filter(node -> node.isClockNode() || TaskSafety.COMMAND_ID.equals(node.commandId))
                .count());
    }

    @Test
    void acceptsAGuardReachedThroughOrdinaryFlowWiring() {
        // Always -> Check Player -> (Success) Self Preservation. The guard is not on a While pin
        // or an Always fan-out, but a run reaches it, so the task is protected.
        TaskGraph task = new TaskGraph("Watched run");
        TaskNode always = new TaskNode(TaskNode.ALWAYS_COMMAND);
        TaskNode check = new TaskNode("condition");
        TaskNode guard = new TaskNode(TaskSafety.COMMAND_ID);
        check.onSuccess = guard.id;
        always.alwaysTargets.add(check.id);
        task.nodes.add(always);
        task.nodes.add(check);
        task.nodes.add(guard);

        assertTrue(TaskSafety.hasMonitor(task));
        assertTrue(TaskSafety.isConnectedGuard(task, guard));
        assertFalse(TaskSafety.installDefaultMonitor(task));
        assertEquals(3, task.nodes.size());

        // Still an ordinary step rather than a live companion, so its repeat box stays the
        // player's to choose.
        assertFalse(TaskSafety.isMonitorNode(task, guard));
    }

    @Test
    void aGuardOnAnUnpoweredBranchIsStillMissingProtection() {
        // START -> mine is the whole run. The guard has a wire, but it comes from a card nothing
        // reaches - and both sit before the entry point, which fall-through never runs backwards to.
        TaskGraph task = new TaskGraph("Orphan guard");
        TaskNode stranded = new TaskNode("walk");
        TaskNode guard = new TaskNode(TaskSafety.COMMAND_ID);
        TaskNode start = new TaskNode(TaskNode.START_COMMAND);
        TaskNode mine = new TaskNode("mine");
        start.onSuccess = mine.id;
        stranded.onSuccess = guard.id;
        task.nodes.add(stranded);
        task.nodes.add(guard);
        task.nodes.add(start);
        task.nodes.add(mine);

        assertTrue(TaskWiring.hasIncomingConnection(task, guard));
        assertFalse(TaskSafety.hasMonitor(task));
        assertFalse(TaskSafety.isConnectedGuard(task, guard));
    }

    @Test
    void connectedProtectionIsDisplayedAsInfiniteEvenWhenLoadedAsOnce() {
        TaskGraph task = new TaskGraph("Old safe task");
        TaskNode always = new TaskNode(TaskNode.ALWAYS_COMMAND);
        TaskNode guard = new TaskNode(TaskSafety.COMMAND_ID);
        guard.repeat = 1;
        always.alwaysTargets.add(guard.id);
        task.nodes.add(always);
        task.nodes.add(guard);

        assertTrue(TaskSafety.isMonitorNode(task, guard));
        guard.repeat = 0;
        assertEquals("x∞", guard.describeRepeat());
    }

}
